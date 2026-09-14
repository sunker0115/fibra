package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraEngineRuntimeAdapterTest {
    @Test
    void repeatedStartSharesBootstrapCompletionButReturnsTheCurrentView() {
        var loads = new AtomicInteger();
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .initialArtifacts(() -> {
                loads.incrementAndGet();
                return List.of();
            }).build()) {
            var signal = engine.start();
            var initial = signal.block();
            var current = engine.submit(new ReplaceDesiredGraph(initial.viewRevision(),
                initial.engine().desiredSource().revision(), new DesiredInputGraph(List.of())))
                .block().view();

            assertEquals(1, loads.get());
            assertEquals(current.viewRevision(), signal.block().viewRevision(),
                "retained start publisher must project the current view");
            assertEquals(current, engine.start().block());
            assertEquals(1, loads.get(), "repeat subscriptions must not bootstrap again");
        }
    }

    @Test
    void failedStartupDoesNotCacheTheOriginalExceptionAndItsViewAfterEngineClose() throws Exception {
        var saves = new AtomicInteger();
        var stateStore = new EngineStateStore() {
            @Override public Optional<DeploymentManifest> load() { return Optional.empty(); }
            @Override public void save(DeploymentManifest manifest) {
                saves.incrementAndGet();
                throw new IllegalStateException("startup save failed");
            }
        };
        var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty()).stateStore(stateStore).build();
        var failure = startupFailure(engine);
        engine.close();
        try {
            var repeated = assertThrows(EngineChangeException.class, () -> engine.start().block());
            assertEquals(engine.published().current(), repeated.view());
            assertFalse(repeated.targetSaved());
            assertEquals(1, saves.get(), "failed startup must not bootstrap again");
            for (var attempt = 0; attempt < 60 && failure.get() != null; attempt++) {
                System.gc();
                Thread.sleep(25);
            }
            assertNull(failure.get(), "completed startup cache retains the original EngineChangeException");
        } finally {
            Reference.reachabilityFence(engine);
        }
    }

    private static WeakReference<EngineChangeException> startupFailure(FibraEngine engine) {
        return new WeakReference<>(assertThrows(EngineChangeException.class, () -> engine.start().block()));
    }

    @Test
    void desiredOnlyChangeDoesNotCreateAnotherRuntimeUpdate(@TempDir Path work) throws Exception {
        var id = new RuntimeId("fake");
        var source = work.resolve("sample.bin");
        Files.writeString(source, "plugin");
        var adapter = new FakeRuntimeAdapter(id);
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(new ArtifactStore(work.resolve("store")))
            .runtimeAdapter(adapter).build()) {
            var initial = engine.start().block();
            var installed = engine.submit(InstallArtifact.builder()
                .expectedRevision(initial.viewRevision()).artifactId(new ArtifactId("sample"))
                .runtimeId(id).version("1.0.0").source(source).build()).block().view();
            var before = adapter.updates.get();
            engine.submit(new ReplaceDesiredGraph(installed.viewRevision(),
                installed.engine().desiredSource().revision(),
                new DesiredInputGraph(List.of(DesiredInputEntry.builder("p", "sample").build()))))
                .block();

            assertEquals(before, adapter.updates.get());
        }
    }

    @Test
    void installsThroughAnExplicitRuntimeAndRetiresOnlyAfterPublication(@TempDir Path work)
        throws Exception {
        var artifact = new ArtifactId("sample");
        var runtimeId = new RuntimeId("fake");
        var source = work.resolve("sample.bin");
        Files.writeString(source, "plugin");
        var adapter = new FakeRuntimeAdapter(runtimeId);
        var repository = InMemoryDesiredStateRepository.empty();
        var store = new ArtifactStore(work.resolve("artifacts"));

        try (var engine = FibraEngine.builder(repository)
            .artifactStore(store).runtimeAdapter(adapter).build()) {
            var started = engine.start().block();
            var installed = engine.submit(InstallArtifact.builder()
                .expectedRevision(started.viewRevision()).artifactId(artifact)
                .runtimeId(runtimeId).version("1.0.0").source(source).build())
                .block().view();

            assertTrue(installed.engine().artifacts().containsKey(artifact));
            assertEquals(Set.of(artifact), installed.engine().runtimes().get(runtimeId).resources()
                .stream().map(resource -> resource.artifact().id()).collect(java.util.stream.Collectors.toSet()));
            assertEquals(RuntimeResourceSnapshot.State.ACTIVE,
                installed.engine().runtimes().get(runtimeId).resources().getFirst().state());
            assertEquals(1, adapter.owners.get());
            assertEquals(1, adapter.updates.get());
            assertEquals(1, adapter.updateCloses.get());
            assertEquals(0, adapter.ownerCloses.get());

            var removed = engine.submit(new UninstallArtifact(
                installed.viewRevision(), artifact)).block().view();

            assertFalse(removed.engine().artifacts().containsKey(artifact));
            assertTrue(removed.engine().runtimes().containsKey(runtimeId));
            assertTrue(removed.engine().runtimes().get(runtimeId).resources().isEmpty());
            assertEquals(2, adapter.updates.get());
            assertEquals(2, adapter.updateCloses.get());
            assertEquals(0, adapter.ownerCloses.get());
        }
    }

    @Test
    void startIsLazyRecoversTheExactSavedTargetAndCloseOwnsRuntimeResources(
        @TempDir Path work) throws Exception {
        var artifact = new ArtifactId("sample");
        var runtimeId = new RuntimeId("fake");
        var source = work.resolve("sample.bin");
        Files.writeString(source, "plugin");
        var store = new ArtifactStore(work.resolve("artifacts"));
        var saved = store.prepareInstall(artifact, runtimeId, "1.0.0", source).save();
        var stateStore = EngineStateStore.inMemory();
        stateStore.save(new DeploymentManifest(Map.of(artifact, saved.revision()), new DesiredInputGraph(List.of())));
        var repository = InMemoryDesiredStateRepository.empty();
        var adapter = new FakeRuntimeAdapter(runtimeId);
        var engine = FibraEngine.builder(repository).artifactStore(store).stateStore(stateStore)
            .runtimeAdapter(adapter).build();

        assertEquals(EngineState.NEW, engine.published().current().engine().state());
        assertEquals(0, adapter.owners.get());
        var started = engine.start().block().engine();
        assertTrue(started.artifacts().containsKey(artifact));
        assertTrue(started.runtimes().containsKey(runtimeId));

        engine.close();
        assertEquals(1, adapter.owners.get());
        assertEquals(1, adapter.ownerCloses.get());
    }

    @Test
    void initialArtifactsAndDesiredGraphStartInOneChangeSet(@TempDir Path work)
        throws Exception {
        var artifact = new ArtifactId("sample");
        var runtimeId = new RuntimeId("fake");
        var source = Files.writeString(work.resolve("sample.bin"), "plugin");
        var graph = new DesiredInputGraph(List.of(
            DesiredInputEntry.builder("sample-one", "sample").build()));
        var stateStore = new RecordingStateStore();
        var loads = new AtomicInteger();
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .artifactStore(new ArtifactStore(work.resolve("artifacts")))
            .stateStore(stateStore).runtimeAdapter(new FakeRuntimeAdapter(runtimeId))
            .initialArtifacts(() -> {
                loads.incrementAndGet();
                return List.of(DeploymentArtifact.builder().artifactId(artifact)
                    .runtimeId(runtimeId).version("1.0.0").source(source).build());
            }).build()) {
            assertEquals(0, loads.get());

            var started = engine.start().block().engine();

            assertEquals(1, loads.get());
            assertEquals(1, stateStore.saves.get());
            assertEquals(graph, stateStore.load().orElseThrow().desiredGraph());
            assertEquals(started.artifacts().get(artifact).revision(),
                stateStore.load().orElseThrow().artifacts().get(artifact));
            assertEquals(com.sstlfsj.fibra.PluginInstanceState.ACTIVE,
                started.instances().get("sample-one").state());
        }
    }

    @Test
    void savedTargetDoesNotReadInitialArtifactsOrDesiredSource(@TempDir Path work)
        throws Exception {
        var artifact = new ArtifactId("sample");
        var runtimeId = new RuntimeId("fake");
        var source = Files.writeString(work.resolve("sample.bin"), "plugin");
        var store = new ArtifactStore(work.resolve("artifacts"));
        var saved = store.prepareInstall(artifact, runtimeId, "1.0.0", source).save();
        var stateStore = EngineStateStore.inMemory();
        stateStore.save(new DeploymentManifest(Map.of(artifact, saved.revision()),
            new DesiredInputGraph(List.of())));
        var initialLoads = new AtomicInteger();
        try (var engine = FibraEngine.builder(() -> {
                throw new IllegalStateException("desired source must not be read");
            }).artifactStore(store).stateStore(stateStore)
            .runtimeAdapter(new FakeRuntimeAdapter(runtimeId))
            .initialArtifacts(() -> {
                initialLoads.incrementAndGet();
                throw new IllegalStateException("initial artifacts must not be read");
            }).build()) {
            var started = engine.start().block().engine();

            assertEquals(0, initialLoads.get());
            assertEquals(saved.revision(), started.artifacts().get(artifact).revision());
        }
    }

    @Test
    void rejectsInvalidInitialArtifactListsBeforeSavingATarget(@TempDir Path work)
        throws Exception {
        assertThrows(NullPointerException.class, () -> FibraEngine.builder(
            InMemoryDesiredStateRepository.empty()).initialArtifacts(null));

        var runtimeId = new RuntimeId("fake");
        var source = Files.writeString(work.resolve("sample.bin"), "plugin");
        var artifact = DeploymentArtifact.builder().artifactId(new ArtifactId("sample"))
            .runtimeId(runtimeId).version("1.0.0").source(source).build();
        var stateStore = new RecordingStateStore();
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(new ArtifactStore(work.resolve("artifacts")))
            .stateStore(stateStore).runtimeAdapter(new FakeRuntimeAdapter(runtimeId))
            .initialArtifacts(() -> List.of(artifact, artifact)).build()) {
            assertThrows(IllegalArgumentException.class, () -> engine.start().block());
            assertEquals(0, stateStore.saves.get());
        }

        var nullEntryStateStore = new RecordingStateStore();
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .stateStore(nullEntryStateStore)
            .initialArtifacts(() -> java.util.Arrays.asList(artifact, null)).build()) {
            assertThrows(NullPointerException.class, () -> engine.start().block());
            assertEquals(0, nullEntryStateStore.saves.get());
        }

        var nullStateStore = new RecordingStateStore();
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .stateStore(nullStateStore).initialArtifacts(() -> null).build()) {
            assertThrows(NullPointerException.class, () -> engine.start().block());
            assertEquals(0, nullStateStore.saves.get());
        }
    }

    @Test
    void initialArtifactPrepareFailureRollsBackEarlierStaging(@TempDir Path work)
        throws Exception {
        var runtimeId = new RuntimeId("fake");
        var artifactRoot = work.resolve("artifacts");
        var store = new ArtifactStore(artifactRoot);
        var stateStore = new RecordingStateStore();
        var first = DeploymentArtifact.builder().artifactId(new ArtifactId("first"))
            .runtimeId(runtimeId).version("1.0.0")
            .source(Files.writeString(work.resolve("first.bin"), "plugin")).build();
        var missing = DeploymentArtifact.builder().artifactId(new ArtifactId("missing"))
            .runtimeId(runtimeId).version("1.0.0").source(work.resolve("missing.bin")).build();
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(store).stateStore(stateStore)
            .runtimeAdapter(new FakeRuntimeAdapter(runtimeId))
            .initialArtifacts(() -> List.of(first, missing)).build()) {
            var failure = assertThrows(EngineChangeException.class, () -> engine.start().block());

            assertFalse(failure.targetSaved());
            assertTrue(stateStore.load().isEmpty());
            assertTrue(store.history(first.artifactId()).isEmpty());
            assertTransactionsEmpty(artifactRoot);
        }
    }

    @Test
    void initialArtifactBindFailureDoesNotSaveTargetAndRollsBackStaging(@TempDir Path work)
        throws Exception {
        var runtimeId = new RuntimeId("fake");
        var artifactId = new ArtifactId("sample");
        var artifactRoot = work.resolve("artifacts");
        var store = new ArtifactStore(artifactRoot);
        var stateStore = new RecordingStateStore();
        var graph = new DesiredInputGraph(List.of(
            DesiredInputEntry.builder("sample-one", "sample").build()));
        var artifact = DeploymentArtifact.builder().artifactId(artifactId)
            .runtimeId(runtimeId).version("1.0.0")
            .source(Files.writeString(work.resolve("sample.bin"), "plugin")).build();
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .artifactStore(store).stateStore(stateStore)
            .runtimeAdapter(new FakeRuntimeAdapter(runtimeId, RuntimeFailure.BIND))
            .initialArtifacts(() -> List.of(artifact)).build()) {
            var failure = assertThrows(EngineChangeException.class, () -> engine.start().block());

            assertFalse(failure.targetSaved());
            assertTrue(stateStore.load().isEmpty());
            assertTrue(store.history(artifactId).isEmpty());
            assertTransactionsEmpty(artifactRoot);
        }
    }

    @Test
    void initialArtifactMountFailureKeepsTheSavedTargetAndCommittedArtifact(@TempDir Path work)
        throws Exception {
        var runtimeId = new RuntimeId("fake");
        var artifactId = new ArtifactId("sample");
        var artifactRoot = work.resolve("artifacts");
        var store = new ArtifactStore(artifactRoot);
        var stateStore = new RecordingStateStore();
        var graph = new DesiredInputGraph(List.of(
            DesiredInputEntry.builder("sample-one", "sample").build()));
        var artifact = DeploymentArtifact.builder().artifactId(artifactId)
            .runtimeId(runtimeId).version("1.0.0")
            .source(Files.writeString(work.resolve("sample.bin"), "plugin")).build();
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .artifactStore(store).stateStore(stateStore)
            .runtimeAdapter(new FakeRuntimeAdapter(runtimeId, RuntimeFailure.MOUNT))
            .initialArtifacts(() -> List.of(artifact)).build()) {
            var failure = assertThrows(EngineChangeException.class, () -> engine.start().block());

            assertTrue(failure.targetSaved());
            assertEquals(1, stateStore.saves.get());
            assertEquals(graph, stateStore.load().orElseThrow().desiredGraph());
            assertEquals(1, store.history(artifactId).size());
            assertTransactionsEmpty(artifactRoot);
        }
    }

    @Test
    void rejectsMissingAndMismatchedRuntimeIdsWithoutPublishing(@TempDir Path work)
        throws Exception {
        var source = work.resolve("sample.bin");
        Files.writeString(source, "plugin");
        var store = new ArtifactStore(work.resolve("artifacts"));
        var stateStore = new RecordingStateStore();
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(store).stateStore(stateStore).build()) {
            var started = engine.start().block();
            var savedTarget = stateStore.load().orElseThrow();

            var failure = assertThrows(EngineChangeException.class, () -> engine.submit(
                InstallArtifact.builder().expectedRevision(started.viewRevision())
                    .artifactId(new ArtifactId("sample"))
                    .runtimeId(new RuntimeId("missing")).version("1.0.0")
                    .source(source).build()).block());

            assertFalse(failure.targetSaved());
            assertTrue(failure.getCause() instanceof UnknownRuntimeException);
            var current = engine.published().current();
            assertEquals(started.engine().state(), current.engine().state());
            assertEquals(started.engine().runtimes(), current.engine().runtimes());
            assertEquals(started.engine().artifacts(), current.engine().artifacts());
            assertEquals(savedTarget, stateStore.load().orElseThrow());
            assertTrue(store.history(new ArtifactId("sample")).isEmpty());
        }
    }

    @Test
    void appliesArtifactsAndDesiredGraphInOneChangeSet(@TempDir Path work)
        throws Exception {
        var runtimeId = new RuntimeId("fake");
        var artifactId = new ArtifactId("sample");
        var source = work.resolve("sample.bin");
        Files.writeString(source, "plugin");
        var repository = InMemoryDesiredStateRepository.empty();
        var stateStore = new RecordingStateStore();
        try (var engine = FibraEngine.builder(repository)
            .artifactStore(new ArtifactStore(work.resolve("artifacts")))
            .runtimeAdapter(new FakeRuntimeAdapter(runtimeId)).stateStore(stateStore).build()) {
            var started = engine.start().block();
            var graph = new DesiredInputGraph(List.of(
                DesiredInputEntry.builder("sample-one", "sample").build()));
            var command = ApplyDeployment.builder(graph)
                .expectedRevision(started.viewRevision())
                .expectedDesiredRevision(started.engine().desiredSource().revision())
                .artifacts(List.of(DeploymentArtifact.builder().artifactId(artifactId)
                    .runtimeId(runtimeId).version("1.0.0").source(source).build()))
                .build();

            var deployed = engine.submit(command).block().view().engine();

            assertTrue(deployed.artifacts().containsKey(artifactId));
            assertEquals(com.sstlfsj.fibra.PluginInstanceState.ACTIVE,
                deployed.instances().get("sample-one").state());
            assertEquals(2, stateStore.saves.get());
            assertEquals(artifactId, stateStore.load().orElseThrow().artifacts().keySet().iterator().next());
        }
    }

    @Test
    void restartsFromTheSavedTargetAndInstalledArtifact(@TempDir Path work)
        throws Exception {
        var runtimeId = new RuntimeId("fake");
        var artifactId = new ArtifactId("sample");
        var source = work.resolve("sample.bin");
        var artifactRoot = work.resolve("artifacts");
        var stateRoot = work.resolve("state");
        Files.writeString(source, "plugin");
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(new ArtifactStore(artifactRoot))
            .stateStore(new FileEngineStateStore(stateRoot))
            .runtimeAdapter(new FakeRuntimeAdapter(runtimeId)).build()) {
            var started = engine.start().block();
            engine.submit(InstallArtifact.builder().expectedRevision(started.viewRevision())
                .artifactId(artifactId).runtimeId(runtimeId).version("1.0.0").source(source).build())
                .block();
        }
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(new ArtifactStore(artifactRoot))
            .runtimeAdapter(new FakeRuntimeAdapter(runtimeId))
            .stateStore(new FileEngineStateStore(stateRoot)).build()) {
            var recovered = engine.start().block().engine();

            assertTrue(recovered.artifacts().containsKey(artifactId));
            assertTrue(recovered.runtimes().containsKey(runtimeId));
        }
    }

    private static void assertTransactionsEmpty(Path artifactRoot) throws Exception {
        try (var transactions = Files.list(artifactRoot.resolve("transactions"))) {
            assertEquals(0, transactions.count());
        }
    }

    private enum RuntimeFailure { NONE, BIND, MOUNT }

    private static final class FakeRuntimeAdapter implements PluginRuntimeAdapter {
        private final RuntimeId id;
        private final RuntimeFailure failure;
        private final AtomicInteger owners = new AtomicInteger();
        private final AtomicInteger updates = new AtomicInteger();
        private final AtomicInteger updateCloses = new AtomicInteger();
        private final AtomicInteger ownerCloses = new AtomicInteger();

        private FakeRuntimeAdapter(RuntimeId id) {
            this(id, RuntimeFailure.NONE);
        }

        private FakeRuntimeAdapter(RuntimeId id, RuntimeFailure failure) {
            this.id = id;
            this.failure = failure;
        }

        @Override
        public RuntimeId id() {
            return id;
        }

        @Override
        public Mono<DeploymentArtifact> probe(com.sstlfsj.fibra.artifact.ArtifactPackage artifact) {
            return Mono.error(new AssertionError("runtime tests do not probe installation packages"));
        }

        @Override
        public Mono<RuntimeArtifactInspection> inspect(ArtifactRecord artifact) {
            return Mono.just(new RuntimeArtifactInspection(id, artifact.id(), Map.of()));
        }

        @Override
        public RuntimeResourceOwner create() {
            owners.incrementAndGet();
            var definition = PluginDefinition.builder("sample", Void.class,
                () -> (context, config) -> failure == RuntimeFailure.MOUNT
                    ? Mono.error(new IllegalStateException("mount fixture failure")) : Mono.empty()).build();
            return new RuntimeResourceOwner() {
                private List<ArtifactRecord> active = List.of();
                private RuntimeCatalog catalog = RuntimeCatalog.empty();
                private final Mono<Void> close = Mono.<Void>fromRunnable(ownerCloses::incrementAndGet).cache();

                @Override
                public RuntimeResourceUpdate createUpdate(List<ArtifactRecord> target) {
                    updates.incrementAndGet();
                    var next = List.copyOf(target);
                    var nextCatalog = next.isEmpty() ? RuntimeCatalog.empty()
                        : new RuntimeCatalog(PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> {
                            if (failure == RuntimeFailure.BIND) {
                                throw new IllegalStateException("bind fixture failure");
                            }
                            return null;
                        })),
                            Map.of(definition.name(), next.getFirst().id()));
                    return new RuntimeResourceUpdate() {
                        @Override public Mono<Void> prepareAsync() { return Mono.empty(); }
                        @Override public Set<ArtifactId> affectedArtifacts() {
                            return next.stream().map(ArtifactRecord::id).collect(java.util.stream.Collectors.toSet());
                        }
                        @Override public RuntimeCatalog catalog() { return nextCatalog; }
                        @Override public RuntimeResourceSnapshot snapshot() {
                            return FakeRuntimeAdapter.this.snapshot(next, RuntimeResourceSnapshot.State.PREPARED);
                        }
                        @Override public void adopt() { active = next; catalog = nextCatalog; }
                        private final Mono<Void> close = Mono.<Void>fromRunnable(updateCloses::incrementAndGet).cache();
                        @Override public Mono<Void> closeAsync() { return close; }
                    };
                }

                @Override public RuntimeCatalog catalog() { return catalog; }
                @Override public RuntimeResourceSnapshot snapshot() {
                    return FakeRuntimeAdapter.this.snapshot(active, RuntimeResourceSnapshot.State.ACTIVE);
                }
                @Override public Mono<Void> closeAsync() { return close; }
            };
        }

        private RuntimeResourceSnapshot snapshot(List<ArtifactRecord> artifacts,
                                                 RuntimeResourceSnapshot.State state) {
            return new RuntimeResourceSnapshot(id, artifacts.stream().map(artifact ->
                RuntimeResourceSnapshot.Resource.builder().artifact(artifact).identity(artifact.revision())
                    .state(state).build()).toList());
        }

    }

    private static final class RecordingStateStore implements EngineStateStore {
        private final AtomicInteger saves = new AtomicInteger();
        private DeploymentManifest manifest;
        @Override public Optional<DeploymentManifest> load() { return Optional.ofNullable(manifest); }
        @Override public void save(DeploymentManifest value) { manifest = value; saves.incrementAndGet(); }
    }
}
