package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ExecutionTarget;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.FacetRole;
import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.PluginFacet;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.FibraException;
import com.sstlfsj.fibra.ManagedPluginControl;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstance;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredEvaluation;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.engine.CompiledRuntimeSlice;
import com.sstlfsj.fibra.engine.DeploymentTarget;
import com.sstlfsj.fibra.engine.ExecutionUnitKey;
import com.sstlfsj.fibra.engine.HostCapabilitySnapshot;
import com.sstlfsj.fibra.engine.PluginFacetSource;
import com.sstlfsj.fibra.engine.PluginSelection;
import com.sstlfsj.fibra.engine.ResolvedFacetDependency;
import com.sstlfsj.fibra.engine.RuntimeTargetSlice;
import com.sstlfsj.fibra.engine.RuntimeHostServices;
import com.sstlfsj.fibra.engine.RuntimeUnitDisableRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaRuntimeDriverTest {
    @TempDir Path work;

    @Test
    void activePluginFailureClosesAdmissionAndRequestsOneReplacement() {
        var health = reactor.core.publisher.Sinks.<Void>one();
        var mounted = new AtomicReference<PluginInstance<?>>();
        var definition = PluginDefinition.builder("supervised", Void.class,
            () -> (context, ignored) -> {
                mounted.set(context.plugins().current().orElseThrow());
                context.effects().supervise(health.asMono(), "health");
                return reactor.core.publisher.Mono.empty();
            }).build();
        var builtIn = builtIn(definition);
        var key = new ExecutionUnitKey("supervised-unit");
        var graph = new DesiredInputGraph(List.of(builtInEntry(key.value(), "supervised")));
        try (var services = new LiveHostServices()) {
            var driver = new JavaRuntimeDriver(services, List.of(builtIn));
            var generation = sealedBuiltIn(driver, builtIn, graph,
                Map.of(key, List.of())).generation();
            var unit = generation.units().get(key);
            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.ACTIVE,
                unit.reconcileAsync("activate").block().aggregateState());
            services.observationRefreshes.clear();

            health.tryEmitError(new IllegalStateException("health failed"));
            mounted.get().settled().onErrorResume(error ->
                reactor.core.publisher.Mono.empty()).block(java.time.Duration.ofSeconds(5));

            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.FAILED,
                unit.snapshot().aggregateState());
            assertEquals(List.of(Set.of(key)), services.reconcileRequests.stream()
                .map(request -> request.stream()
                    .map(com.sstlfsj.fibra.engine.RuntimeUnitFence::unitKey)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()))
                .toList());
            assertThrows(IllegalStateException.class,
                () -> unit.reconcileAsync("cannot-restart-failed-unit").block());
            var identity = unit.snapshot().executions().getFirst().runtimeInstanceId();
            assertTrue(services.observationRefreshes.stream().anyMatch(fence ->
                fence.unitKey().equals(key) && fence.runtimeInstanceId().equals(identity)));
            stop(unit, "failed");
            generation.retireAsync().block();
            assertEquals(1, services.reconcileRequests.size());
            driver.closeAsync().block();
        }
    }

    @Test
    void generationObservationFreezesTheCompiledCapabilitySnapshot() {
        var definition = PluginDefinition.builder("capability-snapshot", Void.class,
            () -> (context, ignored) -> reactor.core.publisher.Mono.empty()).build();
        var builtIn = builtIn(definition);
        var key = new ExecutionUnitKey("capability-unit");
        var graph = new DesiredInputGraph(List.of(builtInEntry(key.value(),
            definition.name())));
        var target = DeploymentTarget.of(1, List.of(), graph,
            ConfigContextSnapshot.empty());
        var slice = RuntimeTargetSlice.builder(JavaRuntimeProvider.RUNTIME_ID,
                target)
            .desired(DesiredEvaluation.evaluate(graph, target.configContext()))
            .capabilities(HostCapabilitySnapshot.of(Map.of("host.storage", false)))
            .facets(List.of()).builtInPackages(List.of(builtIn.metadata()))
            .affectedEntryIds(Set.of(key.value()))
            .unitDependencies(Map.of(key, List.of())).build();
        try (var services = new LiveHostServices()) {
            var driver = new JavaRuntimeDriver(services, List.of(builtIn));
            var candidate = driver.createCandidate(slice);
            candidate.prepareAsync().block();
            var generation = candidate.seal(CompiledRuntimeSlice.of(
                candidate.preparedPlan(), List.of(key)));
            var unit = generation.units().get(key);

            assertEquals(Set.of("host.storage"), unit.reconcileAsync("activate")
                .block().executions().getFirst().capabilities());

            stop(unit, "capability");
            generation.retireAsync().block();
            driver.closeAsync().block();
        }
    }

    @Test
    void preparationBuildsAnEntryUnitWithoutStartingThePlugin() throws Exception {
        var facet = facet("app", "entrypoint: fixture.SampleEntrypoint\n",
            fixture.SampleEntrypoint.class);
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of());
        var candidate = driver.createCandidate(slice(
            new PluginFacetSource(facet, List.of())));

        candidate.prepareAsync().block();

        var key = new ExecutionUnitKey("app");
        var plan = candidate.preparedPlan();
        assertEquals(JavaRuntimeProvider.RUNTIME_ID, plan.runtimeId());
        assertEquals(List.of(key), List.copyOf(plan.units().keySet()));
        assertEquals(facet.artifactId(), plan.units().get(key).artifactId());
        assertTrue(plan.units().get(key).dependencies().isEmpty());

        var generation = candidate.seal(CompiledRuntimeSlice.of(plan, List.of(key)));
        assertTrue(driver.snapshot().units().isEmpty());
        assertEquals(List.of(key), List.copyOf(generation.units().keySet()));
        generation.abortAsync().block();
        assertTrue(driver.snapshot().units().isEmpty());
        candidate.closeAsync().block();
        driver.closeAsync().block();
    }

    @Test
    void preparedPlanUsesDesiredEntryIdsAndEngineDependencies() throws Exception {
        var base = facet("base", "entrypoint: fixture.SampleEntrypoint\n", fixture.SampleEntrypoint.class);
        var app = facet("app", "entrypoint: fixture.SampleEntrypoint\n", fixture.SampleEntrypoint.class);
        var baseSource = new PluginFacetSource(base, List.of());
        var appSource = new PluginFacetSource(app, List.of(
            new ResolvedFacetDependency(base.pluginId(), base.packageRevision(),
                base.facet().facetId(), base.artifactId())));
        var candidate = new JavaRuntimeDriver(new NoopHostServices(), List.of()).createCandidate(slice(baseSource,
            appSource));

        candidate.prepareAsync().block();

        assertEquals(List.of(new ExecutionUnitKey("base")), candidate.preparedPlan()
            .units().get(new ExecutionUnitKey("app")).dependencies());
    }

    @Test
    void failedPreparationCanBeClosedRepeatedly() throws Exception {
        var facet = facet("invalid", "entrypoint: 42\n", null);
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of());
        var candidate = driver.createCandidate(slice(
            new PluginFacetSource(facet, List.of())));

        assertThrows(JavaRuntimeException.class,
            () -> candidate.prepareAsync().block());
        candidate.closeAsync().block();
        candidate.closeAsync().block();
        driver.closeAsync().block();
    }

    @Test
    void staticClassSpaceIsSharedAcrossAttemptsAndClosesAfterTheLastLease() throws Exception {
        var facet = facet("app", "entrypoint: fixture.PreparationEntrypoint\n",
            fixture.PreparationEntrypoint.class);
        var definitions = new ArrayList<ClassLoader>();
        var closes = new ArrayList<PluginClassLoader>();
        fixture.PreparationObserver.callback = definitions::add;
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of(),
            getClass().getClassLoader(), List.of("java.", "com.sstlfsj.fibra.", "reactor.",
                "fixture.PreparationObserver"),
            loader -> { closes.add(loader); loader.close(); });
        try {
            var first = driver.createCandidate(slice(new PluginFacetSource(facet, List.of())));
            first.prepareAsync().block();
            var firstGeneration = first.seal(CompiledRuntimeSlice.of(first.preparedPlan(),
                List.of(new ExecutionUnitKey("app"))));
            var second = driver.createCandidate(slice(new PluginFacetSource(facet, List.of())));
            second.prepareAsync().block();
            var secondGeneration = second.seal(CompiledRuntimeSlice.of(second.preparedPlan(),
                List.of(new ExecutionUnitKey("app"))));

            assertEquals(1, definitions.size());
            firstGeneration.abortAsync().block();
            assertTrue(closes.isEmpty());
            secondGeneration.abortAsync().block();
            assertEquals(1, closes.size());
        } finally {
            fixture.PreparationObserver.callback = null;
            driver.closeAsync().block();
        }
    }

    @Test
    void builtInMetadataMustExactlyMatchPrivateDefinitions() {
        var facet = com.sstlfsj.fibra.engine.BuiltInFacet.builder(new FacetId("host"),
            JavaRuntimeProvider.RUNTIME_ID, new ExecutionTarget("host"))
            .definitionIds(Set.of("sample")).build();
        var metadata = com.sstlfsj.fibra.engine.BuiltInPluginPackage.builder()
            .pluginId(new PluginId("built-in")).version("1")
            .packageDigest("a".repeat(64)).facets(List.of(facet)).build();
        var definition = PluginDefinition.builder("sample", Void.class,
            () -> (context, config) -> reactor.core.publisher.Mono.empty()).build();

        new JavaBuiltInPackage(metadata, Map.of(new FacetId("host"),
            List.of(new JavaDefinitionEntry<>(definition, ignored -> null))));
        assertThrows(IllegalArgumentException.class, () -> new JavaBuiltInPackage(metadata,
            Map.of(new FacetId("host"), List.of())));
    }

    @Test
    void runningJavaUnitPublishesItsOwnedRegistrarAndClosesRealRoutes()
        throws Exception {
        var facet = facet("contribution",
            "entrypoint: fixture.ContributionEntrypoint\n",
            fixture.ContributionEntrypoint.class);
        var published = new AtomicReference<fixture.ContributionObserver.Published>();
        fixture.ContributionObserver.callback = published::set;
        try (var services = new LiveHostServices()) {
            var driver = new JavaRuntimeDriver(services, List.of(),
                getClass().getClassLoader(), List.of("java.", "com.sstlfsj.fibra.",
                    "reactor.", "org.reactivestreams.", "org.slf4j.",
                    "fixture.ContributionObserver"), PluginClassLoader::close);
            var candidate = driver.createCandidate(slice(
                new PluginFacetSource(facet, List.of())));
            candidate.prepareAsync().block();
            var plan = candidate.preparedPlan();
            var generation = candidate.seal(CompiledRuntimeSlice.of(plan,
                List.of(new ExecutionUnitKey("contribution"))));
            var unit = generation.units().get(
                new ExecutionUnitKey("contribution"));

            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.ACTIVE,
                unit.reconcileAsync("start").block().aggregateState());
            var registration = published.get();
            assertEquals(new com.sstlfsj.fibra.bridge.ContributionId(
                "contribution", "echo"), registration.registration().id());
            assertEquals("echo:request", services.directory.current().routes()
                .invoke(services.context(), registration.kind(),
                    registration.registration().id(),
                    registration.registration().registrationIdentity(), "request")
                .block());

            unit.closeAdmission();
            assertThrows(com.sstlfsj.fibra.bridge.ContributionUnavailableException.class,
                () -> services.directory.current().routes()
                    .invoke(services.context(), registration.kind(),
                        registration.registration().id(),
                        registration.registration().registrationIdentity(), "late")
                    .block());
            unit.drainAsync("drain", java.time.Instant.now().plusSeconds(5)).block();
            unit.stopAsync("stop", java.time.Instant.now().plusSeconds(5)).block();
            generation.retireAsync().block();
            driver.closeAsync().block();
        } finally {
            fixture.ContributionObserver.callback = null;
        }
    }

    @Test
    void failedStartRetainsScopeAndClassSpaceUntilFailedCleanupCanBeRetried()
        throws Exception {
        var facet = facet("failed-start",
            "entrypoint: fixture.ContributionEntrypoint\n",
            fixture.ContributionEntrypoint.class);
        var startFailure = new IllegalStateException("injected start failure");
        fixture.ContributionObserver.callback = ignored -> { throw startFailure; };
        var closes = new ArrayList<PluginClassLoader>();
        try (var services = new LiveHostServices(true)) {
            var driver = new JavaRuntimeDriver(services, List.of(),
                getClass().getClassLoader(), List.of("java.",
                    "com.sstlfsj.fibra.", "reactor.",
                    "org.reactivestreams.", "org.slf4j.",
                    "fixture.ContributionObserver"),
                loader -> { closes.add(loader); loader.close(); });
            var candidate = driver.createCandidate(slice(
                new PluginFacetSource(facet, List.of())));
            candidate.prepareAsync().block();
            var generation = candidate.seal(CompiledRuntimeSlice.of(
                candidate.preparedPlan(),
                List.of(new ExecutionUnitKey("failed-start"))));
            var unit = generation.units().get(
                new ExecutionUnitKey("failed-start"));

            var observation = unit.reconcileAsync("start").block();

            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.FAILED,
                observation.aggregateState());
            assertEquals(startFailure.toString(),
                observation.executions().getFirst().failure().message());
            assertEquals(1, services.scopeCloseAttempts.get());
            assertEquals(1, startFailure.getSuppressed().length);
            assertEquals("injected scope close failure",
                startFailure.getSuppressed()[0].getMessage());
            assertTrue(closes.isEmpty(),
                "failed cleanup must retain the unit class-space lease");
            assertTrue(services.reconcileRequests.isEmpty(),
                "a unit that never became active must not enter an automatic replacement loop");

            unit.closeAdmission();
            unit.drainAsync("drain", java.time.Instant.now().plusSeconds(5))
                .block();
            unit.stopAsync("stop", java.time.Instant.now().plusSeconds(5))
                .block();
            generation.retireAsync().block();

            assertEquals(2, services.scopeCloseAttempts.get());
            assertEquals(1, closes.size());
            driver.closeAsync().block();
        } finally {
            fixture.ContributionObserver.callback = null;
        }
    }

    @Test
    void realScopeCleanupFailureRetainsTheUnitClassSpaceLease()
        throws Exception {
        var facet = facet("cleanup-failure",
            "entrypoint: fixture.ContributionEntrypoint\n",
            fixture.ContributionEntrypoint.class);
        fixture.ContributionObserver.start = context ->
            context.effects().add(() -> reactor.core.publisher.Mono.error(
                new IllegalStateException("real cleanup failure")));
        var closes = new ArrayList<PluginClassLoader>();
        try (var services = new LiveHostServices()) {
            var driver = new JavaRuntimeDriver(services, List.of(),
                getClass().getClassLoader(), List.of("java.",
                    "com.sstlfsj.fibra.", "reactor.",
                    "org.reactivestreams.", "org.slf4j.",
                    "fixture.ContributionObserver"),
                loader -> { closes.add(loader); loader.close(); });
            var candidate = driver.createCandidate(slice(
                new PluginFacetSource(facet, List.of())));
            candidate.prepareAsync().block();
            var generation = candidate.seal(CompiledRuntimeSlice.of(
                candidate.preparedPlan(),
                List.of(new ExecutionUnitKey("cleanup-failure"))));
            var unit = generation.units().get(
                new ExecutionUnitKey("cleanup-failure"));

            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.ACTIVE,
                unit.reconcileAsync("start").block().aggregateState());
            unit.closeAdmission();
            unit.drainAsync("drain", java.time.Instant.now().plusSeconds(5))
                .block();

            var failure = assertThrows(IllegalStateException.class,
                () -> unit.stopAsync("stop",
                    java.time.Instant.now().plusSeconds(5)).block());

            assertTrue(failure.getMessage().contains(
                "runtime scope cleanup failed"));
            assertTrue(failure.getMessage().contains("real cleanup failure"));
            assertTrue(closes.isEmpty(),
                "scope cleanup failure must retain the unit class-space lease");
        } finally {
            fixture.ContributionObserver.start = null;
            fixture.ContributionObserver.callback = null;
        }
    }

    @Test
    void staticLeaseRetriesOnlyDependenciesWhoseReleaseFailed() throws Exception {
        var firstBase = facet("lease-base-first",
            "entrypoint: fixture.SampleEntrypoint\n",
            fixture.SampleEntrypoint.class);
        var secondBase = facet("lease-base-second",
            "entrypoint: fixture.SampleEntrypoint\n",
            fixture.SampleEntrypoint.class, "c".repeat(64), "d".repeat(64));
        var app = facet("lease-app",
            "entrypoint: fixture.SampleEntrypoint\n",
            fixture.SampleEntrypoint.class);
        var firstBaseSource = new PluginFacetSource(firstBase, List.of());
        var secondBaseSource = new PluginFacetSource(secondBase, List.of());
        var appSource = new PluginFacetSource(app, List.of(
            new ResolvedFacetDependency(firstBase.pluginId(),
                firstBase.packageRevision(), firstBase.facet().facetId(),
                firstBase.artifactId()),
            new ResolvedFacetDependency(secondBase.pluginId(),
                secondBase.packageRevision(), secondBase.facet().facetId(),
                secondBase.artifactId())));
        var closeAttempts = new AtomicInteger();
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of(),
            getClass().getClassLoader(), List.of("java.",
                "com.sstlfsj.fibra.", "reactor."), loader -> {
                    if (closeAttempts.incrementAndGet() == 3) {
                        throw new java.io.IOException(
                            "injected dependency close failure");
                    }
                    loader.close();
                });
        var candidate = driver.createCandidate(singleEntrySlice(appSource,
            firstBaseSource, secondBaseSource, appSource));
        candidate.prepareAsync().block();
        var lease = preparedLease(candidate, new ExecutionUnitKey("lease-app"));

        assertThrows(JavaRuntimeException.class, lease::close);
        lease.close();

        assertEquals(4, closeAttempts.get(),
            "retry must skip the parent and the dependency already released");
        candidate.closeAsync().block();
        driver.closeAsync().block();
    }

    @Test
    void managedControlTargetsTheGeneratedRootInstanceAndRejectsAChild() {
        var root = new AtomicReference<PluginInstance<?>>();
        var child = new AtomicReference<PluginInstance<?>>();
        var childDefinition = PluginDefinition.builder("child", Void.class,
            () -> (context, ignored) -> reactor.core.publisher.Mono.empty())
            .require(ManagedPluginControl.KEY).build();
        var rootDefinition = PluginDefinition.builder("root", Void.class,
            () -> (context, ignored) -> {
                root.set(context.plugins().current().orElseThrow());
                context.plugins().requestDisable();
                var mounted = context.plugins().mount("child-instance",
                    childDefinition.prepare(null));
                child.set(mounted);
                return mounted.settled().then();
            }).require(ManagedPluginControl.KEY).build();
        var builtIn = builtIn(rootDefinition);

        try (var services = new LiveHostServices()) {
            var driver = new JavaRuntimeDriver(services, List.of(builtIn));
            var sealed = sealedBuiltIn(driver, builtIn, new DesiredInputGraph(
                List.of(builtInEntry("unit-root", "root"))),
                Map.of(new ExecutionUnitKey("unit-root"), List.of()));
            var unit = sealed.generation().units().get(
                new ExecutionUnitKey("unit-root"));
            var active = unit.reconcileAsync("start-root")
                .block(java.time.Duration.ofSeconds(2));
            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.ACTIVE,
                active.aggregateState());

            var request = services.disableRequest.get();
            assertEquals(JavaRuntimeProvider.RUNTIME_ID, request.fence().runtimeId());
            assertEquals(new ExecutionUnitKey("unit-root"), request.fence().unitKey());
            assertEquals(active.executions().getFirst().unitTargetRevision(),
                request.fence().unitTargetRevision());
            assertEquals(active.executions().getFirst().runtimeInstanceId(),
                request.fence().runtimeInstanceId());
            assertEquals("unit-root", root.get().id());
            assertNotEquals(root.get().id(), request.fence().runtimeInstanceId());
            assertEquals("java-managed-plugin-control", request.reason());
            assertThrows(FibraException.class,
                () -> child.get().context().plugins().requestDisable());
            assertEquals(request, services.disableRequest.get());

            stop(unit, "root");
            sealed.generation().retireAsync().block();
            driver.closeAsync().block();
        }
    }

    @Test
    void retainedConsumerReportsPendingWhenItsDynamicProviderIsRevoked() {
        var business = ServiceKey.of("java-test-business", String.class);
        var registrars = new ArrayList<Object>();
        var controls = new ArrayList<Object>();
        var consumed = new AtomicReference<String>();
        var provider = PluginDefinition.builder("provider", Void.class,
            () -> (context, ignored) -> {
                registrars.add(context.services().require(
                    ContributionServices.REGISTRAR));
                controls.add(context.services().require(ManagedPluginControl.KEY));
                context.services().provide(business, "shared-business");
                return reactor.core.publisher.Mono.empty();
            }).require(ContributionServices.REGISTRAR)
            .require(ManagedPluginControl.KEY).provide(business).build();
        var consumer = PluginDefinition.builder("consumer", Void.class,
            () -> (context, ignored) -> {
                registrars.add(context.services().require(
                    ContributionServices.REGISTRAR));
                controls.add(context.services().require(ManagedPluginControl.KEY));
                consumed.set(context.services().require(business));
                return reactor.core.publisher.Mono.empty();
            }).require(ContributionServices.REGISTRAR)
            .require(ManagedPluginControl.KEY).require(business).build();
        var builtIn = builtIn(provider, consumer);
        var providerKey = new ExecutionUnitKey("provider-unit");
        var consumerKey = new ExecutionUnitKey("consumer-unit");
        var providerGraph = new DesiredInputGraph(List.of(
            builtInEntryWithSharedControlRealms(providerKey.value(),
                "provider")));
        var consumerGraph = new DesiredInputGraph(List.of(
            builtInEntryWithSharedControlRealms(consumerKey.value(),
                "consumer")));

        try (var services = new LiveHostServices()) {
            var driver = new JavaRuntimeDriver(services, List.of(builtIn));
            var providerGeneration = sealedBuiltIn(driver, builtIn,
                providerGraph, Map.of(providerKey, List.of())).generation();
            var consumerGeneration = sealedBuiltIn(driver, builtIn,
                consumerGraph, Map.of(consumerKey, List.of())).generation();
            var providerUnit = providerGeneration.units().get(providerKey);
            var consumerUnit = consumerGeneration.units().get(consumerKey);

            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.ACTIVE,
                providerUnit.reconcileAsync("start-provider").block()
                    .aggregateState());
            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.ACTIVE,
                consumerUnit.reconcileAsync("start-consumer").block()
                    .aggregateState());
            assertEquals("shared-business", consumed.get());
            assertEquals(2, registrars.size());
            assertEquals(2, controls.size());
            assertNotSame(registrars.get(0), registrars.get(1));
            assertNotSame(controls.get(0), controls.get(1));

            var consumerIdentity = consumerUnit.snapshot().executions()
                .getFirst().runtimeInstanceId();
            stop(providerUnit, "provider");
            providerGeneration.retireAsync().block();
            var pending = consumerUnit.snapshot();
            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.PENDING,
                pending.aggregateState());
            assertEquals(consumerIdentity,
                pending.executions().getFirst().runtimeInstanceId());
            assertTrue(services.observationRefreshes.stream().anyMatch(fence ->
                fence.unitKey().equals(consumerKey)
                    && fence.runtimeInstanceId().equals(consumerIdentity)));

            services.observationRefreshes.clear();
            var restoredProviderGeneration = sealedBuiltIn(driver, builtIn,
                providerGraph, Map.of(providerKey, List.of())).generation();
            var restoredProvider = restoredProviderGeneration.units()
                .get(providerKey);
            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.ACTIVE,
                restoredProvider.reconcileAsync("restore-provider").block()
                    .aggregateState());
            var recovered = consumerUnit.snapshot();
            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.ACTIVE,
                recovered.aggregateState());
            assertEquals(consumerIdentity,
                recovered.executions().getFirst().runtimeInstanceId());
            assertTrue(services.observationRefreshes.stream().anyMatch(fence ->
                fence.unitKey().equals(consumerKey)
                    && fence.runtimeInstanceId().equals(consumerIdentity)));

            stop(restoredProvider, "restored-provider");
            restoredProviderGeneration.retireAsync().block();
            stop(consumerUnit, "consumer");
            consumerGeneration.retireAsync().block();

            services.observationRefreshes.clear();
            var postConsumerProviderGeneration = sealedBuiltIn(driver,
                builtIn, providerGraph, Map.of(providerKey, List.of()))
                .generation();
            var postConsumerProvider = postConsumerProviderGeneration.units()
                .get(providerKey);
            postConsumerProvider.reconcileAsync("post-consumer-provider")
                .block();
            assertTrue(services.observationRefreshes.stream().noneMatch(fence ->
                fence.unitKey().equals(consumerKey)));
            stop(postConsumerProvider, "post-consumer-provider");
            postConsumerProviderGeneration.retireAsync().block();
            driver.closeAsync().block();
        }
    }

    @Test
    void desiredRealmAndInterceptPoliciesAreFrozenPerUnit() {
        var business = ServiceKey.of("java-policy-business", String.class);
        var matchingValue = new AtomicReference<String>();
        var matchingIntercept = new AtomicReference<Object>();
        var isolatedStarts = new java.util.concurrent.atomic.AtomicInteger();
        var provider = PluginDefinition.builder("realm-provider", Void.class,
            () -> (context, ignored) -> {
                context.services().provide(business, "tenant-value");
                return reactor.core.publisher.Mono.empty();
            }).provide(business).build();
        var matching = PluginDefinition.builder("realm-matching", Void.class,
            () -> (context, ignored) -> {
                matchingValue.set(context.services().require(business));
                matchingIntercept.set(context.intercept(business));
                return reactor.core.publisher.Mono.empty();
            }).require(business).build();
        var isolated = PluginDefinition.builder("realm-isolated", Void.class,
            () -> (context, ignored) -> {
                isolatedStarts.incrementAndGet();
                return reactor.core.publisher.Mono.empty();
            }).require(business).build();
        var builtIn = builtIn(provider, matching, isolated);
        var providerKey = new ExecutionUnitKey("realm-provider-unit");
        var matchingKey = new ExecutionUnitKey("realm-matching-unit");
        var isolatedKey = new ExecutionUnitKey("realm-isolated-unit");
        var graph = new DesiredInputGraph(List.of(
            builtInEntry(providerKey.value(), "realm-provider", business,
                "tenant-a", null),
            builtInEntry(matchingKey.value(), "realm-matching", business,
                "tenant-a", Map.of("trace", "configured")),
            builtInEntry(isolatedKey.value(), "realm-isolated", business,
                "tenant-b", null)));

        try (var services = new LiveHostServices()) {
            var driver = new JavaRuntimeDriver(services, List.of(builtIn));
            var sealed = sealedBuiltIn(driver, builtIn, graph, Map.of(
                providerKey, List.of(), matchingKey, List.of(),
                isolatedKey, List.of()));
            var providerUnit = sealed.generation().units().get(providerKey);
            var matchingUnit = sealed.generation().units().get(matchingKey);
            var isolatedUnit = sealed.generation().units().get(isolatedKey);

            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.ACTIVE,
                providerUnit.reconcileAsync("start-provider").block()
                    .aggregateState());
            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.ACTIVE,
                matchingUnit.reconcileAsync("start-matching").block()
                    .aggregateState());
            assertEquals("tenant-value", matchingValue.get());
            assertEquals(Map.of("trace", "configured"),
                matchingIntercept.get());
            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.PENDING,
                isolatedUnit.reconcileAsync("start-isolated").block()
                    .aggregateState());
            assertEquals(0, isolatedStarts.get());

            stop(isolatedUnit, "isolated");
            stop(matchingUnit, "matching");
            stop(providerUnit, "provider");
            sealed.generation().retireAsync().block();
            driver.closeAsync().block();
        }
    }

    private RuntimeTargetSlice slice(PluginFacetSource... sources) {
        var facets = List.of(sources);
        var entries = facets.stream().map(PluginFacetSource::facet).map(facet ->
            DesiredInputEntry.builder(facet.artifactId().value(), new PluginDefinitionRef(
                facet.pluginId().value(), facet.facet().facetId().value(), "sample")).build()).toList();
        var target = DeploymentTarget.of(1,
            facets.stream().map(PluginFacetSource::facet).map(facet ->
                new PluginSelection(facet.pluginId(), facet.packageRevision(), true))
                .toList(),
            new DesiredInputGraph(entries), ConfigContextSnapshot.empty());
        var dependencies = new java.util.LinkedHashMap<ExecutionUnitKey, List<ExecutionUnitKey>>();
        for (var source : facets) {
            dependencies.put(new ExecutionUnitKey(source.facet().artifactId().value()),
                source.dependencies().stream().map(dependency ->
                    new ExecutionUnitKey(dependency.artifactId().value())).toList());
        }
        return RuntimeTargetSlice.builder(JavaRuntimeProvider.RUNTIME_ID, target)
            .desired(DesiredEvaluation.evaluate(target.desiredGraph(),
                target.configContext()))
            .capabilities(HostCapabilitySnapshot.empty())
            .facets(facets)
            .affectedEntryIds(Set.copyOf(target.desiredGraph().plugins().keySet()))
            .unitDependencies(dependencies)
            .build();
    }

    private RuntimeTargetSlice singleEntrySlice(PluginFacetSource unitSource,
                                                PluginFacetSource... sources) {
        var entryId = unitSource.facet().artifactId().value();
        var entry = DesiredInputEntry.builder(entryId,
            new PluginDefinitionRef(unitSource.facet().pluginId().value(),
                unitSource.facet().facet().facetId().value(), "sample"))
            .build();
        var target = DeploymentTarget.of(1,
            java.util.Arrays.stream(sources).map(PluginFacetSource::facet)
                .map(facet -> new PluginSelection(facet.pluginId(),
                    facet.packageRevision(), true)).toList(),
            new DesiredInputGraph(List.of(entry)),
            ConfigContextSnapshot.empty());
        return RuntimeTargetSlice.builder(JavaRuntimeProvider.RUNTIME_ID,
                target)
            .desired(DesiredEvaluation.evaluate(target.desiredGraph(),
                target.configContext()))
            .capabilities(HostCapabilitySnapshot.empty())
            .facets(List.of(sources)).affectedEntryIds(Set.of(entryId))
            .unitDependencies(Map.of(new ExecutionUnitKey(entryId), List.of()))
            .build();
    }

    private static AutoCloseable preparedLease(
        com.sstlfsj.fibra.engine.RuntimeCandidate candidate,
        ExecutionUnitKey key) throws Exception {
        var preparedField = candidate.getClass().getDeclaredField("prepared");
        preparedField.setAccessible(true);
        var prepared = (Map<?, ?>) preparedField.get(candidate);
        var unit = prepared.get(key);
        var leaseField = unit.getClass().getDeclaredField("lease");
        leaseField.setAccessible(true);
        return (AutoCloseable) leaseField.get(unit);
    }

    private static JavaBuiltInPackage builtIn(
        PluginDefinition<Void>... definitions) {
        var facetId = new FacetId("host");
        var facet = com.sstlfsj.fibra.engine.BuiltInFacet.builder(facetId,
                JavaRuntimeProvider.RUNTIME_ID, new ExecutionTarget("host"))
            .definitionIds(java.util.Arrays.stream(definitions)
                .map(PluginDefinition::name).collect(java.util.stream.Collectors.toSet()))
            .build();
        var metadata = com.sstlfsj.fibra.engine.BuiltInPluginPackage.builder()
            .pluginId(new PluginId("java-test-built-in")).version("1")
            .packageDigest("d".repeat(64)).facets(List.of(facet)).build();
        var entries = new ArrayList<JavaDefinitionEntry<?>>();
        java.util.Arrays.stream(definitions).forEach(definition -> entries.add(
            new JavaDefinitionEntry<>(definition, ignored -> null)));
        return new JavaBuiltInPackage(metadata, Map.of(facetId, entries));
    }

    private static DesiredInputEntry builtInEntry(String entryId,
                                                  String definitionId) {
        return DesiredInputEntry.builder(entryId, new PluginDefinitionRef(
            "java-test-built-in", "host", definitionId)).build();
    }

    private static DesiredInputEntry builtInEntryWithSharedControlRealms(
        String entryId, String definitionId) {
        return DesiredInputEntry.builder(entryId, new PluginDefinitionRef(
                "java-test-built-in", "host", definitionId))
            .realms(Map.of(
                ContributionServices.REGISTRAR.name(),
                com.sstlfsj.fibra.value.LiteralValue.of("shared-control"),
                ManagedPluginControl.KEY.name(),
                com.sstlfsj.fibra.value.LiteralValue.of("shared-control")))
            .build();
    }

    private static DesiredInputEntry builtInEntry(
        String entryId, String definitionId, ServiceKey<?> service,
        String realm, Object intercept) {
        var builder = DesiredInputEntry.builder(entryId, new PluginDefinitionRef(
                "java-test-built-in", "host", definitionId))
            .realms(Map.of(service.name(),
                com.sstlfsj.fibra.value.LiteralValue.of(realm)));
        if (intercept != null) {
            builder.intercepts(Map.of(service.name(),
                com.sstlfsj.fibra.value.LiteralValue.of(intercept)));
        }
        return builder.build();
    }

    private static SealedBuiltIn sealedBuiltIn(JavaRuntimeDriver driver,
                                               JavaBuiltInPackage builtIn,
                                               DesiredInputGraph graph,
                                               Map<ExecutionUnitKey,
                                                   List<ExecutionUnitKey>> dependencies) {
        var target = DeploymentTarget.of(1, List.of(), graph,
            ConfigContextSnapshot.empty());
        var slice = RuntimeTargetSlice.builder(JavaRuntimeProvider.RUNTIME_ID,
                target)
            .desired(DesiredEvaluation.evaluate(graph, target.configContext()))
            .capabilities(HostCapabilitySnapshot.empty())
            .facets(List.of()).builtInPackages(List.of(builtIn.metadata()))
            .affectedEntryIds(Set.copyOf(graph.plugins().keySet()))
            .unitDependencies(dependencies).build();
        var candidate = driver.createCandidate(slice);
        candidate.prepareAsync().block();
        var dependencyFirst = List.copyOf(dependencies.keySet());
        var generation = candidate.seal(CompiledRuntimeSlice.of(
            candidate.preparedPlan(), dependencyFirst));
        return new SealedBuiltIn(generation);
    }

    private static void stop(com.sstlfsj.fibra.engine.RuntimeUnitGeneration unit,
                             String suffix) {
        unit.closeAdmission();
        unit.drainAsync("drain-" + suffix,
            java.time.Instant.now().plusSeconds(5)).block();
        unit.stopAsync("stop-" + suffix,
            java.time.Instant.now().plusSeconds(5)).block();
    }

    private record SealedBuiltIn(
        com.sstlfsj.fibra.engine.PreparedRuntimeGeneration generation) { }

    private static final class NoopHostServices implements RuntimeHostServices {
        @Override public String hostInstanceId() { return "java-test-host"; }
        @Override public com.sstlfsj.fibra.ScopeView scope() { return null; }
        @Override public reactor.core.publisher.Mono<Void> releaseScope(
            com.sstlfsj.fibra.Scope scope) {
            return reactor.core.publisher.Mono.defer(scope::closeAsync);
        }
        @Override public com.sstlfsj.fibra.bridge.ContributionKindRegistry contributionKinds() {
            return com.sstlfsj.fibra.bridge.ContributionKindRegistry.empty();
        }
        @Override public com.sstlfsj.fibra.bridge.ContributionAdmission openContributionAdmission(com.sstlfsj.fibra.engine.ExecutionUnitKey key) {
            throw new UnsupportedOperationException();
        }
        @Override public com.sstlfsj.fibra.engine.RemoteContributionInvoker remoteContributions() {
            throw new UnsupportedOperationException();
        }
        @Override public String nextIdentity(String namespace) { return namespace + ":test"; }
        @Override public void requestReconcile(
            Set<com.sstlfsj.fibra.engine.RuntimeUnitFence> fences,
            String reason) { }
        @Override public void requestObservationRefresh(
            com.sstlfsj.fibra.engine.RuntimeUnitFence fence) { }
        @Override public void requestDisable(
            com.sstlfsj.fibra.engine.RuntimeUnitDisableRequest request) { }
        @Override public void requestRecompile(com.sstlfsj.fibra.engine.RuntimeRecompileReason reason) { }
    }

    private static final class LiveHostServices
        implements RuntimeHostServices, AutoCloseable {
        private final com.sstlfsj.fibra.runtime.FibraRuntime runtime =
            com.sstlfsj.fibra.runtime.FibraRuntime.create();
        private final com.sstlfsj.fibra.runtime.RuntimeDomain domain =
            runtime.openDomain("java-driver-test");
        private final com.sstlfsj.fibra.bridge.ContributionDirectory directory =
            new com.sstlfsj.fibra.bridge.ContributionDirectory();
        private final AtomicReference<RuntimeUnitDisableRequest> disableRequest =
            new AtomicReference<>();
        private final List<com.sstlfsj.fibra.engine.RuntimeUnitFence>
            observationRefreshes = new CopyOnWriteArrayList<>();
        private final List<Set<com.sstlfsj.fibra.engine.RuntimeUnitFence>>
            reconcileRequests = new CopyOnWriteArrayList<>();
        private final boolean failFirstChildClose;
        private final AtomicInteger scopeCloseAttempts = new AtomicInteger();
        private long identities;

        private LiveHostServices() {
            this(false);
        }

        private LiveHostServices(boolean failFirstChildClose) {
            this.failFirstChildClose = failFirstChildClose;
        }

        @Override public String hostInstanceId() { return "java-live-host"; }
        @Override public com.sstlfsj.fibra.ScopeView scope() {
            var root = domain.rootScope().context().scope();
            if (!failFirstChildClose) return root;
            return new com.sstlfsj.fibra.ScopeView() {
                @Override public String name() { return root.name(); }
                @Override public com.sstlfsj.fibra.Context context() {
                    return root.context();
                }
                @Override public com.sstlfsj.fibra.Scope openChild(String name) {
                    return new FailOnceCloseScope(root.openChild(name),
                        scopeCloseAttempts);
                }
                @Override public boolean isClosed() { return root.isClosed(); }
            };
        }
        com.sstlfsj.fibra.Context context() {
            return domain.rootScope().context();
        }
        @Override public reactor.core.publisher.Mono<Void> releaseScope(
            com.sstlfsj.fibra.Scope scope) {
            var owned = scope instanceof FailOnceCloseScope wrapped
                ? wrapped.delegate : scope;
            return reactor.core.publisher.Mono.defer(scope::closeAsync)
                .then(reactor.core.publisher.Mono.fromRunnable(() -> {
                    var failures = domain.cleanupFailures(owned);
                    if (!failures.isEmpty()) {
                        throw new IllegalStateException(
                            "runtime scope cleanup failed: " + failures);
                    }
                }));
        }
        @Override public com.sstlfsj.fibra.bridge.ContributionKindRegistry contributionKinds() {
            return com.sstlfsj.fibra.bridge.ContributionKindRegistry.empty();
        }
        @Override public com.sstlfsj.fibra.bridge.ContributionAdmission openContributionAdmission(com.sstlfsj.fibra.engine.ExecutionUnitKey key) {
            return directory.openAdmission(key.value());
        }
        @Override public com.sstlfsj.fibra.engine.RemoteContributionInvoker remoteContributions() {
            throw new UnsupportedOperationException();
        }
        @Override public String nextIdentity(String namespace) {
            return namespace + ':' + ++identities;
        }
        @Override public void requestReconcile(
            Set<com.sstlfsj.fibra.engine.RuntimeUnitFence> fences,
            String reason) {
            reconcileRequests.add(Set.copyOf(fences));
        }
        @Override public void requestObservationRefresh(
            com.sstlfsj.fibra.engine.RuntimeUnitFence fence) {
            observationRefreshes.add(fence);
        }
        @Override public void requestDisable(
            com.sstlfsj.fibra.engine.RuntimeUnitDisableRequest request) {
            disableRequest.set(request);
        }
        @Override public void requestRecompile(
            com.sstlfsj.fibra.engine.RuntimeRecompileReason reason) { }
        @Override public void close() {
            directory.close();
            runtime.close();
        }
    }

    private static final class FailOnceCloseScope
        implements com.sstlfsj.fibra.Scope {
        private final com.sstlfsj.fibra.Scope delegate;
        private final AtomicInteger attempts;

        private FailOnceCloseScope(com.sstlfsj.fibra.Scope delegate,
                                   AtomicInteger attempts) {
            this.delegate = delegate;
            this.attempts = attempts;
        }

        @Override public String name() { return delegate.name(); }
        @Override public com.sstlfsj.fibra.Context context() {
            return delegate.context();
        }
        @Override public com.sstlfsj.fibra.Scope openChild(String name) {
            return delegate.openChild(name);
        }
        @Override public boolean isClosed() { return delegate.isClosed(); }
        @Override public reactor.core.publisher.Mono<Void> closeAsync() {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("injected scope close failure");
            }
            return delegate.closeAsync();
        }
    }

    private ManagedFacet facet(String artifactId, String descriptor,
                               Class<?> entrypoint) throws Exception {
        return facet(artifactId, descriptor, entrypoint, "a".repeat(64),
            "b".repeat(64));
    }

    private ManagedFacet facet(String artifactId, String descriptor,
                               Class<?> entrypoint, String packageRevision,
                               String payloadDigest) throws Exception {
        var payload = work.resolve(artifactId + ".jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(payload))) {
            entry(jar, JavaFacetDescriptorReader.LOCATION, descriptor);
            if (entrypoint != null) {
                copyClass(jar, entrypoint);
            }
        }
        return new ManagedFacet(new ArtifactId(artifactId), new PluginId(artifactId),
            packageRevision, new PluginFacet(new FacetId("host"), FacetRole.HOST,
                JavaRuntimeProvider.RUNTIME_ID, new ExecutionTarget("host"), payload,
                payloadDigest, List.of(), List.of()));
    }

    private static void copyClass(JarOutputStream jar, Class<?> type) throws Exception {
        var name = type.getName().replace('.', '/') + ".class";
        jar.putNextEntry(new JarEntry(name));
        try (var input = type.getResourceAsStream('/' + name)) {
            jar.write(input.readAllBytes());
        }
        jar.closeEntry();
    }

    private static void entry(JarOutputStream jar, String name, String value)
        throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(value.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }
}
