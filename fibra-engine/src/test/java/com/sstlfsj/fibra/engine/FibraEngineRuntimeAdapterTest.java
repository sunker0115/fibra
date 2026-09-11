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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraEngineRuntimeAdapterTest {
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

    private static final class FakeRuntimeAdapter implements PluginRuntimeAdapter {
        private final RuntimeId id;
        private final AtomicInteger owners = new AtomicInteger();
        private final AtomicInteger updates = new AtomicInteger();
        private final AtomicInteger updateCloses = new AtomicInteger();
        private final AtomicInteger ownerCloses = new AtomicInteger();

        private FakeRuntimeAdapter(RuntimeId id) {
            this.id = id;
        }

        @Override
        public RuntimeId id() {
            return id;
        }

        @Override
        public Mono<RuntimeArtifactInspection> inspect(ArtifactRecord artifact) {
            return Mono.just(new RuntimeArtifactInspection(id, artifact.id(), Map.of()));
        }

        @Override
        public RuntimeResourceOwner create() {
            owners.incrementAndGet();
            var definition = PluginDefinition.builder("sample", Void.class,
                () -> (context, config) -> Mono.empty()).build();
            return new RuntimeResourceOwner() {
                private List<ArtifactRecord> active = List.of();
                private RuntimeCatalog catalog = RuntimeCatalog.empty();
                private final Mono<Void> close = Mono.<Void>fromRunnable(ownerCloses::incrementAndGet).cache();

                @Override
                public RuntimeResourceUpdate createUpdate(List<ArtifactRecord> target) {
                    updates.incrementAndGet();
                    var next = List.copyOf(target);
                    var nextCatalog = next.isEmpty() ? RuntimeCatalog.empty()
                        : new RuntimeCatalog(PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> null)),
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
