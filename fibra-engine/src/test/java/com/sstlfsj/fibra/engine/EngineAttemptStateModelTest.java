package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ExecutionTarget;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.config.PublicationRequirement;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * candidate、current 与 retirement batch 是三个并存的状态所有者；这些测试刻意观察
 * 事务中间态，防止它们再次被压成一个 Engine 全局 phase。
 */
class EngineAttemptStateModelTest {
    private static final ExecutionUnitKey UNIT = new ExecutionUnitKey("a1");

    @TempDir Path root;
    private final Store store = new Store();
    private final ProbeProvider provider = new ProbeProvider();

    @Test
    void candidatePreparingAndSettledCurrentAreIndependentSnapshots() throws Exception {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, 1);
            var currentId = engine.snapshot().current().orElseThrow().attemptId();
            provider.blockPrepare();

            var replacement = applyAsync(engine, 1, 2);
            provider.awaitPrepare();
            try {
                var snapshot = engine.snapshot();
                CandidateAttemptSnapshot candidate = snapshot.candidate().orElseThrow();
                CurrentAttemptSnapshot current = snapshot.current().orElseThrow();

                assertEquals(CandidatePhase.PREPARING, candidate.phase());
                assertEquals(currentId, current.attemptId());
                assertEquals(CurrentPhase.SETTLED, current.phase());
                assertTrue(current.observations().containsKey(UNIT));
                assertEquals(TargetConvergence.SATISFIED,
                    snapshot.targetConvergence());
                assertNoLegacyTopLevelObservationMaps();
            } finally {
                provider.releasePrepare();
            }
            replacement.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void promotionPreservesCandidateIdentityAndNamesRetirementSource() throws Exception {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, 1);
            var previousId = engine.snapshot().current().orElseThrow().attemptId();
            provider.blockPrepare();
            provider.blockDrain();

            var replacement = applyAsync(engine, 1, 2);
            provider.awaitPrepare();
            var candidateId = engine.snapshot().candidate().orElseThrow().attemptId();
            provider.releasePrepare();
            provider.awaitDrain();
            try {
                var snapshot = engine.snapshot();
                CurrentAttemptSnapshot current = snapshot.current().orElseThrow();
                RetirementBatchSnapshot retirement = snapshot.retirementBatch()
                    .orElseThrow();

                assertTrue(snapshot.candidate().isEmpty());
                assertEquals(candidateId, current.attemptId());
                assertNotEquals(previousId, current.attemptId());
                assertEquals(previousId, retirement.sourceAttemptId());
                assertNotEquals(previousId, retirement.batchId());
                assertEquals(CurrentPhase.WAITING_FOR_RETIREMENT,
                    current.phase());
                assertEquals(RetirementPhase.DRAINING, retirement.phase());
                assertTrue(current.observations().containsKey(UNIT));
                assertTrue(retirement.observations().containsKey(UNIT));
            } finally {
                provider.releaseDrain();
            }
            replacement.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void retirementStoppingDoesNotOverwriteCurrentPhase() throws Exception {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, 1);
            provider.blockStop();

            var replacement = applyAsync(engine, 1, 2);
            provider.awaitStop();
            try {
                var snapshot = engine.snapshot();
                assertEquals(CurrentPhase.WAITING_FOR_RETIREMENT,
                    snapshot.current().orElseThrow().phase());
                assertEquals(RetirementPhase.STOPPING,
                    snapshot.retirementBatch().orElseThrow().phase());
            } finally {
                provider.releaseStop();
            }
            replacement.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void retirementFailureBlocksCurrentWithoutRelabelingItFailed()
        throws Exception {
        var notified = new CountDownLatch(1);
        var engine = engine(request -> notified.countDown());
        try {
            apply(engine, 0, 1);
            provider.lifecycleFailure = "drain";

            assertThrows(EngineChangeException.class,
                () -> apply(engine, 1, 2));
            assertTrue(notified.await(2, TimeUnit.SECONDS));

            var snapshot = engine.snapshot();
            assertEquals(EngineState.FAIL_STOP, snapshot.state());
            assertEquals(CurrentPhase.BLOCKED,
                snapshot.current().orElseThrow().phase());
            assertEquals(RetirementPhase.FAILED,
                snapshot.retirementBatch().orElseThrow().phase());
            var subject = assertInstanceOf(FailureSubject.Retirement.class,
                engine.published().current().engineDiagnostics().failure()
                    .orElseThrow().subject());
            assertEquals(snapshot.retirementBatch().orElseThrow().batchId(),
                subject.batchId());
            assertEquals(snapshot.retirementBatch().orElseThrow()
                .sourceAttemptId(), subject.sourceAttemptId());
            assertGatesClosed(engine);
        } finally {
            closeAfterFatal(engine);
        }
    }

