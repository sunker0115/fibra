package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.ScopeView;
import com.sstlfsj.fibra.bridge.ContributionCodec;
import com.sstlfsj.fibra.bridge.ContributionDirectory;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ExecutionTarget;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.FacetRole;
import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.PluginFacet;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.RuntimeId;
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
import com.sstlfsj.fibra.engine.RuntimeHostServices;
import com.sstlfsj.fibra.engine.RuntimeRecompileReason;
import com.sstlfsj.fibra.engine.RuntimeUnitGeneration;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeRuntimeDriverTest {
    private static final String REVISION = "a".repeat(64);
    private static final ContributionCodec<String, String, String> TEST_CODEC =
        new ContributionCodec<>() {
            @Override public int schemaVersion() { return 1; }
            @Override public String decodeDescriptor(LiteralValue value) {
                return string(value);
            }
            @Override public LiteralValue encodeInput(String value) {
                return LiteralValue.of(value);
            }
            @Override public String decodeInput(LiteralValue value) {
                return string(value);
            }
            @Override public LiteralValue encodeOutput(String value) {
                return LiteralValue.of(value);
            }
            @Override public String decodeOutput(LiteralValue value) {
                return string(value);
            }
            private String string(LiteralValue value) {
                return ((LiteralValue.StringValue) value).value();
            }
        };
    private static final ContributionKind<String, String, String> TEST_KIND =
        ContributionKind.remote("test.delayed", String.class, String.class,
            String.class, TEST_CODEC);

    @Test
    void prepareBuildsAnEntryKeyedPlanWithoutStartingNode(@TempDir Path work)
        throws Exception {
        var marker = work.resolve("node-started");
        var dependency = facet(work, "dependency", "dependency-artifact", marker);
        var dependent = facet(work, "dependent", "dependent-artifact", marker);
        var driver = new NodeRuntimeProvider(NodeRuntimeOptions.defaults(
            Path.of(System.getProperty("fibra.test.node", "node")),
            work.resolve("sessions"))).create(new Services());
        var candidate = driver.createCandidate(slice(dependent, "node-entry",
            List.of(new com.sstlfsj.fibra.engine.ResolvedFacetDependency(
                dependency.pluginId(), dependency.packageRevision(),
                dependency.facet().facetId(), dependency.artifactId())),
            List.of(new ExecutionUnitKey("dependency-entry"))));

        assertFalse(Files.exists(marker));
        assertThrows(IllegalStateException.class, candidate::preparedPlan);

        candidate.prepareAsync().block();

        var plan = candidate.preparedPlan();
        var key = new ExecutionUnitKey("node-entry");
        assertEquals(Set.of(key), plan.units().keySet());
        assertEquals(List.of(new ExecutionUnitKey("dependency-entry")),
            plan.units().get(key).dependencies());
        assertFalse(Files.exists(marker), "compile-only preparation must not start Node");

        var generation = candidate.seal(CompiledRuntimeSlice.of(plan, List.of(key)));
        assertTrue(generation.units().get(key).snapshot().executions().isEmpty(),
            "seal 前不得产生可见执行实例");
        generation.abortAsync().block();
        assertFalse(Files.exists(marker), "sealed inert generation must not start Node");
        assertEquals(0, payloadCount(driver));
        driver.closeAsync().block();
    }

    @Test
    void twoEntriesSharePayloadUntilTheLastUnitStops(@TempDir Path work)
        throws Exception {
        var pidDirectory = Files.createDirectory(work.resolve("pids"));
        var facet = lifecycleFacet(work, pidDirectory, false);
        try (var services = new Services()) {
            var driver = new NodeRuntimeProvider(options(work))
                .create(services);
            var candidate = driver.createCandidate(slice(facet,
                List.of("first-entry", "second-entry")));

            candidate.prepareAsync().block();
            assertEquals(1, payloadCount(driver));
            assertEquals(2, payloadReferences(driver));
            var plan = candidate.preparedPlan();
            var generation = candidate.seal(CompiledRuntimeSlice.of(plan,
                List.of(new ExecutionUnitKey("first-entry"),
                    new ExecutionUnitKey("second-entry"))));
            assertTrue(driver.snapshot().units().isEmpty());
            assertEquals(0, pidCount(pidDirectory));

            var first = generation.units().get(new ExecutionUnitKey("first-entry"));
            var second = generation.units().get(new ExecutionUnitKey("second-entry"));
            first.reconcileAsync("start-first").block(Duration.ofSeconds(5));
            second.reconcileAsync("start-second").block(Duration.ofSeconds(5));
            assertEquals(2, driver.snapshot().units().size());

            stop(first, "first");
            assertEquals(1, payloadCount(driver));
            assertEquals(1, payloadReferences(driver));
            assertEquals(1, driver.snapshot().units().size());

            stop(second, "second");
            assertEquals(0, payloadCount(driver));
            assertTrue(driver.snapshot().units().isEmpty());
            generation.retireAsync().block(Duration.ofSeconds(5));
            driver.closeAsync().block(Duration.ofSeconds(5));
        }
    }

    @Test
    void dependencyStaticIdentityParticipatesInConsumerPayloadReuse(@TempDir Path work)
        throws Exception {
        var marker = work.resolve("node-started");
        var dependencyV1 = facet(work.resolve("v1"), "dependency",
            "dependency-artifact-v1", marker, "1".repeat(64), "2".repeat(64));
        var dependencyV2 = facet(work.resolve("v2"), "dependency",
            "dependency-artifact-v2", marker, "3".repeat(64), "4".repeat(64));
        var dependent = facet(work, "dependent", "dependent-artifact", marker);
        try (var services = new Services()) {
            var driver = new NodeRuntimeProvider(options(work)).create(services);
            var first = driver.createCandidate(dependencySlice(dependent, dependencyV1));
            first.prepareAsync().block();
            assertEquals(2, payloadCount(driver));
            assertEquals(2, totalPayloadReferences(driver));

            var shared = driver.createCandidate(dependencySlice(dependent, dependencyV1));
            shared.prepareAsync().block();
            assertEquals(2, payloadCount(driver),
                "unchanged dependency identity must share both payload generations");
            assertEquals(4, totalPayloadReferences(driver));

            var changed = driver.createCandidate(dependencySlice(dependent, dependencyV2));
            changed.prepareAsync().block();
            assertEquals(4, payloadCount(driver),
                "dependency identity change must create a new consumer payload generation");

            changed.closeAsync().block();
            assertEquals(2, payloadCount(driver));
            shared.closeAsync().block();
            assertEquals(2, payloadCount(driver));
            first.closeAsync().block();
            assertEquals(0, payloadCount(driver));
            driver.closeAsync().block();
        }
    }

    @Test
    void realLifecycleFencesLateOperationsAndCleansAStartFailure(@TempDir Path work)
        throws Exception {
        var successPids = Files.createDirectory(work.resolve("success-pids"));
        try (var services = new Services()) {
            var driver = new NodeRuntimeProvider(options(work))
                .create(services);
            var success = sealedUnit(driver, lifecycleFacet(work, successPids, false),
                "live-entry");

            success.unit.reconcileAsync("start-live").block(Duration.ofSeconds(5));
            assertThrows(RuntimeException.class,
                () -> success.unit.reconcileAsync("late-start").block(Duration.ofSeconds(5)));
            success.unit.closeAdmission();
            success.unit.drainAsync("drain-live", deadline()).block(Duration.ofSeconds(5));
            assertThrows(RuntimeException.class,
                () -> success.unit.drainAsync("late-drain", deadline())
                    .block(Duration.ofSeconds(5)));
            success.unit.stopAsync("stop-live", deadline()).block(Duration.ofSeconds(5));
            assertThrows(RuntimeException.class,
                () -> success.unit.stopAsync("late-stop", deadline())
                    .block(Duration.ofSeconds(5)));
            success.generation.retireAsync().block(Duration.ofSeconds(5));

            var failed = sealedUnit(driver, lifecycleFacet(work,
                Files.createDirectory(work.resolve("failed-pids")), true), "failed-entry");
            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.FAILED,
                failed.unit.reconcileAsync("start-failed").block(Duration.ofSeconds(5))
                    .aggregateState());
            failed.unit.closeAdmission();
            failed.unit.drainAsync("drain-failed", deadline()).block(Duration.ofSeconds(5));
            failed.unit.stopAsync("stop-failed", deadline()).block(Duration.ofSeconds(5));
            failed.generation.retireAsync().block(Duration.ofSeconds(5));

            assertEquals(0, payloadCount(driver));
            assertTrue(driver.snapshot().units().isEmpty());
            try (var sessions = Files.list(work.resolve("sessions"))) {
                assertTrue(sessions.findAny().isEmpty());
            }
            driver.closeAsync().block(Duration.ofSeconds(5));
        }
    }

    @Test
    void failedStartReleasesEachSuccessfulOwnerBeforeStopRetriesTheRemainder(
        @TempDir Path work) throws Exception {
        var pids = Files.createDirectory(work.resolve("failed-start-pids"));
        try (var services = new Services(true)) {
            var driver = new NodeRuntimeProvider(options(work)).create(services);
            var failed = sealedUnit(driver, lifecycleFacet(work, pids, true),
                "failed-start-entry");

            var observation = failed.unit.reconcileAsync("start")
                .block(Duration.ofSeconds(5));
            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.FAILED,
                observation.aggregateState());
            assertEquals(1, services.scopeCloseAttempts.get());
            var sidecarField = failed.unit.getClass().getDeclaredField("sidecar");
            sidecarField.setAccessible(true);
            assertEquals(null, sidecarField.get(failed.unit),
                "failed-start cleanup must release the successfully closed sidecar immediately");
            try (var sessions = Files.list(work.resolve("sessions"))) {
                assertTrue(sessions.findAny().isEmpty(),
                    "failed-start cleanup must remove the released sidecar session");
            }

            failed.unit.closeAdmission();
            failed.unit.drainAsync("drain", deadline()).block(Duration.ofSeconds(5));
            failed.unit.stopAsync("stop", deadline()).block(Duration.ofSeconds(5));
            failed.generation.retireAsync().block(Duration.ofSeconds(5));

            assertEquals(2, services.scopeCloseAttempts.get(),
                "stop must retry only the scope whose failed-start cleanup failed");
            assertEquals(0, payloadCount(driver));
            driver.closeAsync().block(Duration.ofSeconds(5));
        }
    }

    @Test
    void activeSidecarTerminationPublishesFailureAndRequestsReplacement(
        @TempDir Path work) throws Exception {
        try (var services = new Services()) {
            var driver = new NodeRuntimeProvider(options(work)).create(services);
            var sealed = sealedUnit(driver, lifecycleFacet(work,
                Files.createDirectory(work.resolve("termination-pids")), false),
                "terminated-entry");
            var started = sealed.unit.reconcileAsync("start")
                .block(Duration.ofSeconds(5));
            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.ACTIVE,
                started.aggregateState(), () -> "unexpected Node start result: "
                    + started.executions().getFirst().failure());

            var sidecarField = sealed.unit.getClass().getDeclaredField("sidecar");
            sidecarField.setAccessible(true);
            ((NodeSidecar) sidecarField.get(sealed.unit)).close();

            assertTrue(services.reconcileRequested.await(3, TimeUnit.SECONDS));
            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.FAILED,
                sealed.unit.snapshot().aggregateState());
            assertEquals(1, services.reconcileRequests.get());

            sealed.unit.drainAsync("drain", deadline()).block(Duration.ofSeconds(5));
            sealed.unit.stopAsync("stop", deadline()).block(Duration.ofSeconds(5));
            sealed.generation.retireAsync().block(Duration.ofSeconds(5));
            driver.closeAsync().block(Duration.ofSeconds(5));
        }
    }

    @Test
    void sidecarTerminationDuringRegistrationCannotPublishADeadActiveUnit(
        @TempDir Path work) throws Exception {
        try (var services = new Services(false, true)) {
            var driver = new NodeRuntimeProvider(options(work)).create(services);
            var sealed = sealedUnit(driver,
                terminationDuringRegistrationFacet(work), "startup-exit-entry");

            var start = sealed.unit.reconcileAsync("start").toFuture();
            assertTrue(services.registrationStarted.await(3, TimeUnit.SECONDS));
            var sidecarField = sealed.unit.getClass().getDeclaredField("sidecar");
            sidecarField.setAccessible(true);
            var sidecar = (NodeSidecar) sidecarField.get(sealed.unit);
            sidecar.termination().onErrorResume(ignored -> Mono.empty())
                .block(Duration.ofSeconds(5));
            assertTrue(services.admissionClosed.await(3, TimeUnit.SECONDS),
                "termination during startup must close contribution admission");
            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.PENDING,
                assertDoesNotThrow(sealed.unit::snapshot).aggregateState(),
                "startup termination remains private until failStart publishes FAILED");
            services.registrationRelease.tryEmitEmpty();

            var observation = start.get(5, TimeUnit.SECONDS);
            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.FAILED,
                observation.aggregateState());
            assertEquals(0, services.reconcileRequests.get(),
                "a sidecar that never became active must not request replacement");

            sealed.unit.drainAsync("drain", deadline()).block(Duration.ofSeconds(5));
            sealed.unit.stopAsync("stop", deadline()).block(Duration.ofSeconds(5));
            sealed.generation.retireAsync().block(Duration.ofSeconds(5));
            driver.closeAsync().block(Duration.ofSeconds(5));
        }
    }

    @Test
    void managedRangeCleanupFailureRetainsTheSidecarAndPayloadOwner(
        @TempDir Path work) throws Exception {
        try (var services = new Services()) {
            var driver = new NodeRuntimeProvider(options(work)).create(services);
            var sealed = sealedUnit(driver, cleanupFailureFacet(work),
                "cleanup-failure-entry");

            var started = sealed.unit.reconcileAsync("start")
                .block(Duration.ofSeconds(5));
            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.ACTIVE,
                started.aggregateState());
            assertTrue(services.reconcileRequested.await(3, TimeUnit.SECONDS));
            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.FAILED,
                sealed.unit.snapshot().aggregateState());

            sealed.unit.closeAdmission();
            sealed.unit.drainAsync("drain", deadline()).block(Duration.ofSeconds(5));
            var cleanupFailure = assertThrows(NodeRpcException.class,
                () -> sealed.unit.stopAsync("stop", deadline())
                    .block(Duration.ofSeconds(5)));
            assertEquals(NodeRpcPhase.TERMINATE, cleanupFailure.phase());
            assertEquals(1, payloadCount(driver),
                "cleanup failure must retain the payload lease");
            var sidecarField = sealed.unit.getClass().getDeclaredField("sidecar");
            sidecarField.setAccessible(true);
            assertTrue(sidecarField.get(sealed.unit) instanceof NodeSidecar,
                "cleanup failure must retain the sidecar owner");
            var scopeField = sealed.unit.getClass().getDeclaredField("scope");
            scopeField.setAccessible(true);
            assertEquals(null, scopeField.get(sealed.unit),
                "all-settled stop must release the independently successful scope");
        }
    }

    @Test
    void stopCleanupFailurePreservesTheRequestFailureAsSuppressed(
        @TempDir Path work) throws Exception {
        try (var services = new Services()) {
            var driver = new NodeRuntimeProvider(options(work)).create(services);
            var sealed = sealedUnit(driver, stopCleanupFailureFacet(work),
                "stop-cleanup-failure-entry");

            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.ACTIVE,
                sealed.unit.reconcileAsync("start").block(Duration.ofSeconds(5))
                    .aggregateState());
            sealed.unit.closeAdmission();
            sealed.unit.drainAsync("drain", deadline()).block(Duration.ofSeconds(5));

            var cleanupFailure = assertThrows(NodeRpcException.class,
                () -> sealed.unit.stopAsync("stop", deadline())
                    .block(Duration.ofSeconds(5)));
            assertEquals(NodeRpcPhase.TERMINATE, cleanupFailure.phase());
            assertTrue(java.util.Arrays.stream(cleanupFailure.getSuppressed())
                    .anyMatch(failure -> failure instanceof NodeRpcException nodeFailure
                        && nodeFailure.phase() == NodeRpcPhase.REQUEST
                        && "stop rejected".equals(nodeFailure.getMessage())),
                "cleanup failure must retain the preceding stop request failure");
        }
    }

    @Test
    void disableNotificationCarriesTheUnitFenceWithoutRequestingReconcile(
        @TempDir Path work) throws Exception {
        try (var services = new Services()) {
            var driver = new NodeRuntimeProvider(options(work)).create(services);
            var sealed = sealedUnit(driver, disableFacet(work), "disable-entry");
            var active = sealed.unit.reconcileAsync("start-disable")
                .block(Duration.ofSeconds(5));
            assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.ACTIVE,
                active.aggregateState());
            assertTrue(services.disableRequested.await(3, TimeUnit.SECONDS));

            var request = services.disableRequest.get();
            var execution = active.executions().getFirst();
            assertEquals(NodeRuntimeProvider.RUNTIME_ID, request.fence().runtimeId());
            assertEquals(new ExecutionUnitKey("disable-entry"), request.fence().unitKey());
            assertEquals(execution.unitTargetRevision(),
                request.fence().unitTargetRevision());
            assertEquals(execution.runtimeInstanceId(),
                request.fence().runtimeInstanceId());
            assertEquals("node-fibra-disable", request.reason());
            assertEquals(0, services.reconcileRequests.get());

            stop(sealed.unit, "disable");
            sealed.generation.retireAsync().block(Duration.ofSeconds(5));
            driver.closeAsync().block(Duration.ofSeconds(5));
        }
    }

    @Test
    void generationObservationFreezesTheCompiledCapabilitySnapshot(
        @TempDir Path work) throws Exception {
        var pidDirectory = Files.createDirectories(work.resolve("capability-pids"));
        var facet = lifecycleFacet(work, pidDirectory, false);
        var entryId = "capability-entry";
        var graph = graph(facet, List.of(entryId));
        var context = ConfigContextSnapshot.empty();
        var target = DeploymentTarget.of(1, List.of(new PluginSelection(
            facet.pluginId(), facet.packageRevision(), true)), graph, context);
        var slice = com.sstlfsj.fibra.engine.RuntimeTargetSlice.builder(
                NodeRuntimeProvider.RUNTIME_ID, target)
            .desired(DesiredEvaluation.evaluate(graph, context))
            .capabilities(HostCapabilitySnapshot.of(Map.of("host.process", false)))
            .facets(List.of(new PluginFacetSource(facet, List.of())))
            .affectedEntryIds(Set.of(entryId))
            .unitDependencies(Map.of(new ExecutionUnitKey(entryId), List.of()))
            .build();
        try (var services = new Services()) {
            var driver = new NodeRuntimeProvider(options(work)).create(services);
            var candidate = driver.createCandidate(slice);
            candidate.prepareAsync().block(Duration.ofSeconds(5));
            var key = new ExecutionUnitKey(entryId);
            var generation = candidate.seal(CompiledRuntimeSlice.of(
                candidate.preparedPlan(), List.of(key)));
            var unit = generation.units().get(key);

            assertEquals(Set.of("host.process"), unit.reconcileAsync("activate")
                .block(Duration.ofSeconds(5)).executions().getFirst().capabilities());

            stop(unit, "capability");
            generation.retireAsync().block(Duration.ofSeconds(5));
            driver.closeAsync().block(Duration.ofSeconds(5));
        }
    }

    private static com.sstlfsj.fibra.engine.RuntimeTargetSlice slice(
        ManagedFacet facet, String entryId,
        List<com.sstlfsj.fibra.engine.ResolvedFacetDependency> dependencies,
        List<ExecutionUnitKey> unitDependencies) {
        var graph = graph(facet, List.of(entryId));
        var context = ConfigContextSnapshot.empty();
        var target = DeploymentTarget.of(1, List.of(new PluginSelection(
            facet.pluginId(), facet.packageRevision(), true)), graph, context);
        return com.sstlfsj.fibra.engine.RuntimeTargetSlice.builder(
                NodeRuntimeProvider.RUNTIME_ID, target)
            .desired(DesiredEvaluation.evaluate(graph, context))
            .capabilities(HostCapabilitySnapshot.empty())
            .facets(List.of(new PluginFacetSource(facet, dependencies)))
            .affectedEntryIds(Set.of(entryId))
            .unitDependencies(Map.of(new ExecutionUnitKey(entryId), unitDependencies))
            .build();
    }

    private static com.sstlfsj.fibra.engine.RuntimeTargetSlice slice(
        ManagedFacet facet, List<String> entryIds) {
        var graph = graph(facet, entryIds);
        var context = ConfigContextSnapshot.empty();
        var target = DeploymentTarget.of(1, List.of(new PluginSelection(
            facet.pluginId(), facet.packageRevision(), true)), graph, context);
        var dependencies = new LinkedHashMap<ExecutionUnitKey, List<ExecutionUnitKey>>();
        entryIds.forEach(id -> dependencies.put(new ExecutionUnitKey(id), List.of()));
        return com.sstlfsj.fibra.engine.RuntimeTargetSlice.builder(
                NodeRuntimeProvider.RUNTIME_ID, target)
            .desired(DesiredEvaluation.evaluate(graph, context))
            .capabilities(HostCapabilitySnapshot.empty())
            .facets(List.of(new PluginFacetSource(facet, List.of())))
            .affectedEntryIds(Set.copyOf(entryIds))
            .unitDependencies(dependencies)
            .build();
    }

    private static com.sstlfsj.fibra.engine.RuntimeTargetSlice dependencySlice(
        ManagedFacet dependent, ManagedFacet dependency) {
        var entryId = "dependent-entry";
        var graph = graph(dependent, List.of(entryId));
        var context = ConfigContextSnapshot.empty();
        var target = DeploymentTarget.of(1, List.of(
            new PluginSelection(dependent.pluginId(), dependent.packageRevision(), true),
            new PluginSelection(dependency.pluginId(), dependency.packageRevision(), true)),
            graph, context);
        var resolved = new com.sstlfsj.fibra.engine.ResolvedFacetDependency(
            dependency.pluginId(), dependency.packageRevision(),
            dependency.facet().facetId(), dependency.artifactId());
        return com.sstlfsj.fibra.engine.RuntimeTargetSlice.builder(
                NodeRuntimeProvider.RUNTIME_ID, target)
            .desired(DesiredEvaluation.evaluate(graph, context))
            .capabilities(HostCapabilitySnapshot.empty())
            .facets(List.of(new PluginFacetSource(dependency, List.of()),
                new PluginFacetSource(dependent, List.of(resolved))))
            .affectedEntryIds(Set.of(entryId))
            .unitDependencies(Map.of(new ExecutionUnitKey(entryId), List.of()))
            .build();
    }

    private static DesiredInputGraph graph(ManagedFacet facet,
                                           List<String> entryIds) {
        return new DesiredInputGraph(entryIds.stream().map(id ->
            DesiredInputEntry.builder(id, new PluginDefinitionRef(
                facet.pluginId().value(), facet.facet().facetId().value(), "main"))
                .build()).toList());
    }

    private static ManagedFacet facet(Path work, String pluginId, String artifactId,
                                      Path marker) throws Exception {
        return facet(work, pluginId, artifactId, marker, REVISION,
            "b".repeat(64));
    }

    private static ManagedFacet facet(Path work, String pluginId, String artifactId,
                                      Path marker, String revision,
                                      String payloadDigest) throws Exception {
        var payload = work.resolve(pluginId);
        Files.createDirectories(payload);
        Files.writeString(payload.resolve("index.mjs"),
            "import { writeFileSync } from 'node:fs'; writeFileSync("
                + quote(marker.toString()) + ", 'started');");
        Files.writeString(payload.resolve("fibra-plugin.yaml"), """
            protocol: 1
            definitionId: main
            entrypoint: index.mjs
            contributions: []
            """);
        return new ManagedFacet(new ArtifactId(artifactId), new PluginId(pluginId),
            revision, new PluginFacet(new FacetId("main"), FacetRole.HOST,
                NodeRuntimeProvider.RUNTIME_ID, new ExecutionTarget("host"), payload,
                payloadDigest, List.of(), List.of()));
    }

    private static ManagedFacet lifecycleFacet(Path work, Path pidDirectory,
                                               boolean failStart) throws Exception {
        var suffix = failStart ? "failed" : "live";
        var payload = work.resolve("node-" + suffix + '-' + System.nanoTime());
        Files.createDirectories(payload);
        Files.writeString(payload.resolve("index.mjs"), lifecycleScript(pidDirectory,
            failStart));
        Files.writeString(payload.resolve("fibra-plugin.yaml"), """
            protocol: 1
            definitionId: main
            entrypoint: index.mjs
            contributions: []
            """);
        return new ManagedFacet(new ArtifactId("node-" + suffix + '-' + System.nanoTime()),
            new PluginId("node-" + suffix), REVISION,
            new PluginFacet(new FacetId("main"), FacetRole.HOST,
                NodeRuntimeProvider.RUNTIME_ID, new ExecutionTarget("host"), payload,
                "c".repeat(64), List.of(), List.of()));
    }

    private static ManagedFacet disableFacet(Path work) throws Exception {
        var payload = Files.createDirectories(work.resolve("node-disable"));
        Files.writeString(payload.resolve("index.mjs"), """
            import readline from 'node:readline';
            const send = message => process.stdout.write(JSON.stringify(message) + '\\n');
            const reply = (id, result) => send({jsonrpc:'2.0', id, result});
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              const {id, method} = message;
              if (method === 'fibra.handshake') reply(id, {protocol:1});
              else if (method === 'fibra.ping') reply(id, {ok:true});
              else if (method === 'fibra.start') {
                reply(id, {ok:true});
                send({jsonrpc:'2.0', method:'fibra.disable', params:{}});
              }
              else if (method === 'fibra.stop') reply(id, {ok:true});
            });
            """);
        Files.writeString(payload.resolve("fibra-plugin.yaml"), """
            protocol: 1
            definitionId: main
            entrypoint: index.mjs
            contributions: []
            """);
        return new ManagedFacet(new ArtifactId("node-disable"),
            new PluginId("node-disable"), REVISION,
            new PluginFacet(new FacetId("main"), FacetRole.HOST,
                NodeRuntimeProvider.RUNTIME_ID, new ExecutionTarget("host"), payload,
            "e".repeat(64), List.of(), List.of()));
    }

    private static ManagedFacet cleanupFailureFacet(Path work) throws Exception {
        var payload = Files.createDirectories(work.resolve("node-cleanup-failure"));
        Files.writeString(payload.resolve("index.mjs"), """
            import fs from 'node:fs';
            import readline from 'node:readline';
            const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              const {id, method} = message;
              if (method === 'fibra.handshake') reply(id, {protocol:1});
              else if (method === 'fibra.ping') reply(id, {ok:true});
              else if (method === 'fibra.start') {
                reply(id, {ok:true});
                setTimeout(() => {
                  fs.writeFileSync('termination.status', 'FAILED\\n');
                  process.exit(17);
                }, 100);
              }
            });
            """);
        Files.writeString(payload.resolve("fibra-plugin.yaml"), """
            protocol: 1
            definitionId: main
            entrypoint: index.mjs
            contributions: []
            """);
        return new ManagedFacet(new ArtifactId("node-cleanup-failure"),
            new PluginId("node-cleanup-failure"), REVISION,
            new PluginFacet(new FacetId("main"), FacetRole.HOST,
                NodeRuntimeProvider.RUNTIME_ID, new ExecutionTarget("host"), payload,
                "f".repeat(64), List.of(), List.of()));
    }

    private static ManagedFacet terminationDuringRegistrationFacet(Path work)
        throws Exception {
        var payload = Files.createDirectories(
            work.resolve("node-startup-termination"));
        Files.writeString(payload.resolve("index.mjs"), """
            import readline from 'node:readline';
            const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              const {id, method} = message;
              if (method === 'fibra.handshake') reply(id, {protocol:1});
              else if (method === 'fibra.ping') reply(id, {ok:true});
              else if (method === 'fibra.start') {
                reply(id, {ok:true});
                setImmediate(() => process.exit(17));
              }
            });
            """);
        Files.writeString(payload.resolve("fibra-plugin.yaml"), """
            protocol: 1
            definitionId: main
            entrypoint: index.mjs
            contributions:
              - name: delayed
                kind: test.delayed
                schemaVersion: 1
                method: test.invoke
                descriptor: delayed
            """);
        return new ManagedFacet(new ArtifactId("node-startup-termination"),
            new PluginId("node-startup-termination"), REVISION,
            new PluginFacet(new FacetId("main"), FacetRole.HOST,
                NodeRuntimeProvider.RUNTIME_ID, new ExecutionTarget("host"), payload,
                "7".repeat(64), List.of(), List.of()));
    }

    private static ManagedFacet stopCleanupFailureFacet(Path work) throws Exception {
        var payload = Files.createDirectories(work.resolve("node-stop-cleanup-failure"));
        Files.writeString(payload.resolve("index.mjs"), """
            import fs from 'node:fs';
            import readline from 'node:readline';
            const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            const reject = id => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, error:{code:-32000, message:'stop rejected'}}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              const {id, method} = message;
              if (method === 'fibra.handshake') reply(id, {protocol:1});
              else if (method === 'fibra.ping') reply(id, {ok:true});
              else if (method === 'fibra.start') reply(id, {ok:true});
              else if (method === 'fibra.stop') {
                fs.writeFileSync('termination.status', 'FAILED\\n');
                reject(id);
              }
            });
            """);
        Files.writeString(payload.resolve("fibra-plugin.yaml"), """
            protocol: 1
            definitionId: main
            entrypoint: index.mjs
            contributions: []
            """);
        return new ManagedFacet(new ArtifactId("node-stop-cleanup-failure"),
            new PluginId("node-stop-cleanup-failure"), REVISION,
            new PluginFacet(new FacetId("main"), FacetRole.HOST,
                NodeRuntimeProvider.RUNTIME_ID, new ExecutionTarget("host"), payload,
                "9".repeat(64), List.of(), List.of()));
    }

    private static String lifecycleScript(Path pidDirectory, boolean failStart) {
        return """
            import fs from 'node:fs';
            import path from 'node:path';
            import readline from 'node:readline';
            const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            const fail = id => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, error:{code:-32000, message:'start failed'}}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              const {id, method} = message;
              if (method === 'fibra.handshake') reply(id, {protocol:1});
              else if (method === 'fibra.ping') reply(id, {ok:true});
              else if (method === 'fibra.start') {
                fs.writeFileSync(path.join(%s, String(process.pid)), 'started');
                %s
              }
              else if (method === 'fibra.stop') reply(id, {ok:true});
            });
            """.formatted(quote(pidDirectory.toString()),
            failStart ? "fail(id);" : "reply(id, {ok:true});");
    }

    private static NodeRuntimeOptions options(Path work) {
        return NodeRuntimeOptions.builder(
                Path.of(System.getProperty("fibra.test.node", "node")),
                work.resolve("sessions"))
            .handshakeTimeout(Duration.ofSeconds(3))
            .defaultRequestTimeout(Duration.ofSeconds(3))
            .terminateTimeout(Duration.ofSeconds(1))
            .build();
    }

    private static SealedUnit sealedUnit(com.sstlfsj.fibra.engine.RuntimeDriver driver,
                                         ManagedFacet facet, String entryId) {
        var candidate = driver.createCandidate(slice(facet, List.of(entryId)));
        candidate.prepareAsync().block(Duration.ofSeconds(5));
        var key = new ExecutionUnitKey(entryId);
        var generation = candidate.seal(CompiledRuntimeSlice.of(
            candidate.preparedPlan(), List.of(key)));
        return new SealedUnit(generation, generation.units().get(key));
    }

    private static void stop(RuntimeUnitGeneration unit, String suffix) {
        unit.closeAdmission();
        unit.drainAsync("drain-" + suffix, deadline()).block(Duration.ofSeconds(5));
        unit.stopAsync("stop-" + suffix, deadline()).block(Duration.ofSeconds(5));
    }

    private static Instant deadline() {
        return Instant.now().plusSeconds(5);
    }

    private static long pidCount(Path directory) throws Exception {
        try (var values = Files.list(directory)) {
            return values.count();
        }
    }

    private static int payloadCount(com.sstlfsj.fibra.engine.RuntimeDriver driver)
        throws Exception {
        return payloads(driver).size();
    }

    private static int payloadReferences(com.sstlfsj.fibra.engine.RuntimeDriver driver)
        throws Exception {
        var payload = payloads(driver).values().stream().findFirst().orElseThrow();
        var references = payload.getClass().getDeclaredField("references");
        references.setAccessible(true);
        return references.getInt(payload);
    }

    private static int totalPayloadReferences(
        com.sstlfsj.fibra.engine.RuntimeDriver driver) throws Exception {
        var total = 0;
        for (var payload : payloads(driver).values()) {
            var references = payload.getClass().getDeclaredField("references");
            references.setAccessible(true);
            total += references.getInt(payload);
        }
        return total;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> payloads(
        com.sstlfsj.fibra.engine.RuntimeDriver driver) throws Exception {
        Field field = driver.getClass().getDeclaredField("payloads");
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(driver);
    }

    private static String quote(String value) {
        return tools.jackson.databind.json.JsonMapper.builder().build()
            .valueToTree(value).toString();
    }

    private record SealedUnit(
        com.sstlfsj.fibra.engine.PreparedRuntimeGeneration generation,
        RuntimeUnitGeneration unit) { }

    private static final class Services implements RuntimeHostServices, AutoCloseable {
        private final FibraRuntime runtime = FibraRuntime.create();
        private final com.sstlfsj.fibra.runtime.RuntimeDomain domain =
            runtime.openDomain("node-driver-test");
        private final ContributionDirectory directory = new ContributionDirectory();
        private final boolean failFirstChildClose;
        private final boolean delayRegistration;
        private final AtomicInteger scopeCloseAttempts = new AtomicInteger();
        private final AtomicInteger reconcileRequests = new AtomicInteger();
        private final CountDownLatch reconcileRequested = new CountDownLatch(1);
        private final AtomicReference<com.sstlfsj.fibra.engine.RuntimeUnitDisableRequest>
            disableRequest = new AtomicReference<>();
        private final CountDownLatch disableRequested = new CountDownLatch(1);
        private final CountDownLatch registrationStarted = new CountDownLatch(1);
        private final CountDownLatch admissionClosed = new CountDownLatch(1);
        private final Sinks.One<Void> registrationRelease = Sinks.one();
        private int identities;

        private Services() {
            this(false, false);
        }

        private Services(boolean failFirstChildClose) {
            this(failFirstChildClose, false);
        }

        private Services(boolean failFirstChildClose,
                         boolean delayRegistration) {
            this.failFirstChildClose = failFirstChildClose;
            this.delayRegistration = delayRegistration;
        }

        @Override public String hostInstanceId() { return "node-test-host"; }
        @Override public ScopeView scope() {
            var root = domain.rootScope().context().scope();
            if (!failFirstChildClose) return root;
            return new ScopeView() {
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
            return delayRegistration
                ? com.sstlfsj.fibra.bridge.ContributionKindRegistry.of(TEST_KIND)
                : com.sstlfsj.fibra.bridge.ContributionKindRegistry.empty();
        }
        @Override public com.sstlfsj.fibra.bridge.ContributionAdmission openContributionAdmission(com.sstlfsj.fibra.engine.ExecutionUnitKey key) {
            var delegate = directory.openAdmission(key.value());
            if (!delayRegistration) return delegate;
            return new com.sstlfsj.fibra.bridge.ContributionAdmission() {
                @Override public <D, I, O> Mono<com.sstlfsj.fibra.bridge.ContributionRegistration> register(
                    com.sstlfsj.fibra.Context owner,
                    ContributionKind<D, I, O> kind, String localName,
                    D descriptor,
                    com.sstlfsj.fibra.bridge.ContributionHandler<I, O> handler) {
                    return delegate.register(owner, kind, localName, descriptor,
                        handler);
                }
                @Override public Mono<List<com.sstlfsj.fibra.bridge.ContributionRegistration>> registerAll(
                    com.sstlfsj.fibra.Context owner,
                    List<com.sstlfsj.fibra.bridge.ContributionBinding<?, ?, ?>> bindings,
                    com.sstlfsj.fibra.Disposable afterDrain) {
                    registrationStarted.countDown();
                    return registrationRelease.asMono().then(
                        delegate.registerAll(owner, bindings, afterDrain));
                }
                @Override public void closeAdmission() {
                    delegate.closeAdmission();
                    admissionClosed.countDown();
                }
                @Override public Mono<Void> drainAsync() {
                    return delegate.drainAsync();
                }
            };
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
            reconcileRequests.incrementAndGet();
            reconcileRequested.countDown();
        }
        @Override public void requestObservationRefresh(
            com.sstlfsj.fibra.engine.RuntimeUnitFence fence) { }
        @Override public void requestDisable(
            com.sstlfsj.fibra.engine.RuntimeUnitDisableRequest request) {
            disableRequest.set(request);
            disableRequested.countDown();
        }
        @Override public void requestRecompile(RuntimeRecompileReason reason) { }

        @Override public void close() {
            directory.close();
            runtime.close();
        }
    }

    private static final class FailOnceCloseScope implements com.sstlfsj.fibra.Scope {
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
                return reactor.core.publisher.Mono.error(
                    new IllegalStateException("injected scope close failure"));
            }
            return delegate.closeAsync();
        }
    }
}
