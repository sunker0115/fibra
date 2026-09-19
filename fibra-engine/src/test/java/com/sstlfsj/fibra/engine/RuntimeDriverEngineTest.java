package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.*;
import com.sstlfsj.fibra.config.*;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeDriverEngineTest {
    @TempDir Path root;
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final Store store = new Store();
    private final ProbeProvider alpha = new ProbeProvider("alpha", "a");
    private final ProbeProvider beta = new ProbeProvider("beta", "b");

    @Test
    void everyRuntimeSealsBeforeSaveAndGlobalDependencyOrderControlsReplacement() {
        beta.dependencies = List.of(new FacetDependency(new PluginId("a"), new FacetId("main")));
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1", "b1"));
            assertEquals(List.of("prepare:alpha", "prepare:beta", "seal:alpha", "seal:beta",
                "save:1", "activate:a1", "activate:b1"), events);
            events.clear();
            apply(engine, 1, graph(2, "a1", "b1"));
            assertEquals(List.of("prepare:alpha", "prepare:beta", "seal:alpha", "seal:beta", "save:2",
                "admission:b1", "admission:a1", "drain:b1", "drain:a1", "stop:b1", "stop:a1",
                "activate:a1", "activate:b1", "retire:beta", "retire:alpha"), events);
        }
    }

    @Test
    void partialSealAndExplicitSaveFailureAbortWithoutActivatingAnyUnit() {
        beta.sealFailure = true;
        try (var engine = engine(request -> fail("unexpected termination"))) {
            assertThrows(EngineChangeException.class, () -> apply(engine, 0, graph(1, "a1", "b1")));
            assertEquals(0, store.saves);
            assertTrue(events.contains("abort:alpha"));
            assertTrue(events.contains("close:beta"));
            assertFalse(events.stream().anyMatch(value -> value.startsWith("activate:")));
            beta.sealFailure = false;
            store.fail = true;
            events.clear();
            assertThrows(EngineChangeException.class, () -> apply(engine, 0, graph(1, "a1", "b1")));
            assertTrue(events.containsAll(List.of("abort:alpha", "abort:beta")));
            assertFalse(events.stream().anyMatch(value -> value.startsWith("activate:")));
            assertTrue(engine.published().current().engineDiagnostics().mutationGateOpen());
            assertEquals(DurableTargetState.ABSENT, engine.snapshot().durableState());
        }
    }

    @Test
    void sameContentIsNoopButAToBToAKeepsMonotonicRevision() {
        try (var engine = engine(request -> { })) {
            var a = apply(engine, 0, graph(1, "a1"));
            var identity = detail(engine, "a1").runtimeInstanceId();
            events.clear();
            var same = apply(engine, 1, graph(1, "a1"));
            assertEquals(a.viewRevision(), same.viewRevision());
            assertTrue(events.isEmpty());
            assertEquals(identity, detail(engine, "a1").runtimeInstanceId());
            apply(engine, 1, graph(2, "a1"));
            var secondA = apply(engine, 2, graph(1, "a1"));
            assertEquals(a.engine().target().orElseThrow().targetDigest(),
                secondA.engine().target().orElseThrow().targetDigest());
            assertEquals(3, secondA.engine().target().orElseThrow().targetRevision());
        }
    }

    @Test
    void retainedUnitKeepsObjectInstanceAndItsCreationRevision() {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1", "a2"));
            var retained = alpha.units.get("a1");
            var first = detail(engine, "a1");
            var graph = new DesiredInputGraph(List.of(entry("a1", "a", 1), entry("a2", "a", 2)));
            apply(engine, 1, graph);
            assertSame(retained, alpha.units.get("a1"));
            assertEquals(first.runtimeInstanceId(), detail(engine, "a1").runtimeInstanceId());
            assertEquals(1, detail(engine, "a1").unitTargetRevision());
            assertEquals(2, detail(engine, "a2").unitTargetRevision());
            assertEquals(0, alpha.retired.get(), "shared generation is still owned by retained a1");
        }
    }

    @Test
    void concurrentStartupSubscribersReceiveTheExactBootstrapViewBeforeQueuedReplacement()
        throws Exception {
        try (var seed = engine(request -> { })) {
            apply(seed, 0, graph(1, "a1"));
        }
        store.reopen();
        events.clear();
        var activationEntered = Sinks.<Void>one();
        var activationRelease = Sinks.<Void>one();
        alpha.activationEntered = activationEntered;
        alpha.activationRelease = activationRelease;
        var engine = FibraEngine.builder(
                new PluginPackageStore(root.resolve("concurrent-start")), store)
            .runtimeProvider(alpha).runtimeProvider(beta)
            .hostTerminationPort(request -> { }).build();
        try {
            var first = engine.startAsync().toFuture();
            var second = engine.startAsync().toFuture();
            activationEntered.asMono().block(Duration.ofSeconds(2));
            var replacement = engine.submit(ApplyDeployment.builder(graph(2, "a1"))
                .expectedRevision(1)
                .selections(List.of(alpha.metadata().selection(true),
                    beta.metadata().selection(true)))
                .configContext(ConfigContextSnapshot.empty()).build()).toFuture();
            activationRelease.tryEmitEmpty();

            var firstView = first.get(2, TimeUnit.SECONDS);
            var secondView = second.get(2, TimeUnit.SECONDS);
            assertEquals(1, firstView.engine().target().orElseThrow()
                .targetRevision());
            assertEquals(firstView, secondView);
            assertEquals(2, replacement.get(2, TimeUnit.SECONDS).view()
                .engine().target().orElseThrow()
                .targetRevision());
        } finally {
            alpha.activationEntered = null;
            alpha.activationRelease = null;
            engine.close();
        }
    }

    @Test
    void currentUnitDisablePersistsACompleteTargetAndDuplicateIsNoop() {
        try (var engine = engine(request -> { })) {
            var graph = graph(1, "a1", "b1");
            var context = ConfigContextSnapshot.of(new LiteralValue.ObjectValue(
                Map.of("tenant", LiteralValue.of("stable"))));
            engine.submit(ApplyDeployment.builder(graph).expectedRevision(0)
                .selections(List.of(alpha.metadata().selection(true),
                    beta.metadata().selection(true)))
                .configContext(context).build()).block();
            var before = engine.snapshot().target().orElseThrow();
            var alphaDetail = detail(engine, "a1");
            var betaIdentity = detail(engine, "b1").runtimeInstanceId();

            alpha.services.requestDisable(disable(alpha.id(), "a1", alphaDetail));
            flush(engine);

            var disabled = engine.snapshot().target().orElseThrow();
            assertEquals(before.targetRevision() + 1, disabled.targetRevision());
            assertEquals(before.selections(), disabled.selections());
            assertEquals(context, disabled.configContext());
            assertFalse(disabled.desiredGraph().plugins().get("a1").enabled());
            assertTrue(disabled.desiredGraph().plugins().get("b1").enabled());
            assertFalse(engine.snapshot().units().containsKey(
                new ExecutionUnitKey("a1")));
            assertEquals(betaIdentity, detail(engine, "b1").runtimeInstanceId());
            var saves = store.saves;

            alpha.services.requestDisable(disable(alpha.id(), "a1", alphaDetail));
            flush(engine);

            assertEquals(saves, store.saves);
            assertEquals(disabled, engine.snapshot().target().orElseThrow());
        }
    }

    @Test
    void staleOrMismatchedUnitDisableCannotAffectCurrentGeneration() {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1"));
            var replaced = detail(engine, "a1");
            apply(engine, 1, graph(2, "a1"));
            var current = detail(engine, "a1");
            var target = engine.snapshot().target().orElseThrow();
            var saves = store.saves;

            alpha.services.requestDisable(disable(beta.id(), "a1", current));
            alpha.services.requestDisable(RuntimeUnitDisableRequest.of(
                RuntimeUnitFence.builder(alpha.id(), new ExecutionUnitKey("a1"))
                .unitTargetRevision(current.unitTargetRevision() + 1)
                .runtimeInstanceId(current.runtimeInstanceId())
                .build(), "wrong-revision"));
            alpha.services.requestDisable(RuntimeUnitDisableRequest.of(
                RuntimeUnitFence.builder(alpha.id(), new ExecutionUnitKey("a1"))
                .unitTargetRevision(current.unitTargetRevision())
                .runtimeInstanceId(current.runtimeInstanceId() + ":stale")
                .build(), "wrong-instance"));
            alpha.services.requestDisable(disable(alpha.id(), "a1", replaced));
            flush(engine);

            assertEquals(saves, store.saves);
            assertEquals(target, engine.snapshot().target().orElseThrow());
            assertEquals(current, detail(engine, "a1"));
        }
    }

    @Test
    void currentFenceRefreshPublishesLiveStateWithoutSavingReconcilingOrReplacing() throws Exception {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1"));
            var unit = alpha.units.get("a1");
            var active = detail(engine, "a1");
            var fence = fence(alpha.id(), "a1", active);
            var saves = store.saves;
            events.clear();

            var pendingView = nextState(engine, "a1",
                ExecutionObservation.State.PENDING);
            unit.observe("dependency-lost", ExecutionObservation.State.PENDING);
            alpha.services.requestObservationRefresh(fence);
            var pending = pendingView.get(2, TimeUnit.SECONDS);
            assertFalse(pending.engineDiagnostics().targetSatisfied());
            assertEquals(active.runtimeInstanceId(), detail(pending, "a1")
                .runtimeInstanceId());

            var failedView = nextState(engine, "a1",
                ExecutionObservation.State.FAILED);
            unit.observe("dependency-failed", ExecutionObservation.State.FAILED);
            alpha.services.requestObservationRefresh(fence);
            var failed = failedView.get(2, TimeUnit.SECONDS);
            assertFalse(failed.engineDiagnostics().targetSatisfied());
            assertNotNull(detail(failed, "a1").failure());

            var recoveredView = nextState(engine, "a1",
                ExecutionObservation.State.ACTIVE);
            unit.observe("dependency-recovered", ExecutionObservation.State.ACTIVE);
            alpha.services.requestObservationRefresh(fence);
            var recovered = recoveredView.get(2, TimeUnit.SECONDS);
            assertTrue(recovered.engineDiagnostics().targetSatisfied());
            assertEquals(active.runtimeInstanceId(), detail(recovered, "a1")
                .runtimeInstanceId());
            assertSame(unit, alpha.units.get("a1"));
            assertEquals(saves, store.saves);
            assertTrue(events.isEmpty());
        }
    }

    @Test
    void staleObservationFenceIsIgnoredWithoutPublishing() {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1"));
            var retiredFence = fence(alpha.id(), "a1", detail(engine, "a1"));
            apply(engine, 1, graph(2, "a1"));
            var before = engine.published().current();
            var current = detail(before, "a1");
            alpha.units.get("a1").observe("must-not-publish",
                ExecutionObservation.State.ACTIVE);

            alpha.services.requestObservationRefresh(retiredFence);
            alpha.services.requestObservationRefresh(RuntimeUnitFence.builder(
                    beta.id(), new ExecutionUnitKey("a1"))
                .unitTargetRevision(current.unitTargetRevision())
                .runtimeInstanceId(current.runtimeInstanceId()).build());
            alpha.services.requestObservationRefresh(RuntimeUnitFence.builder(
                    alpha.id(), new ExecutionUnitKey("missing"))
                .unitTargetRevision(current.unitTargetRevision())
                .runtimeInstanceId(current.runtimeInstanceId()).build());
            alpha.services.requestObservationRefresh(RuntimeUnitFence.builder(
                    alpha.id(), new ExecutionUnitKey("a1"))
                .unitTargetRevision(current.unitTargetRevision() + 1)
                .runtimeInstanceId(current.runtimeInstanceId()).build());
            alpha.services.requestObservationRefresh(RuntimeUnitFence.builder(
                    alpha.id(), new ExecutionUnitKey("a1"))
                .unitTargetRevision(current.unitTargetRevision())
                .runtimeInstanceId(current.runtimeInstanceId() + ":old").build());
            flush(engine);

            assertEquals(before.viewRevision(), engine.published().current()
                .viewRevision());
            assertEquals(current.lifecycleOperationId(), detail(engine, "a1")
                .lifecycleOperationId());
        }
    }

    @Test
    void staleReconcileFenceCannotReplaceTheCurrentFailedGeneration()
        throws Exception {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1"));
            var stale = fence(alpha.id(), "a1", detail(engine, "a1"));
            apply(engine, 1, graph(2, "a1"));
            var current = detail(engine, "a1");
            var currentFence = fence(alpha.id(), "a1", current);
            var unit = alpha.units.get("a1");
            unit.observe("current-failed", ExecutionObservation.State.FAILED);
            var failedView = nextState(engine, "a1",
                ExecutionObservation.State.FAILED);

            alpha.services.requestReconcile(Set.of(stale,
                RuntimeUnitFence.builder(beta.id(),
                        new ExecutionUnitKey("a1"))
                    .unitTargetRevision(current.unitTargetRevision())
                    .runtimeInstanceId(current.runtimeInstanceId()).build(),
                RuntimeUnitFence.builder(alpha.id(),
                        new ExecutionUnitKey("missing"))
                    .unitTargetRevision(current.unitTargetRevision())
                    .runtimeInstanceId(current.runtimeInstanceId()).build(),
                RuntimeUnitFence.builder(alpha.id(),
                        new ExecutionUnitKey("a1"))
                    .unitTargetRevision(current.unitTargetRevision() + 1)
                    .runtimeInstanceId(current.runtimeInstanceId()).build(),
                RuntimeUnitFence.builder(alpha.id(),
                        new ExecutionUnitKey("a1"))
                    .unitTargetRevision(current.unitTargetRevision())
                    .runtimeInstanceId(current.runtimeInstanceId() + ":old")
                    .build()), "stale-runtime-callback");
            alpha.services.requestObservationRefresh(currentFence);
            failedView.get(2, TimeUnit.SECONDS);

            assertEquals(current.runtimeInstanceId(),
                detail(engine, "a1").runtimeInstanceId());
            assertEquals(ExecutionObservation.State.FAILED,
                detail(engine, "a1").state());
        }
    }

    @Test
    void oneRefreshSamplesEachUnitOnceForSnapshotAndTargetSatisfaction() throws Exception {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1"));
            var current = detail(engine, "a1");
            var unit = alpha.units.get("a1");
            unit.scriptSnapshots(ExecutionObservation.State.PENDING,
                ExecutionObservation.State.ACTIVE);
            var pendingView = nextState(engine, "a1",
                ExecutionObservation.State.PENDING);

            alpha.services.requestObservationRefresh(
                fence(alpha.id(), "a1", current));

            var pending = pendingView.get(2, TimeUnit.SECONDS);
            assertEquals(ExecutionObservation.State.PENDING,
                pending.engine().units().get(new ExecutionUnitKey("a1"))
                    .aggregateState());
            assertFalse(pending.engineDiagnostics().targetSatisfied());
            assertEquals(1, unit.scriptedSnapshotCalls.get());
        }
    }

    @Test
    void failedUnitDisableSaveKeepsCurrentAndAllowsTheSameFenceToRetry() {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1", "b1"));
            var target = engine.snapshot().target().orElseThrow();
            var attempt = engine.snapshot().current().orElseThrow().attemptId();
            var current = detail(engine, "a1");
            store.fail = true;

            alpha.services.requestDisable(disable(alpha.id(), "a1", current));
            flush(engine);

            assertEquals(target, engine.snapshot().target().orElseThrow());
            assertEquals(attempt,
                engine.snapshot().current().orElseThrow().attemptId());
            assertEquals(current, detail(engine, "a1"));
            assertTrue(engine.snapshot().target().orElseThrow().desiredGraph()
                .plugins().get("a1").enabled());
            assertTrue(engine.published().current().engineDiagnostics()
                .mutationGateOpen());
            assertNotNull(engine.published().current().engineDiagnostics().failure());

            store.fail = false;
            alpha.services.requestDisable(disable(alpha.id(), "a1", current));
            flush(engine);

            assertEquals(target.targetRevision() + 1,
                engine.snapshot().target().orElseThrow().targetRevision());
            assertFalse(engine.snapshot().target().orElseThrow().desiredGraph()
                .plugins().get("a1").enabled());
            assertFalse(engine.snapshot().units().containsKey(
                new ExecutionUnitKey("a1")));
        }
    }

    @Test
    void contextAToBToAIsDurableWhileUnaffectedUnitRemainsOwnedByItsOriginalGeneration() {
        try (var engine = engine(request -> { })) {
            var desired = graph(1, "a1");
            String firstDigest = null;
            String instance = null;
            for (int index = 0; index < 3; index++) {
                var value = index == 1 ? "b" : "a";
                var context = ConfigContextSnapshot.of(new LiteralValue.ObjectValue(Map.of("host", LiteralValue.of(value))));
                engine.submit(ApplyDeployment.builder(desired).expectedRevision(index)
                    .selections(List.of(alpha.metadata().selection(true), beta.metadata().selection(true)))
                    .configContext(context).build()).block();
                assertEquals(index + 1, engine.snapshot().target().orElseThrow().targetRevision());
                if (index == 0) {
                    firstDigest = engine.snapshot().target().orElseThrow().targetDigest();
                    instance = detail(engine, "a1").runtimeInstanceId();
                }
                assertEquals(instance, detail(engine, "a1").runtimeInstanceId());
                assertEquals(1, detail(engine, "a1").unitTargetRevision());
            }
            assertEquals(firstDigest, engine.snapshot().target().orElseThrow().targetDigest());
            assertEquals(3, store.saves);
        }
    }

    @Test
    void changedExpandedEdgesRebuildDependentsOnAddingAndRemovingSiblingEntry() {
        alpha.dependencies = List.of(new FacetDependency(new PluginId("b"), new FacetId("main")));
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1", "b1"));
            var original = detail(engine, "a1").runtimeInstanceId();
            apply(engine, 1, graph(1, "a1", "b1", "b2"));
            assertNotEquals(original, detail(engine, "a1").runtimeInstanceId());
            assertEquals(List.of(new ExecutionUnitKey("b1"), new ExecutionUnitKey("b2")),
                alpha.units.get("a1").plan.dependencies());
            var second = detail(engine, "a1").runtimeInstanceId();
            apply(engine, 2, graph(1, "a1", "b2"));
            assertNotEquals(second, detail(engine, "a1").runtimeInstanceId());
            assertEquals(List.of(new ExecutionUnitKey("b2")), alpha.units.get("a1").plan.dependencies());
            assertFalse(engine.snapshot().units().containsKey(new ExecutionUnitKey("b1")));
        }
    }

    @Test
    void packageGateSuppressesRawEnabledEntriesAndEnableRestoresThem() {
        try (var engine = engine(request -> { })) {
            var desired = graph(1, "a1", "a2");
            apply(engine, 0, desired);
            var disabled = ApplyDeployment.builder(desired).expectedRevision(1)
                .selections(List.of(alpha.metadata().selection(false), beta.metadata().selection(true)))
                .configContext(ConfigContextSnapshot.empty()).build();
            engine.submit(disabled).block();
            assertEquals(desired, engine.snapshot().target().orElseThrow().desiredGraph());
            assertTrue(engine.snapshot().units().isEmpty());
            apply(engine, 2, desired);
            assertEquals(2, engine.snapshot().units().size());
        }
    }

    @Test
    void contractFingerprintChangeRecompilesAllRuntimesWithoutSavingAnotherTarget() {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1", "b1"));
            var firstA = detail(engine, "a1").runtimeInstanceId();
            var firstB = detail(engine, "b1").runtimeInstanceId();
            alpha.contract = "v2";
            apply(engine, 1, graph(1, "a1", "b1"));
            assertEquals(1, store.saves);
            assertEquals(1, engine.snapshot().target().orElseThrow().targetRevision());
            assertNotEquals(firstA, detail(engine, "a1").runtimeInstanceId());
            assertNotEquals(firstB, detail(engine, "b1").runtimeInstanceId());
        }
    }

    @Test
    void failedCurrentUsesSameTokenButFreshUnitsAndCleansOldBeforeRetry() {
        alpha.activationFailure = true;
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1"));
            assertEquals(ExecutionObservation.State.FAILED, detail(engine, "a1").state());
            var previous = detail(engine, "a1").runtimeInstanceId();
            alpha.activationFailure = false;
            events.clear();
            engine.submit(new ReconcileCurrent()).block();
            assertEquals(1, store.saves);
            assertNotEquals(previous, detail(engine, "a1").runtimeInstanceId());
            assertTrue(events.indexOf("stop:a1") < events.indexOf("activate:a1"));
        }
    }

    @Test
    void saveUncertainDoesNotPromoteAndBlockingTerminationCannotBlockCommandLane() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var notifications = new AtomicInteger();
        var engine = engine(request -> {
            notifications.incrementAndGet();
            entered.countDown();
            try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        try {
            apply(engine, 0, graph(1, "a1"));
            var oldAttempt = engine.snapshot().current().orElseThrow().attemptId();
            store.uncertain = true;
            assertThrows(EngineChangeException.class, () -> apply(engine, 1, graph(2, "a1")));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(DurableTargetState.UNCERTAIN, engine.snapshot().durableState());
            assertEquals(oldAttempt, engine.snapshot().current().orElseThrow().attemptId());
            assertFalse(engine.published().current().engineDiagnostics().contributionAdmissionOpen());
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThrows(MutationGateClosedException.class, () -> engine.submit(new ReconcileCurrent()).block()));
            assertEquals(1, notifications.get());
        } finally { release.countDown(); engine.close(); }
    }

    @Test
    void throwingOrReentrantTerminationPortCannotReopenGateOrProduceAnotherRequest() throws Exception {
        for (boolean reentrant : List.of(false, true)) {
            var reference = new AtomicReference<FibraEngine>();
            var called = new CountDownLatch(1);
            var calls = new AtomicInteger();
            var packages = new PluginPackageStore(root.resolve("reentry-" + reentrant));
            var engine = FibraEngine.builder(packages, store).runtimeProvider(alpha).runtimeProvider(beta)
                .hostTerminationPort(request -> {
                    calls.incrementAndGet();
                    try {
                        if (reentrant) assertThrows(MutationGateClosedException.class,
                            () -> reference.get().submit(new ReconcileCurrent()).block(Duration.ofSeconds(1)));
                        else throw new IllegalStateException("port failure");
                    } finally { called.countDown(); }
                }).build();
            reference.set(engine);
            store.uncertain = true;
            engine.startAsync().block();
            try {
                assertThrows(EngineChangeException.class, () -> apply(engine, 0, graph(1, "a1")));
                assertTrue(called.await(2, TimeUnit.SECONDS));
                assertThrows(MutationGateClosedException.class, () -> engine.submit(new ReconcileCurrent()).block());
                assertEquals(1, calls.get());
            } finally { engine.close(); store.reset(); }
        }
    }

    @Test
    void abortFailureRetainsCandidateAndClosesBothGates() throws Exception {
        var notified = new CountDownLatch(1);
        alpha.abortFailure = true;
        beta.sealFailure = true;
        var engine = engine(request -> notified.countDown());
        try {
            assertThrows(EngineChangeException.class, () -> apply(engine, 0, graph(1, "a1", "b1")));
            assertTrue(notified.await(2, TimeUnit.SECONDS));
            assertTrue(engine.snapshot().candidate().isPresent());
            assertFalse(engine.published().current().engineDiagnostics().mutationGateOpen());
            assertFalse(engine.published().current().engineDiagnostics().contributionAdmissionOpen());
        } finally { alpha.abortFailure = false; engine.close(); }
    }

    @Test
    void providerConstructionFailureClosesAllAlreadyCreatedDriversInReverseOrder() {
        var packageRoot = root.resolve("providers");
        var targetStore = new Store();
        var capturedScope = new AtomicReference<com.sstlfsj.fibra.ScopeView>();
        var creationFailure = new IllegalStateException("creation failed");
        var third = new ProbeProvider("zeta", "z") {
            public RuntimeDriver create(RuntimeHostServices services) {
                capturedScope.set(services.scope());
                throw creationFailure;
            }
        };
        var thrown = assertThrows(IllegalStateException.class, () -> FibraEngine.builder(
            new PluginPackageStore(packageRoot), targetStore).runtimeProvider(alpha)
            .runtimeProvider(beta).runtimeProvider(third).hostTerminationPort(request -> { }).build());
        assertSame(creationFailure, thrown);
        assertEquals(List.of("driver-close:beta", "driver-close:alpha"), events);
        assertTrue(capturedScope.get().isClosed());
        assertConstructionStoresReleased(packageRoot, targetStore);
    }

    @Test
    void providerRegistryFailureClosesTransferredStores() {
        var packageRoot = root.resolve("duplicate-provider");
        var targetStore = new Store();

        assertThrows(IllegalArgumentException.class, () -> FibraEngine.builder(
                new PluginPackageStore(packageRoot), targetStore)
            .runtimeProvider(alpha).runtimeProvider(alpha)
            .hostTerminationPort(request -> { }).build());

        assertConstructionStoresReleased(packageRoot, targetStore);
        assertTrue(events.isEmpty());
    }

    @Test
    void providerMetadataFailurePreservesOriginalAndSuppressesStoreCloseFailure() {
        var packageRoot = root.resolve("provider-metadata");
        var targetStore = new Store();
        var metadataFailure = new IllegalStateException("metadata failed");
        var storeCloseFailure = new IllegalStateException("target store close failed");
        targetStore.closeFailure = storeCloseFailure;
        var provider = new ProbeProvider("metadata", "m") {
            public List<BuiltInPluginPackage> builtInPackages() {
                throw metadataFailure;
            }
        };

        var thrown = assertThrows(IllegalStateException.class, () -> FibraEngine.builder(
                new PluginPackageStore(packageRoot), targetStore)
            .runtimeProvider(provider).hostTerminationPort(request -> { }).build());

        assertSame(metadataFailure, thrown);
        assertTrue(Arrays.asList(thrown.getSuppressed()).contains(storeCloseFailure));
        assertConstructionStoresReleased(packageRoot, targetStore);
        assertTrue(events.isEmpty());
    }

    @Test
    void hostServicesFreezeAtStartAndAreInheritedByRuntimeScopes() {
        var key = com.sstlfsj.fibra.ServiceKey.of("host-message", String.class);
        var hostServices = new HostServiceRegistry();
        hostServices.register(key, "available");
        try (var engine = FibraEngine.builder(new PluginPackageStore(
                root.resolve("host-services")), store)
            .runtimeProvider(alpha).runtimeProvider(beta)
            .hostServices(hostServices)
            .hostTerminationPort(request -> { }).build()) {
            engine.startAsync().block();

            assertTrue(engine.published().current().diagnostics().services().stream()
                .anyMatch(service -> service.service().name().equals(key.name())
                    && service.service().type().equals(key.type().getName())));
            assertThrows(IllegalStateException.class, () -> hostServices.register(
                com.sstlfsj.fibra.ServiceKey.of("late", String.class), "late"));
            var child = alpha.services.scope().openChild("host-service-consumer");
            try {
                assertEquals("available",
                    child.context().services().require(key));
            } finally {
                child.close();
            }
        }
    }

    @Test
    void healthyReconcilePreservesInstanceAndOperationIdentity() {
        try (var engine = engine(request -> fail("healthy reconcile must not terminate Host"))) {
            apply(engine, 0, graph(1, "a1"));
            var before = detail(engine, "a1");
            var view = engine.published().current().viewRevision();
            events.clear();
            engine.submit(new ReconcileCurrent()).block();
            assertEquals(before, detail(engine, "a1"));
            assertEquals(view, engine.published().current().viewRevision());
            assertTrue(events.isEmpty());
            assertTrue(engine.published().current().engineDiagnostics().mutationGateOpen());
        }
    }

    @Test
    void availabilityWakeupStartsPendingDependencyBeforeItsDependent() {
        beta.dependencies = List.of(new FacetDependency(new PluginId("a"), new FacetId("main")));
        alpha.pending = true;
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1", "b1"));
            assertEquals(ExecutionObservation.State.PENDING, detail(engine, "a1").state());
            assertEquals(ExecutionObservation.State.PENDING, detail(engine, "b1").state());
            assertFalse(events.contains("activate:b1"));
            var instance = detail(engine, "a1").runtimeInstanceId();
            alpha.pending = false;
            events.clear();
            alpha.services.requestReconcile(Set.of(fence(alpha.id(), "a1",
                detail(engine, "a1"))), "online");
            engine.submit(new ReconcileCurrent()).block();
            assertEquals(List.of("activate:a1", "activate:b1"), events);
            assertEquals(instance, detail(engine, "a1").runtimeInstanceId());
            assertEquals(1, store.saves);
        }
    }

    @Test
    void failedExecutionWakeupReplacesTheUnitInsteadOfReconcilingItInPlace() {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1"));
            var failed = alpha.units.get("a1");
            var previousInstance = failed.instance;
            failed.observed = failed.observation("sidecar-exited",
                ExecutionObservation.State.FAILED);
            events.clear();

            alpha.services.requestReconcile(Set.of(fence(alpha.id(), "a1",
                detail(engine, "a1"))), "execution-exited");
            engine.submit(new ReconcileCurrent()).block();

            assertNotEquals(previousInstance,
                detail(engine, "a1").runtimeInstanceId());
            assertTrue(events.indexOf("admission:a1")
                < events.indexOf("stop:a1"));
            assertTrue(events.indexOf("stop:a1")
                < events.indexOf("activate:a1"));
            assertEquals(1, store.saves);
        }
    }

    @Test
    void batchedReconcileReplacesFailedClosureOnceAndThenWakesIndependentPendingUnit() {
        beta.dependencies = List.of(new FacetDependency(new PluginId("a"),
            new FacetId("main")));
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1", "a2", "b1"));
            var failed = detail(engine, "a1");
            var pending = detail(engine, "a2");
            var dependent = detail(engine, "b1");
            alpha.units.get("a1").observe("execution-exited",
                ExecutionObservation.State.FAILED);
            alpha.units.get("a2").observe("dependency-lost",
                ExecutionObservation.State.PENDING);
            events.clear();

            alpha.services.requestReconcile(Set.of(
                fence(alpha.id(), "a1", failed),
                fence(alpha.id(), "a2", pending),
                fence(beta.id(), "b1", dependent),
                RuntimeUnitFence.builder(alpha.id(), new ExecutionUnitKey("a1"))
                    .unitTargetRevision(failed.unitTargetRevision())
                    .runtimeInstanceId(failed.runtimeInstanceId() + ":stale")
                    .build()), "batched-runtime-callback");
            flush(engine);

            assertNotEquals(failed.runtimeInstanceId(),
                detail(engine, "a1").runtimeInstanceId());
            assertNotEquals(dependent.runtimeInstanceId(),
                detail(engine, "b1").runtimeInstanceId());
            assertEquals(pending.runtimeInstanceId(),
                detail(engine, "a2").runtimeInstanceId());
            assertEquals(ExecutionObservation.State.ACTIVE,
                detail(engine, "a2").state());
            assertEquals(ExecutionObservation.State.ACTIVE,
                detail(engine, "a1").state());
            assertEquals(ExecutionObservation.State.ACTIVE,
                detail(engine, "b1").state());
            assertEquals(1, events.stream().filter("stop:a1"::equals).count());
            assertEquals(1, events.stream().filter("stop:b1"::equals).count());
            assertEquals(1, events.stream().filter("activate:a1"::equals).count());
            assertEquals(1, events.stream().filter("activate:b1"::equals).count());
            assertEquals(1, events.stream().filter("activate:a2"::equals).count());
        }
    }

    @Test
    void planAffectingCallbackRecompilesAllUnitsWithSameDurableToken() {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1", "b1"));
            var first = detail(engine, "b1").runtimeInstanceId();
            alpha.contract = "changed";
            alpha.services.requestRecompile(new RuntimeRecompileReason(alpha.id(), "contract", "new contract"));
            engine.submit(new ReconcileCurrent()).block();
            assertNotEquals(first, detail(engine, "b1").runtimeInstanceId());
            assertEquals(1, engine.snapshot().target().orElseThrow().targetRevision());
            assertEquals(1, store.saves);
        }
    }

    @Test
    void builtInDeclarationDriftChangesFingerprintEvenWhenContractAndDigestStayTheSame() {
        try (var engine = engine(request -> { }, () ->
            HostCapabilitySnapshot.of(Map.of("added-capability", true)))) {
            apply(engine, 0, graph(1, "a1", "b1"));
            var original = engine.snapshot().current().orElseThrow().compiledFingerprint();
            var betaInstance = detail(engine, "b1").runtimeInstanceId();
            alpha.requiredCapabilities = Set.of("added-capability");
            apply(engine, 1, graph(1, "a1", "b1"));
            assertNotEquals(original, engine.snapshot().current().orElseThrow().compiledFingerprint());
            assertNotEquals(betaInstance, detail(engine, "b1").runtimeInstanceId());
            assertEquals(1, store.saves);
        }
    }

    @Test
    void activeUnitRequiresItsFacetCapabilitiesBeforeAnyCandidateSideEffect() {
        alpha.requiredCapabilities = Set.of("storage");
        try (var engine = engine(request -> { })) {
            events.clear();

            assertThrows(EngineChangeException.class,
                () -> apply(engine, 0, graph(1, "a1")));

            assertEquals(0, store.saves);
            assertTrue(events.isEmpty());
            assertTrue(engine.snapshot().current().isEmpty());
        }
    }

    @Test
    void capabilityChangesAreFrozenPerCommittedGeneration() {
        alpha.requiredCapabilities = Set.of("storage");
        var source = new AtomicReference<>(HostCapabilitySnapshot.of(
            Map.of("storage", true)));
        try (var engine = engine(request -> { }, source::get)) {
            apply(engine, 0, graph(1, "a1"));
            var previous = detail(engine, "a1");
            assertEquals(Set.of("storage"), previous.capabilities());

            source.set(HostCapabilitySnapshot.empty());
            assertThrows(EngineChangeException.class,
                () -> apply(engine, 1, graph(1, "a1")));
            var preserved = detail(engine, "a1");
            assertEquals(previous.runtimeInstanceId(),
                preserved.runtimeInstanceId());
            assertEquals(Set.of("storage"), preserved.capabilities());
            assertEquals(1, store.saves);

            source.set(HostCapabilitySnapshot.of(Map.of(
                "storage", true, "gpu", "available")));
            apply(engine, 1, graph(1, "a1"));
            var replaced = detail(engine, "a1");
            assertNotEquals(previous.runtimeInstanceId(),
                replaced.runtimeInstanceId());
            assertEquals(Set.of("storage", "gpu"),
                replaced.capabilities());
            assertEquals(1, store.saves);
        }
    }

    @Test
    void prepareAndPlanFailureNeverReachSaveAndCloseEveryCandidate() {
        try (var engine = engine(request -> fail("clean preparation failure is recoverable"))) {
            beta.prepareFailure = true;
            assertThrows(EngineChangeException.class, () -> apply(engine, 0, graph(1, "a1", "b1")));
            assertEquals(0, store.saves);
            assertTrue(events.containsAll(List.of("close:alpha", "close:beta")));
            beta.prepareFailure = false;
            beta.omitUnit = true;
            events.clear();
            assertThrows(EngineChangeException.class, () -> apply(engine, 0, graph(1, "a1", "b1")));
            assertEquals(0, store.saves);
            assertFalse(events.stream().anyMatch(value -> value.startsWith("seal:")));
            assertTrue(events.containsAll(List.of("close:alpha", "close:beta")));
        }
    }

    @Test
    void drainStopAndRetireFailureKeepNewTargetAndRetiringOwnership() throws Exception {
        for (var failure : List.of("drain", "stop", "retire")) {
            var notified = new CountDownLatch(1);
            var engine = engine(request -> notified.countDown());
            try {
                apply(engine, 0, graph(1, "a1"));
                alpha.lifecycleFailure = failure;
                events.clear();
                assertThrows(EngineChangeException.class, () -> apply(engine, 1, graph(2, "a1")));
                assertTrue(notified.await(2, TimeUnit.SECONDS));
                assertEquals(2, engine.snapshot().target().orElseThrow().targetRevision());
                assertFalse(engine.snapshot().retiring().isEmpty());
                assertFalse(engine.published().current().engineDiagnostics().mutationGateOpen());
                if (!failure.equals("retire")) assertFalse(events.contains("activate:a1"));
                if (failure.equals("drain")) assertFalse(events.contains("stop:a1"));
            } finally {
                assertThrows(IllegalStateException.class, engine::close);
                alpha.lifecycleFailure = null;
                store.reset();
            }
        }
    }

    @Test
    void drainTimeoutDoesNotStopOrRetireStillLeasedUnit() throws Exception {
        var notified = new CountDownLatch(1);
        var engine = FibraEngine.builder(new PluginPackageStore(root.resolve("timeout")), store)
            .runtimeProvider(alpha).runtimeProvider(beta).hostTerminationPort(request -> notified.countDown())
            .lifecycleTimeout(Duration.ofMillis(30)).build();
        engine.startAsync().block();
        try {
            apply(engine, 0, graph(1, "a1"));
            alpha.lifecycleFailure = "timeout";
            events.clear();
            assertThrows(EngineChangeException.class, () -> apply(engine, 1, graph(2, "a1")));
            assertTrue(notified.await(2, TimeUnit.SECONDS));
            assertFalse(events.contains("stop:a1"));
            assertFalse(events.contains("retire:alpha"));
            assertFalse(events.contains("activate:a1"));
        } finally { assertThrows(IllegalStateException.class, engine::close); }
    }

    @Test
    void restartRebuildsOnlyDurableTargetAndMissingProviderLeavesPresentWithoutCurrent() {
        try (var first = engine(request -> { })) { apply(first, 0, graph(1, "a1")); }
        store.reopen();
        var original = alpha.units.get("a1").instance;
        try (var second = engine(request -> { })) {
            assertEquals(1, store.saves);
            assertNotEquals(original, detail(second, "a1").runtimeInstanceId());
            assertEquals(1, second.snapshot().target().orElseThrow().targetRevision());
        }
        store.reopen();
        var broken = FibraEngine.builder(new PluginPackageStore(root.resolve("missing-provider")), store)
            .runtimeProvider(beta).hostTerminationPort(request -> { }).build();
        try {
            assertThrows(EngineChangeException.class, () -> broken.startAsync().block());
            assertEquals(DurableTargetState.PRESENT, broken.snapshot().durableState());
            assertTrue(broken.snapshot().current().isEmpty());
            assertEquals(1, store.saves);
            assertTrue(broken.published().current().engineDiagnostics().mutationGateOpen());
        } finally { broken.close(); }
    }

    @Test
    void snapshotContractViolationStillPublishesFatalAndNotifiesHostOnce() throws Exception {
        var notifications = new AtomicInteger();
        var notified = new CountDownLatch(1);
        try (var engine = engine(request -> { notifications.incrementAndGet(); notified.countDown(); })) {
            apply(engine, 0, graph(1, "a1"));
            var previous = detail(engine, "a1");
            alpha.snapshotFailure = true;
            assertThrows(IllegalStateException.class, () -> engine.submit(new ReconcileCurrent()).block());
            assertTrue(notified.await(2, TimeUnit.SECONDS));
            assertEquals(previous, detail(engine, "a1"), "last proven observation remains available");
            assertFalse(engine.published().current().engineDiagnostics().mutationGateOpen());
            assertEquals(1, notifications.get());
            alpha.snapshotFailure = false;
        }
    }

    @Test
    void storeReturningADifferentDurableTokenIsUncertainAndNeverPromotes() {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1"));
            var attempt = engine.snapshot().current().orElseThrow().attemptId();
            store.wrongToken = true;
            assertThrows(EngineChangeException.class, () -> apply(engine, 1, graph(2, "a1")));
            assertEquals(DurableTargetState.UNCERTAIN, engine.snapshot().durableState());
            assertEquals(attempt, engine.snapshot().current().orElseThrow().attemptId());
            assertFalse(engine.published().current().engineDiagnostics().mutationGateOpen());
        }
    }

    private FibraEngine engine(HostTerminationPort port) {
        return engine(port, HostCapabilitySnapshot::empty);
    }

    private FibraEngine engine(HostTerminationPort port,
                               Supplier<HostCapabilitySnapshot> capabilities) {
        var engine = FibraEngine.builder(new PluginPackageStore(root.resolve(UUID.randomUUID().toString())), store)
            .runtimeProvider(alpha).runtimeProvider(beta).hostTerminationPort(port)
            .capabilities(capabilities).build();
        engine.startAsync().block();
        return engine;
    }
    private PublishedView apply(FibraEngine engine, long expected, DesiredInputGraph graph) {
        return engine.submit(ApplyDeployment.builder(graph).expectedRevision(expected)
            .selections(List.of(alpha.metadata().selection(true), beta.metadata().selection(true)))
            .configContext(ConfigContextSnapshot.empty()).build()).block().view();
    }
    private static DesiredInputGraph graph(int config, String... ids) {
        return new DesiredInputGraph(Arrays.stream(ids).map(id -> entry(id, id.substring(0, 1), config))
            .map(DesiredInputNode.class::cast).toList());
    }
    private static DesiredInputEntry entry(String id, String plugin, int config) {
        return DesiredInputEntry.builder(id, new PluginDefinitionRef(plugin, "main", "definition"))
            .config(LiteralValue.of(config)).build();
    }
    private static ExecutionObservation.Detail detail(FibraEngine engine, String key) {
        return engine.snapshot().units().get(new ExecutionUnitKey(key)).executions().getFirst();
    }
    private static RuntimeUnitDisableRequest disable(RuntimeId runtimeId,
                                                     String key,
                                                     ExecutionObservation.Detail detail) {
        return RuntimeUnitDisableRequest.of(fence(runtimeId, key, detail),
            "test-disable");
    }
    private static RuntimeUnitFence fence(RuntimeId runtimeId, String key,
                                          ExecutionObservation.Detail detail) {
        return RuntimeUnitFence.builder(runtimeId, new ExecutionUnitKey(key))
            .unitTargetRevision(detail.unitTargetRevision())
            .runtimeInstanceId(detail.runtimeInstanceId()).build();
    }
    private static CompletableFuture<PublishedView> nextState(
        FibraEngine engine, String key, ExecutionObservation.State state) {
        return engine.published().views().filter(view -> {
            var observation = view.engine().units().get(new ExecutionUnitKey(key));
            return observation != null && observation.aggregateState() == state;
        }).next().toFuture();
    }
    private static ExecutionObservation.Detail detail(PublishedView view,
                                                       String key) {
        return view.engine().units().get(new ExecutionUnitKey(key))
            .executions().getFirst();
    }
    private static void flush(FibraEngine engine) {
        engine.submit(new ReconcileCurrent()).block();
    }

    private static void assertConstructionStoresReleased(Path packageRoot,
                                                          Store targetStore) {
        assertEquals(1, targetStore.closes);
        assertThrows(IllegalStateException.class, targetStore::load);
        try (var reopened = new PluginPackageStore(packageRoot)) {
            assertNotNull(reopened);
        }
    }

    private class Store implements DeploymentTargetStore {
        DeploymentTarget current;
        boolean closed;
        boolean fail;
        boolean uncertain;
        boolean wrongToken;
        RuntimeException closeFailure;
        int saves;
        int closes;
        public Optional<StoredTarget> load() {
            ensureOpen();
            return Optional.ofNullable(current).map(StoredTarget::confirmed);
        }
        public DurableTargetToken save(long expected, DeploymentTarget target) {
            ensureOpen();
            events.add("save:" + target.targetRevision());
            saves++;
            if (fail) throw new IllegalStateException("save failed");
            if (uncertain) throw new SaveUnconfirmedException(root, new IllegalStateException("fsync uncertain"));
            if (wrongToken) return StoredTarget.confirmed(
                Objects.requireNonNull(current, "current")).token();
            DeploymentTargetStore.checkRevision(expected,
                current == null ? 0 : current.targetRevision(), target);
            current = target;
            return StoredTarget.confirmed(target).token();
        }
        public void close() {
            closes++;
            closed = true;
            if (closeFailure != null) throw closeFailure;
        }
        void reopen() { closed = false; }
        void reset() { current = null; closed = false; }
        private void ensureOpen() {
            if (closed) throw new IllegalStateException(
                "deployment target store is closed");
        }
    }

    private class ProbeProvider implements RuntimeProvider {
        final RuntimeId runtimeId;
        final String plugin;
        List<FacetDependency> dependencies = List.of();
        Set<String> requiredCapabilities = Set.of();
        final Map<String, ProbeUnit> units = new ConcurrentHashMap<>();
        final AtomicInteger retired = new AtomicInteger();
        String contract = "v1";
        boolean sealFailure;
        boolean abortFailure;
        boolean activationFailure;
        boolean pending;
        boolean prepareFailure;
        boolean omitUnit;
        boolean snapshotFailure;
        String lifecycleFailure;
        volatile Sinks.One<Void> activationEntered;
        volatile Sinks.One<Void> activationRelease;
        RuntimeHostServices services;
        ProbeProvider(String runtime, String plugin) { runtimeId = new RuntimeId(runtime); this.plugin = plugin; }
        public RuntimeId id() { return runtimeId; }
        public String contractIdentity() { return contract; }
        public List<BuiltInPluginPackage> builtInPackages() { return List.of(metadata()); }
        BuiltInPluginPackage metadata() {
            return BuiltInPluginPackage.builder().pluginId(new PluginId(plugin)).version("1")
                .packageDigest(plugin.equals("a") ? "a".repeat(64) : "b".repeat(64))
                .facets(List.of(BuiltInFacet.builder(new FacetId("main"), runtimeId, new ExecutionTarget("host"))
                    .definitionIds(Set.of("definition")).dependencies(dependencies)
                    .requiredCapabilities(requiredCapabilities).build())).build();
        }
        public RuntimeDriver create(RuntimeHostServices services) {
            this.services = services;
            return new RuntimeDriver() {
                public RuntimeId id() { return runtimeId; }
                public Mono<RuntimeArtifactInspection> probe(PluginFacetSource source) { return Mono.error(new UnsupportedOperationException()); }
                public Mono<RuntimeArtifactInspection> inspect(ManagedFacet facet) { return Mono.error(new UnsupportedOperationException()); }
                public RuntimeDriverSnapshot snapshot() { return new RuntimeDriverSnapshot(runtimeId, Map.of()); }
                public Mono<Void> closeAsync() { return Mono.fromRunnable(() -> events.add("driver-close:" + runtimeId.value())); }
                public RuntimeCandidate createCandidate(RuntimeTargetSlice slice) {
                    return new RuntimeCandidate() {
                        RuntimePlan plan;
                        public Mono<Void> prepareAsync() {
                            return Mono.fromRunnable(() -> {
                                events.add("prepare:" + runtimeId.value());
                                if (prepareFailure) throw new IllegalStateException("prepare failed");
                                var plans = new ArrayList<ExecutionUnitPlan>();
                                var bindings = new ArrayList<DefinitionBindingPlan>();
                                slice.affectedEntryIds().stream().sorted().forEach(id -> {
                                    if (omitUnit) return;
                                    var key = new ExecutionUnitKey(id);
                                    var entry = (DesiredInputEntry) slice.desired().require(id).input();
                                    plans.add(ExecutionUnitPlan.builder(key, runtimeId, new ExecutionTarget("host"))
                                        .artifactId(metadata().artifactId(new FacetId("main")))
                                        .provenance(plugin, "main", metadata().packageDigest())
                                        .dependencies(slice.unitDependencies().get(key)).build());
                                    bindings.add(DefinitionBindingPlan.builder(entry.definitionRef(), id).unitKey(key)
                                        .publicationRequirement(entry.publicationRequirement()).build());
                                });
                                plan = RuntimePlan.of(runtimeId, plans, bindings);
                            });
                        }
                        public RuntimePlan preparedPlan() { return plan; }
                        public PreparedRuntimeGeneration seal(CompiledRuntimeSlice compiled) {
                            events.add("seal:" + runtimeId.value());
                            if (sealFailure) throw new IllegalStateException("seal failed");
                            var created = new LinkedHashMap<ExecutionUnitKey, RuntimeUnitGeneration>();
                            plan.units().forEach((key, value) -> created.put(key, new ProbeUnit(value,
                                slice.target().targetRevision(), services.nextIdentity("unit"),
                                slice.capabilities().availableNames())));
                            return new PreparedRuntimeGeneration() {
                                public Map<ExecutionUnitKey, RuntimeUnitGeneration> units() { return created; }
                                public Mono<Void> abortAsync() {
                                    return Mono.fromRunnable(() -> {
                                        events.add("abort:" + runtimeId.value());
                                        if (abortFailure) throw new IllegalStateException("abort failed");
                                    });
                                }
                                public Mono<Void> retireAsync() {
                                    return Mono.fromRunnable(() -> {
                                        events.add("retire:" + runtimeId.value());
                                        if ("retire".equals(lifecycleFailure)) throw new IllegalStateException("retire failed");
                                        retired.incrementAndGet();
                                    });
                                }
                            };
                        }
                        public Mono<Void> closeAsync() { return Mono.fromRunnable(() -> events.add("close:" + runtimeId.value())); }
                    };
                }
            };
        }

        private final class ProbeUnit implements RuntimeUnitGeneration {
            final ExecutionUnitPlan plan;
            final long revision;
            final String instance;
            final Set<String> capabilities;
            volatile ExecutionObservation observed;
            final AtomicInteger scriptedSnapshotCalls = new AtomicInteger();
            private final Deque<ExecutionObservation.State> scriptedSnapshots =
                new ArrayDeque<>();
            ProbeUnit(ExecutionUnitPlan plan, long revision, String instance,
                      Set<String> capabilities) {
                this.plan = plan; this.revision = revision; this.instance = instance;
                this.capabilities = Set.copyOf(capabilities);
                observed = observation("prepared", ExecutionObservation.State.PENDING);
            }
            public ExecutionUnitPlan plan() { return plan; }
            public Mono<ExecutionObservation> reconcileAsync(String operation) {
                var activation = Mono.fromSupplier(() -> {
                    events.add("activate:" + plan.key().value());
                    units.put(plan.key().value(), this);
                    return observed = observation(operation, activationFailure ? ExecutionObservation.State.FAILED
                        : pending ? ExecutionObservation.State.PENDING : ExecutionObservation.State.ACTIVE);
                });
                var release = activationRelease;
                if (release == null) return activation;
                activationRelease = null;
                activationEntered.tryEmitEmpty();
                return release.asMono().then(activation);
            }
            public void closeAdmission() { events.add("admission:" + plan.key().value()); }
            public Mono<ExecutionObservation> drainAsync(String operation, Instant deadline) {
                if ("timeout".equals(lifecycleFailure)) return Mono.never();
                return Mono.fromSupplier(() -> {
                    events.add("drain:" + plan.key().value());
                    if ("drain".equals(lifecycleFailure)) throw new IllegalStateException("drain failed");
                    return observation(operation, observed.aggregateState());
                });
            }
            public Mono<ExecutionObservation> stopAsync(String operation, Instant deadline) {
                return Mono.fromSupplier(() -> {
                    events.add("stop:" + plan.key().value());
                    if ("stop".equals(lifecycleFailure)) throw new IllegalStateException("stop failed");
                    return observed = observation(operation, ExecutionObservation.State.PENDING);
                });
            }
            public ExecutionObservation snapshot() {
                if (snapshotFailure) throw new IllegalStateException("snapshot contract violated");
                synchronized (scriptedSnapshots) {
                    if (!scriptedSnapshots.isEmpty()) {
                        var state = scriptedSnapshots.removeFirst();
                        return observation("scripted-" +
                            scriptedSnapshotCalls.incrementAndGet(), state);
                    }
                }
                return observed;
            }
            void observe(String operation, ExecutionObservation.State state) {
                observed = observation(operation, state);
            }
            void scriptSnapshots(ExecutionObservation.State... states) {
                synchronized (scriptedSnapshots) {
                    scriptedSnapshots.clear();
                    scriptedSnapshots.addAll(List.of(states));
                    scriptedSnapshotCalls.set(0);
                }
            }
            private ExecutionObservation observation(String operation, ExecutionObservation.State state) {
                var builder = ExecutionObservation.Detail.builder().unitTargetRevision(revision)
                    .executionId(plan.key().value()).runtimeInstanceId(instance)
                    .lifecycleOperationId(operation).capabilities(capabilities)
                    .state(state);
                if (state == ExecutionObservation.State.FAILED) builder.failure(new ExecutionObservation.Failure("START_FAILED", "start failed", Map.of()));
                return ExecutionObservation.of(plan.pluginId(), plan.facetId(), runtimeId, plan.executionTarget(), List.of(builder.build()));
            }
        }
    }
}