    @Test
    void currentSnapshotFailureWhileCandidateExistsMarksOnlyCurrent()
        throws Exception {
        var notifications = new AtomicInteger();
        var notified = new CountDownLatch(1);
        var engine = engine(request -> {
            notifications.incrementAndGet();
            notified.countDown();
        });
        try {
            apply(engine, 0, 1);
            var current = engine.snapshot().current().orElseThrow()
                .observations().get(UNIT).executions().getFirst();
            provider.blockPrepare();

            var replacement = applyAsync(engine, 1, 2);
            provider.awaitPrepare();
            try {
                provider.snapshotFailure = true;
                provider.services.requestObservationRefresh(
                    RuntimeUnitFence.builder(provider.runtimeId, UNIT)
                        .unitTargetRevision(current.unitTargetRevision())
                        .runtimeInstanceId(current.runtimeInstanceId())
                        .build());
                assertTrue(notified.await(2, TimeUnit.SECONDS));
                var snapshot = engine.snapshot();
                assertEquals(EngineState.FAIL_STOP, snapshot.state());
                assertEquals(CurrentPhase.FAILED,
                    snapshot.current().orElseThrow().phase());
                assertEquals(CandidatePhase.PREPARING,
                    snapshot.candidate().orElseThrow().phase());
                assertTrue(snapshot.retirementBatch().isEmpty());
                var subject = assertInstanceOf(FailureSubject.Unit.class,
                    engine.published().current().engineDiagnostics().failure()
                        .orElseThrow().subject());
                assertEquals(snapshot.current().orElseThrow().attemptId(),
                    subject.ownerId());
                assertEquals(current.runtimeInstanceId(),
                    subject.fence().runtimeInstanceId());
                assertGatesClosed(engine);
                assertEquals(1, notifications.get());
            } finally {
                provider.snapshotFailure = false;
                provider.releasePrepare();
            }
            assertThrows(java.util.concurrent.ExecutionException.class,
                () -> replacement.get(2, TimeUnit.SECONDS));
        } finally {
            provider.snapshotFailure = false;
            closeAfterFatal(engine);
        }
    }

    @Test
    void retirementSnapshotFailureBelongsToRetirementBatch() throws Exception {
        var notified = new CountDownLatch(1);
        var engine = engine(request -> notified.countDown());
        try {
            apply(engine, 0, 1);
            provider.blockDrain();

            var replacement = applyAsync(engine, 1, 2);
            provider.awaitDrain();
            var retirement = engine.snapshot().retirementBatch().orElseThrow();
            provider.snapshotFailure = true;
            provider.releaseDrain();

            assertThrows(java.util.concurrent.ExecutionException.class,
                () -> replacement.get(2, TimeUnit.SECONDS));
            assertTrue(notified.await(2, TimeUnit.SECONDS));

            var snapshot = engine.snapshot();
            assertEquals(EngineState.FAIL_STOP, snapshot.state());
            assertEquals(CurrentPhase.BLOCKED,
                snapshot.current().orElseThrow().phase());
            assertEquals(RetirementPhase.FAILED,
                snapshot.retirementBatch().orElseThrow().phase());
            var subject = assertInstanceOf(FailureSubject.Unit.class,
                engine.published().current().engineDiagnostics().failure()
                    .orElseThrow().subject());
            assertEquals(retirement.batchId(), subject.ownerId());
            assertEquals(1, subject.fence().unitTargetRevision());
            assertGatesClosed(engine);
        } finally {
            provider.snapshotFailure = false;
            closeAfterFatal(engine);
        }
    }

    @Test
    void explicitSaveFailureRemovesCandidateAndPreservesCurrentAndGates() {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, 1);
            var current = engine.snapshot().current().orElseThrow();
            store.fail = true;

            assertThrows(EngineChangeException.class,
                () -> apply(engine, 1, 2));

