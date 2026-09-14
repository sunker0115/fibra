package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnginePartialAdoptFailureTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ArtifactId FIRST_ARTIFACT = new ArtifactId("first-artifact");
    private static final ArtifactId SECOND_ARTIFACT = new ArtifactId("second-artifact");

    @Test
    void secondRuntimeAdoptFailurePublishesThePartialOwnershipFactsAndClosesTheGate(
        @TempDir Path work) throws Exception {
        var first = new ProbeAdapter("a-runtime", "first-definition", false);
        var second = new ProbeAdapter("b-runtime", "second-definition", true);
        var stateStore = new FileEngineStateStore(work.resolve("state"));
        var artifactStore = new ArtifactStore(work.resolve("artifacts"));
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(artifactStore).stateStore(stateStore)
            .runtimeAdapter(first).runtimeAdapter(second).build()) {
            var started = engine.start().block(TIMEOUT);
            var firstSource = Files.writeString(work.resolve("first.bin"), "first");
            var secondSource = Files.writeString(work.resolve("second.bin"), "second");
            var target = new DesiredInputGraph(List.of(
                DesiredInputEntry.builder("sample", first.definitionName).build()));
            var command = ApplyDeployment.builder(target)
                .expectedRevision(started.viewRevision())
                .expectedDesiredRevision(started.engine().desiredSource().revision())
                .artifacts(List.of(
                    artifact(FIRST_ARTIFACT, first, firstSource),
                    artifact(SECOND_ARTIFACT, second, secondSource)))
                .build();

            var failure = assertThrows(EngineChangeException.class,
                () -> engine.submit(command).block(TIMEOUT));

            var failed = failure.view();
            var saved = stateStore.load().orElseThrow();
            assertSame(second.adoptFailure, failure.getCause());
            assertEquals(TargetSaveState.SAVED, failure.targetSaveState());
            assertEquals(ChangePhase.RECONCILING, failed.engineDiagnostics().failedPhase());
            assertEquals(TargetSaveState.SAVED,
                failed.engineDiagnostics().targetSaveState());
            assertTrue(failed.engineDiagnostics().cleanupFailures().isEmpty());
            assertFalse(failed.engineDiagnostics().mutationGateOpen());
            assertEquals(target, saved.desiredGraph());
            assertEquals(Set.of(FIRST_ARTIFACT, SECOND_ARTIFACT), saved.artifacts().keySet());
            assertEquals(saved.artifacts(), failed.engine().artifacts().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                    entry -> entry.getValue().revision())));
            assertEquals(1, first.adopts.get(), "the first runtime was really adopted");
            assertEquals(1, second.adopts.get(), "the failing adopt was really attempted");
            assertEquals(0, first.mounts.get(), "instance coordination must not start after partial adopt");
            assertEquals(0, second.mounts.get(), "instance coordination must not start after partial adopt");
            assertTrue(failed.engine().instances().isEmpty());
            assertEquals(0, first.updateCloses.get(), "a partial adopt must not auto-retire the update");
            assertEquals(0, second.updateCloses.get(), "a partial adopt must not auto-retire the update");
            var firstResources = failed.engineDiagnostics().resources().get(first.id()).resources();
            assertEquals(1, firstResources.size());
            assertEquals(FIRST_ARTIFACT, firstResources.getFirst().artifact().id());
            assertEquals(RuntimeResourceSnapshot.State.ACTIVE, firstResources.getFirst().state());
            assertTrue(failed.engineDiagnostics().resources().get(second.id()).resources().isEmpty());
            assertThrows(MutationGateClosedException.class,
                () -> engine.submit(new RefreshDesired(failed.viewRevision())).block(TIMEOUT));
        }
    }

    private static DeploymentArtifact artifact(ArtifactId id, ProbeAdapter adapter, Path source) {
        return DeploymentArtifact.builder().artifactId(id).runtimeId(adapter.id())
            .version("1.0.0").source(source).build();
    }

    private static final class ProbeAdapter implements PluginRuntimeAdapter {
        private final RuntimeId id;
        private final String definitionName;
        private final boolean failAdopt;
        private final IllegalStateException adoptFailure =
            new IllegalStateException("second runtime adopt failed");
        private final AtomicInteger adopts = new AtomicInteger();
        private final AtomicInteger mounts = new AtomicInteger();
        private final AtomicInteger updateCloses = new AtomicInteger();

        private ProbeAdapter(String id, String definitionName, boolean failAdopt) {
            this.id = new RuntimeId(id);
            this.definitionName = definitionName;
            this.failAdopt = failAdopt;
        }

        @Override public RuntimeId id() { return id; }

        @Override public Mono<DeploymentArtifact> probe(
            com.sstlfsj.fibra.artifact.ArtifactPackage artifact) {
            return Mono.error(new AssertionError("partial-adopt tests do not probe packages"));
        }

        @Override public Mono<RuntimeArtifactInspection> inspect(ArtifactRecord artifact) {
            return Mono.just(new RuntimeArtifactInspection(id, artifact.id(), Map.of()));
        }

        @Override public RuntimeResourceOwner create() {
            return new RuntimeResourceOwner() {
                private List<ArtifactRecord> active = List.of();
                private RuntimeCatalog catalog = RuntimeCatalog.empty();

                @Override public RuntimeResourceUpdate createUpdate(List<ArtifactRecord> target) {
                    var next = List.copyOf(target);
                    var definition = PluginDefinition.builder(definitionName, Void.class,
                        () -> (context, config) -> {
                            mounts.incrementAndGet();
                            return Mono.empty();
                        }).build();
                    var nextCatalog = next.isEmpty() ? RuntimeCatalog.empty()
                        : new RuntimeCatalog(PluginCatalog.of(
                            new PluginCatalogEntry<>(definition, ignored -> null)),
                            Map.of(definition.name(), next.getFirst().id()));
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
                            adopts.incrementAndGet();
                            if (failAdopt) throw adoptFailure;
                            active = next;
                            catalog = nextCatalog;
                        }
                        @Override public Mono<Void> closeAsync() {
                            return Mono.fromRunnable(updateCloses::incrementAndGet);
                        }
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
}
