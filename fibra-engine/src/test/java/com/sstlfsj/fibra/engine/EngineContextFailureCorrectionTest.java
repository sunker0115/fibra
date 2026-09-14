package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineContextFailureCorrectionTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ArtifactId ARTIFACT = new ArtifactId("sample-artifact");

    @Test
    void contextOnlyChangeCorrectsACleanStableFailureWithoutSavingOrPreparingRuntimeResources(
        @TempDir Path work) throws Exception {
        var broken = ConfigContextSnapshot.of(Map.of("value", "broken"));
        var correctedContext = ConfigContextSnapshot.of(Map.of("value", "corrected"));
        var graph = new DesiredInputGraph(List.of(DesiredInputEntry.builder("sample", "sample")
            .config(LiteralValue.of(Map.of("$ref", "/value"))).build()));
        var adapter = new ProbeAdapter();
        var stateStore = new RecordingStateStore();
        var artifactStore = new ArtifactStore(work.resolve("artifacts"));
        var source = Files.writeString(work.resolve("sample.bin"), "sample");
        var artifact = DeploymentArtifact.builder().artifactId(ARTIFACT)
            .runtimeId(adapter.id()).version("1.0.0").source(source).build();
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .artifactStore(artifactStore).stateStore(stateStore).runtimeAdapter(adapter)
            .configContext(broken).initialArtifacts(() -> List.of(artifact)).build()) {
            var failure = assertThrows(EngineChangeException.class,
                () -> engine.start().block(TIMEOUT));
            var failed = failure.view();
            var identity = failed.engine().instances().get("sample").identity();
            var targetRevision = failed.engineDiagnostics().targetRevision();
            var desiredRevision = failed.engine().desiredSource().revision();
            var saves = stateStore.saves.get();
            var runtimeUpdates = adapter.updates.get();

            assertSame(adapter.startFailure, failure.getCause().getSuppressed()[0]);
            assertEquals(ChangePhase.RECONCILING, failed.engineDiagnostics().failedPhase());
            assertEquals(TargetSaveState.SAVED, failed.engineDiagnostics().targetSaveState());
            assertEquals(TargetSaveState.SAVED, failure.targetSaveState());
            assertTrue(failed.engineDiagnostics().cleanupFailures().isEmpty());
            assertTrue(failed.engineDiagnostics().mutationGateOpen());
            assertFalse(failed.engineDiagnostics().targetSatisfied());
            assertEquals(PluginInstanceState.FAILED,
                failed.engine().instances().get("sample").state());

            var corrected = engine.submit(new ReplaceConfigContext(failed.viewRevision(),
                broken.revision(), correctedContext)).block(TIMEOUT).view();

            assertEquals(saves, stateStore.saves.get(),
                "context-only correction must not save the deployment target");
            assertEquals(runtimeUpdates, adapter.updates.get(),
                "context-only correction must not create a runtime resource update");
            assertEquals(targetRevision, corrected.engineDiagnostics().targetRevision());
            assertEquals(desiredRevision, corrected.engine().desiredSource().revision());
            assertNotEquals(failed.viewRevision(), corrected.viewRevision());
            assertEquals(correctedContext.revision(),
                corrected.engineDiagnostics().contextRevision());
            assertEquals(TargetSaveState.NOT_APPLICABLE,
                corrected.engineDiagnostics().targetSaveState());
            assertNull(corrected.engineDiagnostics().failedPhase());
            assertTrue(corrected.engineDiagnostics().cleanupFailures().isEmpty());
            assertNull(corrected.engineDiagnostics().failure());
            assertTrue(corrected.engineDiagnostics().mutationGateOpen());
            assertTrue(corrected.engineDiagnostics().targetSatisfied());
            assertEquals(identity, corrected.engine().instances().get("sample").identity());
            assertEquals(PluginInstanceState.ACTIVE,
                corrected.engine().instances().get("sample").state());
            assertEquals(2, adapter.starts.get());
        }
    }

    private static final class ProbeAdapter implements PluginRuntimeAdapter {
        private final RuntimeId id = new RuntimeId("probe");
        private final AtomicInteger updates = new AtomicInteger();
        private final AtomicInteger starts = new AtomicInteger();
        private final IllegalStateException startFailure =
            new IllegalStateException("runtime rejected context value");

        @Override public RuntimeId id() { return id; }

        @Override public Mono<DeploymentArtifact> probe(
            com.sstlfsj.fibra.artifact.ArtifactPackage artifact) {
            return Mono.error(new AssertionError("context correction tests do not probe packages"));
        }

        @Override public Mono<RuntimeArtifactInspection> inspect(ArtifactRecord artifact) {
            return Mono.just(new RuntimeArtifactInspection(id, artifact.id(), Map.of()));
        }

        @Override public RuntimeResourceOwner create() {
            return new RuntimeResourceOwner() {
                private List<ArtifactRecord> active = List.of();
                private RuntimeCatalog catalog = RuntimeCatalog.empty();

                @Override public RuntimeResourceUpdate createUpdate(List<ArtifactRecord> target) {
                    updates.incrementAndGet();
                    var next = List.copyOf(target);
                    var definition = PluginDefinition.builder("sample", String.class,
                        () -> (context, config) -> {
                            starts.incrementAndGet();
                            return "broken".equals(config) ? Mono.error(startFailure) : Mono.empty();
                        }).build();
                    var nextCatalog = next.isEmpty() ? RuntimeCatalog.empty()
                        : new RuntimeCatalog(PluginCatalog.of(
                            new PluginCatalogEntry<>(definition, value -> (String) value)),
                            Map.of(definition.name(), ARTIFACT));
                    return new RuntimeResourceUpdate() {
                        @Override public Mono<Void> prepareAsync() { return Mono.empty(); }
                        @Override public Set<ArtifactId> affectedArtifacts() {
                            return next.stream().map(ArtifactRecord::id)
                                .collect(java.util.stream.Collectors.toSet());
                        }
                        @Override public RuntimeCatalog catalog() { return nextCatalog; }
                        @Override public RuntimeResourceSnapshot snapshot() {
                            return ProbeAdapter.this.snapshot(next,
                                RuntimeResourceSnapshot.State.PREPARED);
                        }
                        @Override public void adopt() {
                            active = next;
                            catalog = nextCatalog;
                        }
                        @Override public Mono<Void> closeAsync() { return Mono.empty(); }
                    };
                }

                @Override public RuntimeCatalog catalog() { return catalog; }
                @Override public RuntimeResourceSnapshot snapshot() {
                    return ProbeAdapter.this.snapshot(active,
                        RuntimeResourceSnapshot.State.ACTIVE);
                }
                @Override public Mono<Void> closeAsync() { return Mono.empty(); }
            };
        }

        private RuntimeResourceSnapshot snapshot(List<ArtifactRecord> artifacts,
                                                 RuntimeResourceSnapshot.State state) {
            return new RuntimeResourceSnapshot(id, artifacts.stream().map(artifact ->
                RuntimeResourceSnapshot.Resource.builder().artifact(artifact)
                    .identity(artifact.revision()).state(state).build()).toList());
        }
    }

    private static final class RecordingStateStore implements EngineStateStore {
        private final AtomicInteger saves = new AtomicInteger();
        private DeploymentManifest target;

        @Override public Optional<DeploymentManifest> load() {
            return Optional.ofNullable(target);
        }

        @Override public void save(DeploymentManifest target) {
            saves.incrementAndGet();
            this.target = target;
        }
    }
}