            var snapshot = engine.snapshot();
            assertEquals(EngineState.RUNNING, snapshot.state());
            assertEquals(current.attemptId(),
                snapshot.current().orElseThrow().attemptId());
            assertEquals(CurrentPhase.SETTLED,
                snapshot.current().orElseThrow().phase());
            assertTrue(snapshot.candidate().isEmpty());
            assertTrue(snapshot.retirementBatch().isEmpty());
            assertEquals(DurableTargetState.PRESENT, snapshot.durableState());
            assertEquals(TargetConvergence.SATISFIED,
                snapshot.targetConvergence());
            assertGatesOpen(engine);
        }
    }

    @Test
    void saveUnconfirmedEntersFailStopWithoutPromotingCandidate()
        throws Exception {
        var notified = new CountDownLatch(1);
        var notification = new AtomicReference<HostTerminationRequest>();
        var engine = engine(request -> {
            notification.set(request);
            notified.countDown();
        });
        try {
            apply(engine, 0, 1);
            var currentId = engine.snapshot().current().orElseThrow().attemptId();
            store.uncertain = true;

            assertThrows(EngineChangeException.class,
                () -> apply(engine, 1, 2));
            assertTrue(notified.await(2, TimeUnit.SECONDS));

            var snapshot = engine.snapshot();
            assertEquals(EngineState.FAIL_STOP, snapshot.state());
            assertEquals(DurableTargetState.UNCERTAIN, snapshot.durableState());
            assertEquals(TargetConvergence.BLOCKED,
                snapshot.targetConvergence());
            assertEquals(currentId,
                snapshot.current().orElseThrow().attemptId());
            assertEquals(CurrentPhase.SETTLED,
                snapshot.current().orElseThrow().phase());
            assertEquals(CandidatePhase.FAILED,
                snapshot.candidate().orElseThrow().phase());
            assertTrue(snapshot.retirementBatch().isEmpty());
            var fact = engine.published().current().engineDiagnostics()
                .failure().orElseThrow();
            var subject = assertInstanceOf(FailureSubject.Candidate.class,
                fact.subject());
            assertEquals(snapshot.candidate().orElseThrow().attemptId(),
                subject.attemptId());
            assertEquals(fact.subject(), notification.get().subject());
            assertEquals(fact.operationStage(),
                notification.get().operationStage());
            assertEquals(fact.targetRevision(),
                notification.get().targetRevision());
            assertGatesClosed(engine);
        } finally {
            closeAfterFatal(engine);
        }
    }

    @Test
    void synchronousReconcileThrowBelongsToCurrentAndClosesBothGates()
        throws Exception {
        var notifications = new AtomicInteger();
        var notified = new CountDownLatch(1);
        var engine = engine(request -> {
            notifications.incrementAndGet();
            notified.countDown();
        });
        try {
            provider.reconcileThrow = true;

            assertThrows(EngineChangeException.class,
                () -> apply(engine, 0, 1));
            assertTrue(notified.await(2, TimeUnit.SECONDS));

            var snapshot = engine.snapshot();
            assertEquals(EngineState.FAIL_STOP, snapshot.state());
            assertTrue(snapshot.candidate().isEmpty());
            assertEquals(CurrentPhase.FAILED,
                snapshot.current().orElseThrow().phase());
            assertTrue(snapshot.retirementBatch().isEmpty());
            assertEquals(1, snapshot.target().orElseThrow().targetRevision());
            var fact = engine.published().current().engineDiagnostics()
                .failure().orElseThrow();
            var subject = assertInstanceOf(FailureSubject.Unit.class,
                fact.subject());
            assertEquals(UNIT, subject.fence().unitKey());
            assertEquals(1, fact.targetRevision().orElseThrow());
            assertEquals(Optional.of(EngineOperationStage.RECONCILING),
                fact.operationStage());
            assertGatesClosed(engine);
            assertEquals(1, notifications.get());
        } finally {
            provider.reconcileThrow = false;
            closeAfterFatal(engine);
        }
    }

    @Test
    void planningFailureBelongsToTheOperationWithoutInventingAnAttempt() {
        try (var engine = engine(request -> { })) {
            var invalid = ApplyDeployment.builder(new DesiredInputGraph(List.of(
                    DesiredInputEntry.builder("missing",
                        new PluginDefinitionRef("missing", "main", "definition"))
                        .build())))
                .expectedRevision(0)
                .selections(List.of())
                .configContext(ConfigContextSnapshot.empty())
                .build();

            assertThrows(EngineChangeException.class,
                () -> engine.submit(invalid).block());

            var view = engine.published().current();
            var operation = view.engineDiagnostics().operation().orElseThrow();
            var subject = assertInstanceOf(FailureSubject.Operation.class,
                view.engineDiagnostics().failure().orElseThrow().subject());
            assertEquals(operation.operationId(), subject.operationId());
            assertTrue(view.engine().candidate().isEmpty());
            assertTrue(view.engine().current().isEmpty());
            assertEquals(EngineState.RUNNING, view.engine().state());
            assertGatesOpen(engine);
        }
    }

    @Test
    void recoverableBootstrapFailureBelongsToTheDurableTarget() {
        store.current = DeploymentTarget.of(1, List.of(),
            new DesiredInputGraph(List.of(DesiredInputEntry.builder("missing",
                new PluginDefinitionRef("missing", "main", "definition"))
                .build())), ConfigContextSnapshot.empty());
        try (var engine = FibraEngine.builder(
                new PluginPackageStore(root.resolve("bootstrap-target")), store)
            .runtimeProvider(provider)
            .hostTerminationPort(request -> { })
            .build()) {
            var view = engine.startAsync().block();

            assertEquals(EngineState.RUNNING, view.engine().state());
            assertEquals(DurableTargetState.PRESENT,
                view.engine().durableState());
            assertEquals(TargetConvergence.BLOCKED,
                view.engine().targetConvergence());
            assertTrue(view.engine().candidate().isEmpty());
            assertTrue(view.engine().current().isEmpty());
            var subject = assertInstanceOf(FailureSubject.DurableTarget.class,
                view.engineDiagnostics().failure().orElseThrow().subject());
            assertEquals(1, subject.targetRevision());
            assertEquals(store.current.targetDigest(), subject.targetDigest());
            assertGatesOpen(engine);
        }
    }

    private FibraEngine engine(HostTerminationPort terminationPort) {
        var engine = FibraEngine.builder(
                new PluginPackageStore(root.resolve(UUID.randomUUID().toString())),
                store)
            .runtimeProvider(provider)
            .hostTerminationPort(terminationPort)
            .build();
        engine.startAsync().block();
        return engine;
    }

    private PublishedView apply(FibraEngine engine, long expectedRevision,
                                int config) {
        return engine.submit(command(expectedRevision, config)).block().view();
    }

    private java.util.concurrent.CompletableFuture<EngineCommandResult> applyAsync(
        FibraEngine engine, long expectedRevision, int config
    ) {
        return engine.submit(command(expectedRevision, config)).toFuture();
    }

    private ApplyDeployment command(long expectedRevision, int config) {
        return ApplyDeployment.builder(graph(config))
            .expectedRevision(expectedRevision)
            .selections(List.of(provider.metadata().selection(true)))
            .configContext(ConfigContextSnapshot.empty())
            .build();
    }

    private static DesiredInputGraph graph(int config) {
        return new DesiredInputGraph(List.of(DesiredInputEntry.builder(
                "a1", new PluginDefinitionRef("a", "main", "definition"))
            .config(LiteralValue.of(config))
            .build()));
    }

    private static void assertNoLegacyTopLevelObservationMaps() {
        assertThrows(NoSuchMethodException.class,
            () -> EngineSnapshot.class.getMethod("units"));
        assertThrows(NoSuchMethodException.class,
            () -> EngineSnapshot.class.getMethod("retiring"));
    }

    private static void assertGatesOpen(FibraEngine engine) {
        var diagnostics = engine.published().current().engineDiagnostics();
        assertTrue(diagnostics.mutationGateOpen());
        assertTrue(diagnostics.contributionAdmissionOpen());
    }

    private static void assertGatesClosed(FibraEngine engine) {
        var diagnostics = engine.published().current().engineDiagnostics();
        assertFalse(diagnostics.mutationGateOpen());
        assertFalse(diagnostics.contributionAdmissionOpen());
    }

    private static void closeAfterFatal(FibraEngine engine) {
        try {
            engine.close();
        } catch (RuntimeException ignored) {
            // Fail-stop may intentionally retain a failed retirement batch for Host exit.
        }
    }

    private final class Store implements DeploymentTargetStore {
        private DeploymentTarget current;
        private boolean fail;
        private boolean uncertain;
        private boolean closed;

        @Override
        public Optional<StoredTarget> load() {
            ensureOpen();
            return Optional.ofNullable(current).map(StoredTarget::confirmed);
        }

        @Override
        public DurableTargetToken save(long expectedRevision,
                                       DeploymentTarget target) {
            ensureOpen();
            if (fail) throw new IllegalStateException("save failed");
            if (uncertain) throw new SaveUnconfirmedException(root,
                new IllegalStateException("fsync uncertain"));
            DeploymentTargetStore.checkRevision(expectedRevision,
                current == null ? 0 : current.targetRevision(), target);
            current = target;
            return StoredTarget.confirmed(target).token();
        }

        @Override
        public void close() {
            closed = true;
        }

        private void ensureOpen() {
            if (closed) throw new IllegalStateException("target store closed");
        }
    }

    private final class ProbeProvider implements RuntimeProvider {
        private final RuntimeId runtimeId = new RuntimeId("alpha");
        private final Map<ExecutionUnitKey, ProbeUnit> units =
            new ConcurrentHashMap<>();
        private RuntimeHostServices services;
        private volatile boolean snapshotFailure;
        private volatile boolean reconcileThrow;
        private volatile String lifecycleFailure;
        private volatile Sinks.One<Void> prepareEntered;
        private volatile Sinks.One<Void> prepareRelease;
        private volatile Sinks.One<Void> drainEntered;
        private volatile Sinks.One<Void> drainRelease;
        private volatile Sinks.One<Void> stopEntered;
        private volatile Sinks.One<Void> stopRelease;

        @Override
        public RuntimeId id() {
            return runtimeId;
        }

        @Override
        public String contractIdentity() {
            return "attempt-state-model-v1";
        }

        @Override
        public List<BuiltInPluginPackage> builtInPackages() {
            return List.of(metadata());
        }

        private BuiltInPluginPackage metadata() {
            return BuiltInPluginPackage.builder()
                .pluginId(new PluginId("a"))
                .version("1")
                .packageDigest("a".repeat(64))
                .facets(List.of(BuiltInFacet.builder(
                        new FacetId("main"), runtimeId,
                        new ExecutionTarget("host"))
                    .definitionIds(Set.of("definition"))
                    .build()))
                .build();
        }

        @Override
        public RuntimeDriver create(RuntimeHostServices services) {
            this.services = services;
            return new RuntimeDriver() {
                @Override public RuntimeId id() { return runtimeId; }

                @Override
                public Mono<RuntimeArtifactInspection> probe(
                    PluginFacetSource source
                ) {
                    return Mono.error(new UnsupportedOperationException());
                }

                @Override
                public Mono<RuntimeArtifactInspection> inspect(
                    ManagedFacet facet
                ) {
                    return Mono.error(new UnsupportedOperationException());
                }

                @Override
                public RuntimeDriverSnapshot snapshot() {
                    return new RuntimeDriverSnapshot(runtimeId, Map.of());
                }

                @Override
                public RuntimeCandidate createCandidate(RuntimeTargetSlice slice) {
                    return new Candidate(slice);
                }

                @Override
                public Mono<Void> closeAsync() {
                    return Mono.empty();
                }
            };
        }

        private void blockPrepare() {
            prepareEntered = Sinks.one();
            prepareRelease = Sinks.one();
        }

        private void awaitPrepare() {
            prepareEntered.asMono().block(Duration.ofSeconds(2));
        }

        private void releasePrepare() {
            var release = prepareRelease;
            prepareRelease = null;
            release.tryEmitEmpty();
        }

        private void blockDrain() {
            drainEntered = Sinks.one();
            drainRelease = Sinks.one();
        }

        private void awaitDrain() {
            drainEntered.asMono().block(Duration.ofSeconds(2));
        }

        private void releaseDrain() {
            var release = drainRelease;
            drainRelease = null;
            release.tryEmitEmpty();
        }

        private void blockStop() {
            stopEntered = Sinks.one();
            stopRelease = Sinks.one();
        }

        private void awaitStop() {
            stopEntered.asMono().block(Duration.ofSeconds(2));
        }

        private void releaseStop() {
            var release = stopRelease;
            stopRelease = null;
            release.tryEmitEmpty();
        }

        private final class Candidate implements RuntimeCandidate {
            private final RuntimeTargetSlice slice;
            private RuntimePlan plan;

            private Candidate(RuntimeTargetSlice slice) {
                this.slice = slice;
            }

            @Override
            public Mono<Void> prepareAsync() {
                Mono<Void> prepare = Mono.fromRunnable(() -> {
                    var unitPlan = ExecutionUnitPlan.builder(
                            UNIT, runtimeId, new ExecutionTarget("host"))
                        .artifactId(metadata().artifactId(
                            new FacetId("main")))
                        .provenance("a", "main", "a".repeat(64))
                        .build();
                    plan = RuntimePlan.of(runtimeId, List.of(unitPlan),
                        List.of(DefinitionBindingPlan.builder(
                                new PluginDefinitionRef(
                                    "a", "main", "definition"), "a1")
                            .unitKey(UNIT)
                            .publicationRequirement(
                                PublicationRequirement.ACTIVE_REQUIRED)
                            .build()));
                });
                var release = prepareRelease;
                if (release == null) return prepare;
                prepareEntered.tryEmitEmpty();
                return release.asMono().then(prepare);
            }

            @Override
            public RuntimePlan preparedPlan() {
                return plan;
            }

            @Override
            public PreparedRuntimeGeneration seal(
                CompiledRuntimeSlice compiled
            ) {
                var unit = new ProbeUnit(plan.units().get(UNIT),
                    slice.target().targetRevision(),
                    services.nextIdentity("unit"));
                units.put(UNIT, unit);
                return new PreparedRuntimeGeneration() {
                    @Override
                    public Map<ExecutionUnitKey, RuntimeUnitGeneration> units() {
                        return Map.of(UNIT, unit);
                    }

                    @Override
                    public Mono<Void> abortAsync() {
                        return Mono.empty();
                    }

                    @Override
                    public Mono<Void> retireAsync() {
                        return Mono.empty();
                    }
                };
            }

            @Override
            public Mono<Void> closeAsync() {
                return Mono.empty();
            }
        }

        private final class ProbeUnit implements RuntimeUnitGeneration {
            private final ExecutionUnitPlan plan;
            private final long revision;
            private final String identity;
            private volatile ExecutionObservation observation;

            private ProbeUnit(ExecutionUnitPlan plan, long revision,
                              String identity) {
                this.plan = plan;
                this.revision = revision;
                this.identity = identity;
                observation = observation("prepared",
                    ExecutionObservation.State.PENDING);
            }

            @Override
            public ExecutionUnitPlan plan() {
                return plan;
            }

            @Override
            public RuntimeUnitFence fence() {
                return RuntimeUnitFence.builder(runtimeId, UNIT)
                    .unitTargetRevision(revision)
                    .runtimeInstanceId(identity)
                    .build();
            }

            @Override
            public Mono<ExecutionObservation> reconcileAsync(String operation) {
                if (reconcileThrow) {
                    throw new IllegalStateException(
                        "synchronous reconcile contract violation");
                }
                return Mono.fromSupplier(() -> observation = observation(
                    operation, ExecutionObservation.State.ACTIVE));
            }

            @Override
            public void closeAdmission() {
            }

            @Override
            public Mono<ExecutionObservation> drainAsync(
                String operation, Instant deadline
            ) {
                if ("drain".equals(lifecycleFailure)) {
                    return Mono.error(new IllegalStateException("drain failed"));
                }
                var result = Mono.fromSupplier(() -> observation(
                    operation, observation.aggregateState()));
                var release = drainRelease;
                if (release == null) return result;
                drainEntered.tryEmitEmpty();
                return release.asMono().then(result);
            }

            @Override
            public Mono<ExecutionObservation> stopAsync(
                String operation, Instant deadline
            ) {
                var result = Mono.fromSupplier(() -> observation = observation(
                    operation, ExecutionObservation.State.PENDING));
                var release = stopRelease;
                if (release == null) return result;
                stopEntered.tryEmitEmpty();
                return release.asMono().then(result);
            }

            @Override
            public ExecutionObservation snapshot() {
                if (snapshotFailure) {
                    throw new IllegalStateException(
                        "snapshot contract violation");
                }
                return observation;
            }

            private ExecutionObservation observation(
                String operation, ExecutionObservation.State state
            ) {
                return ExecutionObservation.of(new PluginId("a"),
                    new FacetId("main"), runtimeId,
                    new ExecutionTarget("host"),
                    List.of(ExecutionObservation.Detail.builder()
                        .unitTargetRevision(revision)
                        .executionId(UNIT.value())
                        .runtimeInstanceId(identity)
                        .lifecycleOperationId(operation)
                        .state(state)
                        .build()));
            }
        }
    }
}
