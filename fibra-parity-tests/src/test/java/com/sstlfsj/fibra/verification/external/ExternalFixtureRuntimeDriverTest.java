package com.sstlfsj.fibra.verification.external;

import com.sstlfsj.fibra.ScopeView;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ExecutionTarget;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.FacetRole;
import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.PluginFacet;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.bridge.ContributionAdmission;
import com.sstlfsj.fibra.bridge.ContributionDirectory;
import com.sstlfsj.fibra.bridge.ContributionKindRegistry;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredEvaluation;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.engine.CompiledRuntimeSlice;
import com.sstlfsj.fibra.engine.DeploymentTarget;
import com.sstlfsj.fibra.engine.ExecutionObservation;
import com.sstlfsj.fibra.engine.ExecutionUnitKey;
import com.sstlfsj.fibra.engine.HostCapabilitySnapshot;
import com.sstlfsj.fibra.engine.PluginFacetSource;
import com.sstlfsj.fibra.engine.PluginSelection;
import com.sstlfsj.fibra.engine.PreparedRuntimeGeneration;
import com.sstlfsj.fibra.engine.RemoteContributionInvoker;
import com.sstlfsj.fibra.engine.RuntimeCandidate;
import com.sstlfsj.fibra.engine.RuntimeHostServices;
import com.sstlfsj.fibra.engine.RuntimeRecompileReason;
import com.sstlfsj.fibra.engine.RuntimeTargetSlice;
import com.sstlfsj.fibra.engine.RuntimeUnitGeneration;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalFixtureRuntimeDriverTest {
    private static final String REVISION = "a".repeat(64);

    @Test
    void sealingReplacementDoesNotTakeControlOfCurrentUnits() {
        verifyCandidateDoesNotReplaceCurrentControl(false);
    }

    @Test
    void abortingReplacementPreservesCurrentUnitControl() {
        verifyCandidateDoesNotReplaceCurrentControl(true);
    }

    private void verifyCandidateDoesNotReplaceCurrentControl(boolean abortBeforeDisconnect) {
        try (var services = new Services()) {
            var driver = (ExternalFixtureRuntimeDriver) new ExternalFixtureRuntimeProvider().create(services);
            var controller = new ExternalFixtureRuntimeController(driver);
            controller.goOnline();
            var current = generation(driver);
            current.units().values().forEach(unit ->
                assertActive(unit.reconcileAsync("activate-" + unit.plan().key().value()).block()));
            services.reconcileRequests.set(0);
            var candidate = generation(driver);
            if (abortBeforeDisconnect) candidate.abortAsync().block();

            controller.goOffline();

            assertEquals(1, services.reconcileRequests.get());
            assertTrue(services.directory.current().snapshot().entries().isEmpty());
            current.units().values().forEach(unit ->
                assertEquals(ExecutionObservation.State.FAILED, unit.snapshot().aggregateState()));
            controller.goOnline();
            assertEquals(2, services.reconcileRequests.get());
            if (!abortBeforeDisconnect) candidate.abortAsync().block();
            stopAndRetire(current);
            driver.closeAsync().block();
        }
    }

    @Test
    void activeDisconnectFailsUnitsAndRequestsEngineReplacement() {
        try (var services = new Services()) {
            var provider = new ExternalFixtureRuntimeProvider();
            var driver = (ExternalFixtureRuntimeDriver) provider.create(services);
            var controller = new ExternalFixtureRuntimeController(driver);
            var generation = generation(driver);
            var units = generation.units();

            assertEquals(0, controller.snapshot().counters().activations());
            assertEquals(1, controller.snapshot().counters().resourceGenerations());
            assertEquals(2, controller.snapshot().counters().resourceLeases());
            assertPending(units.get(new ExecutionUnitKey("entry-a")).reconcileAsync("offline-a").block());
            assertPending(units.get(new ExecutionUnitKey("entry-b")).reconcileAsync("offline-b").block());

            controller.goOnline();
            assertEquals(1, services.reconcileRequests.get());
            assertEquals(0, controller.snapshot().counters().activations(),
                "controller must not start a unit directly");

            assertActive(units.get(new ExecutionUnitKey("entry-a")).reconcileAsync("online-a").block());
            assertActive(units.get(new ExecutionUnitKey("entry-b")).reconcileAsync("online-b").block());
            assertEquals(2, services.directory.current().snapshot().entries().size());
            assertEquals(Set.of("{\"mode\":\"one\"}", "{\"mode\":\"two\"}"),
                services.directory.current().snapshot().entries().stream()
                    .map(entry -> entry.descriptor().toString())
                    .collect(java.util.stream.Collectors.toSet()),
                "shared facets must preserve each desired entry's resolved config");

            controller.goOffline();
            assertEquals(0, services.directory.current().snapshot().entries().size(),
                "disconnect must synchronously revoke every route");
            assertEquals(0, controller.snapshot().counters().stops(),
                "disconnect is not a lifecycle stop");
            assertEquals(ExecutionObservation.State.FAILED,
                units.get(new ExecutionUnitKey("entry-a")).snapshot().aggregateState());
            assertEquals(2, services.reconcileRequests.get());
            assertEquals(List.of("external-fixture-online",
                "external-fixture-disconnected"), services.reconcileReasons);

            stopAndRetire(generation);
            assertEquals(0, controller.snapshot().counters().resourceGenerations());
            assertEquals(0, controller.snapshot().counters().resourceLeases());
            assertTrue(controller.snapshot().events().stream().anyMatch(event ->
                event.type().equals("DISCONNECTED")
                    && event.detail().equals("failed-replacement-requested")));
        }
    }

    @Test
    void eachEntryHasIndependentIdentityAndOperationFenceWhileSharingOneResource() {
        try (var services = new Services()) {
            var provider = new ExternalFixtureRuntimeProvider();
            var driver = (ExternalFixtureRuntimeDriver) provider.create(services);
            var controller = new ExternalFixtureRuntimeController(driver);
            var generation = generation(driver);
            controller.goOnline();
            var first = generation.units().get(new ExecutionUnitKey("entry-a"));
            var second = generation.units().get(new ExecutionUnitKey("entry-b"));

            var firstActive = first.reconcileAsync("reconcile-a").block();
            var secondActive = second.reconcileAsync("reconcile-b").block();
            var firstDetail = firstActive.executions().getFirst();
            var secondDetail = secondActive.executions().getFirst();
            assertEquals(7, firstDetail.unitTargetRevision());
            assertEquals(7, secondDetail.unitTargetRevision());
            assertNotEquals(firstDetail.runtimeInstanceId(), secondDetail.runtimeInstanceId());
            assertThrows(IllegalStateException.class,
                () -> first.reconcileAsync("stale-reconcile").block());

            first.closeAdmission();
            second.closeAdmission();
            assertEquals(0, services.directory.current().snapshot().entries().size());
            first.drainAsync("drain-a", Instant.now().plusSeconds(1)).block();
            assertThrows(IllegalStateException.class,
                () -> first.drainAsync("wrong-drain", Instant.now().plusSeconds(1)).block());
            second.drainAsync("drain-b", Instant.now().plusSeconds(1)).block();
            first.stopAsync("stop-a", Instant.now().plusSeconds(1)).block();
            second.stopAsync("stop-b", Instant.now().plusSeconds(1)).block();
            assertEquals(1, controller.snapshot().counters().resourceGenerations(),
                "stop must retain shared resource until generation retirement");
            generation.retireAsync().block();
            assertEquals(0, controller.snapshot().counters().resourceGenerations());
        }
    }

    @Test
    void providerCreatesIndependentDriversForSequentialHosts() {
        var provider = new ExternalFixtureRuntimeProvider();
        var identity = provider.contractIdentity();
        var packages = provider.builtInPackages();
        try (var firstServices = new Services("host-a");
             var secondServices = new Services("host-b")) {
            var first = (ExternalFixtureRuntimeDriver) provider.create(firstServices);
            var second = (ExternalFixtureRuntimeDriver) provider.create(secondServices);
            assertNotSame(first, second);
            assertEquals(identity, provider.contractIdentity());
            assertEquals(packages, provider.builtInPackages());

            var firstController = new ExternalFixtureRuntimeController(first);
            var secondController = new ExternalFixtureRuntimeController(second);
            firstController.goOnline();
            assertTrue(firstController.snapshot().online());
            assertTrue(!secondController.snapshot().online());

            first.closeAsync().block();
            secondController.goOnline();
            assertTrue(secondController.snapshot().online(),
                "closing one Host driver must not affect another Host");
            second.closeAsync().block();
        }
    }

    @Test
    void generationObservationFreezesTheCompiledCapabilitySnapshot() {
        try (var services = new Services()) {
            var driver = (ExternalFixtureRuntimeDriver)
                new ExternalFixtureRuntimeProvider().create(services);
            var controller = new ExternalFixtureRuntimeController(driver);
            controller.goOnline();
            var candidate = driver.createCandidate(slice(
                HostCapabilitySnapshot.of(Map.of("client.module", false))));
            candidate.prepareAsync().block();
            var plan = candidate.preparedPlan();
            var generation = candidate.seal(CompiledRuntimeSlice.of(plan,
                List.of(new ExecutionUnitKey("entry-a"),
                    new ExecutionUnitKey("entry-b"))));
            var unit = generation.units().get(new ExecutionUnitKey("entry-a"));

            assertEquals(Set.of("client.module"), unit.reconcileAsync("activate")
                .block().executions().getFirst().capabilities());

            stopAndRetire(generation);
            driver.closeAsync().block();
        }
    }

    private static PreparedRuntimeGeneration generation(
        com.sstlfsj.fibra.engine.RuntimeDriver driver) {
        RuntimeCandidate candidate = driver.createCandidate(slice());
        candidate.prepareAsync().block();
        var plan = candidate.preparedPlan();
        return candidate.seal(CompiledRuntimeSlice.of(plan,
            List.of(new ExecutionUnitKey("entry-a"), new ExecutionUnitKey("entry-b"))));
    }

    private static RuntimeTargetSlice slice() {
        return slice(HostCapabilitySnapshot.empty());
    }

    private static RuntimeTargetSlice slice(HostCapabilitySnapshot capabilities) {
        var facet = new ManagedFacet(new ArtifactId("external-artifact"),
            new PluginId("external-plugin"), REVISION,
            new PluginFacet(new FacetId("main"), FacetRole.HOST,
                ExternalFixtureRuntimeProvider.RUNTIME_ID, new ExecutionTarget("host"),
                Path.of("external-fixture"), "b".repeat(64), List.of(), List.of()));
        var graph = new DesiredInputGraph(List.of(entry("entry-a", "one"),
            entry("entry-b", "two")));
        var target = DeploymentTarget.of(7, List.of(new PluginSelection(
            facet.pluginId(), facet.packageRevision(), true)), graph,
            ConfigContextSnapshot.empty());
        return RuntimeTargetSlice.builder(ExternalFixtureRuntimeProvider.RUNTIME_ID, target)
            .desired(DesiredEvaluation.evaluate(graph, target.configContext()))
            .capabilities(capabilities)
            .facets(List.of(new PluginFacetSource(facet, List.of())))
            .affectedEntryIds(Set.of("entry-a", "entry-b"))
            .unitDependencies(Map.of(new ExecutionUnitKey("entry-a"), List.of(),
                new ExecutionUnitKey("entry-b"), List.of()))
            .build();
    }

    private static DesiredInputEntry entry(String id, String mode) {
        return DesiredInputEntry.builder(id, new PluginDefinitionRef("external-plugin",
            "main", ExternalFixtureRuntimeProvider.DEFINITION_ID))
            .config(LiteralValue.of(Map.of("mode", mode))).build();
    }

    private static void assertPending(ExecutionObservation observation) {
        assertEquals(ExecutionObservation.State.PENDING, observation.aggregateState());
    }

    private static void assertActive(ExecutionObservation observation) {
        assertEquals(ExecutionObservation.State.ACTIVE, observation.aggregateState());
    }

    private static void stopAndRetire(PreparedRuntimeGeneration generation) {
        generation.units().values().forEach(unit -> unit.closeAdmission());
        generation.units().values().forEach(unit ->
            unit.drainAsync("drain-" + unit.plan().key().value(), Instant.now().plusSeconds(1)).block());
        generation.units().values().forEach(unit ->
            unit.stopAsync("stop-" + unit.plan().key().value(), Instant.now().plusSeconds(1)).block());
        generation.retireAsync().block();
    }

    private static final class Services implements RuntimeHostServices, AutoCloseable {
        private final String hostInstanceId;
        private final FibraRuntime runtime = FibraRuntime.create();
        private final com.sstlfsj.fibra.runtime.RuntimeDomain domain =
            runtime.openDomain("external-fixture-test");
        private final ContributionDirectory directory = new ContributionDirectory();
        private final AtomicInteger reconcileRequests = new AtomicInteger();
        private final List<String> reconcileReasons =
            new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicInteger identities = new AtomicInteger();

        private Services() {
            this("external-fixture-test");
        }

        private Services(String hostInstanceId) {
            this.hostInstanceId = hostInstanceId;
        }

        @Override public String hostInstanceId() { return hostInstanceId; }
        @Override public ScopeView scope() { return domain.rootScope().context().scope(); }
        @Override public reactor.core.publisher.Mono<Void> releaseScope(
            com.sstlfsj.fibra.Scope scope) {
            return reactor.core.publisher.Mono.defer(scope::closeAsync)
                .then(reactor.core.publisher.Mono.fromRunnable(() -> {
                    var failures = domain.cleanupFailures(scope);
                    if (!failures.isEmpty()) {
                        throw new IllegalStateException(
                            "runtime scope cleanup failed: " + failures);
                    }
                }));
        }
        @Override public ContributionKindRegistry contributionKinds() {
            return ContributionKindRegistry.of(ExternalFixtureRuntimeProvider.REMOTE_KIND);
        }
        @Override public ContributionAdmission openContributionAdmission(com.sstlfsj.fibra.engine.ExecutionUnitKey key) {
            return directory.openAdmission(key.value());
        }
        @Override public RemoteContributionInvoker remoteContributions() {
            throw new UnsupportedOperationException();
        }
        @Override public String nextIdentity(String namespace) {
            return namespace + ':' + identities.incrementAndGet();
        }
        @Override public void requestReconcile(
                                               Set<com.sstlfsj.fibra.engine.RuntimeUnitFence> fences,
                                               String reason) {
            assertEquals(Set.of(new ExecutionUnitKey("entry-a"),
                new ExecutionUnitKey("entry-b")), fences.stream()
                .peek(fence -> assertEquals(
                    ExternalFixtureRuntimeProvider.RUNTIME_ID,
                    fence.runtimeId()))
                .map(com.sstlfsj.fibra.engine.RuntimeUnitFence::unitKey)
                .collect(java.util.stream.Collectors.toSet()));
            assertTrue(reason.equals("external-fixture-online")
                || reason.equals("external-fixture-disconnected"));
            reconcileReasons.add(reason);
            reconcileRequests.incrementAndGet();
        }
        @Override public void requestObservationRefresh(
            com.sstlfsj.fibra.engine.RuntimeUnitFence fence) { }
        @Override public void requestDisable(
            com.sstlfsj.fibra.engine.RuntimeUnitDisableRequest request) { }
        @Override public void requestRecompile(RuntimeRecompileReason reason) { }
        @Override public void close() {
            directory.close();
            runtime.close();
        }
    }
}
