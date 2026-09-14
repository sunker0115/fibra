package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ApplyDeploymentMountFailureRecoveryTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ArtifactId ARTIFACT = new ArtifactId("sample");
    private static final RuntimeId RUNTIME = new RuntimeId("probe");

    @Test
    void processTerminationAfterSavedTargetAndMountFailureRecoversTheNewArtifact(
        @TempDir Path work) throws Exception {
        var artifactRoot = work.resolve("artifacts");
        var stateRoot = work.resolve("state");
        var upgrade = write(work.resolve("upgrade.bin"), "new artifact bytes");
        var old = install(artifactRoot, work.resolve("old.bin"), "1.0.0", "old artifact bytes");
        saveTarget(stateRoot, old, graph("old"));

        var output = crashAfterVerifiedFailure(artifactRoot, stateRoot, upgrade);
        assertTrue(output.contains("verified saved target mount failure"), output);

        try (var artifacts = new ArtifactStore(artifactRoot);
             var state = new FileEngineStateStore(stateRoot)) {
            var target = state.load().orElseThrow();
            var saved = artifacts.find(ARTIFACT, target.artifacts().get(ARTIFACT)).orElseThrow();
            assertEquals(graph("new"), target.desiredGraph());
            assertEquals("2.0.0", saved.version());
            assertEquals("new artifact bytes", Files.readString(saved.location()));

            try (var restored = engine(artifacts, state, new Probe(false))) {
                var view = restored.start().block(TIMEOUT);
                assertTrue(view.engineDiagnostics().targetSatisfied());
                assertEquals(PluginInstanceState.ACTIVE, view.engine().instances().get("sample").state());
                assertEquals(saved.revision(), view.engine().artifacts().get(ARTIFACT).revision());
                assertEquals(saved.revision(), view.engineDiagnostics().resources().get(RUNTIME)
                    .resources().getFirst().artifact().revision());
            }
        }
    }

    private static String crashAfterVerifiedFailure(Path artifactRoot, Path stateRoot, Path upgrade)
        throws Exception {
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), CrashProcess.class.getName(),
            artifactRoot.toString(), stateRoot.toString(), upgrade.toString());
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("子 JVM 未在时限内终止");
        }
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(73, process.exitValue(), output);
        return output;
    }

    private static FibraEngine engine(ArtifactStore artifacts, FileEngineStateStore state,
                                      Probe probe) {
        return FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(artifacts).stateStore(state).runtimeAdapter(probe).build();
    }

    private static ArtifactRecord install(Path artifactRoot, Path source, String version,
                                          String content) throws IOException {
        try (var store = new ArtifactStore(artifactRoot)) {
            return store.prepareInstall(ARTIFACT, RUNTIME, version, write(source, content)).save();
        }
    }

    private static void saveTarget(Path stateRoot, ArtifactRecord artifact, DesiredInputGraph graph) {
        try (var state = new FileEngineStateStore(stateRoot)) {
            state.save(new DeploymentManifest(Map.of(ARTIFACT, artifact.revision()), graph));
        }
    }

    private static DesiredInputGraph graph(String value) {
        return new DesiredInputGraph(List.of(DesiredInputEntry.builder("sample", "sample")
            .config(LiteralValue.of(value)).build()));
    }

    private static ApplyDeployment deployment(PublishedView view, Path upgrade) {
        return ApplyDeployment.builder(graph("new")).expectedRevision(view.viewRevision())
            .expectedDesiredRevision(view.engine().desiredSource().revision())
            .artifacts(List.of(DeploymentArtifact.builder().artifactId(ARTIFACT)
                .runtimeId(RUNTIME).version("2.0.0").source(upgrade).build())).build();
    }

    private static Path write(Path path, String content) throws IOException {
        Files.writeString(path, content);
        return path;
    }

    public static final class CrashProcess {
        public static void main(String[] args) {
            try {
                var artifacts = new ArtifactStore(Path.of(args[0]));
                var state = new FileEngineStateStore(Path.of(args[1]));
                var probe = new Probe(true);
                var engine = engine(artifacts, state, probe);
                var initial = engine.start().block(TIMEOUT);
                var failure = expectMountFailure(engine, deployment(initial, Path.of(args[2])));
                var target = state.load().orElseThrow();
                var newArtifact = target.artifacts().get(ARTIFACT);

                require(failure.targetSaved(), "target must be confirmed before mount failure");
                require(!failure.view().engineDiagnostics().mutationGateOpen(),
                    "a committed but unretired update must close mutation gate");
                require(failure.view().engineDiagnostics().failure().contains(ChangePhase.RECONCILING.name()),
                    "mount failure diagnostics must identify reconcile as the source stage");
                require(graph("new").equals(target.desiredGraph()), "new graph was not persisted");
                require(newArtifact.equals(failure.view().engine().artifacts().get(ARTIFACT).revision()),
                    "published artifact selection must be the persisted new target");
                require(failure.view().engine().instances().isEmpty(),
                    "old instance must be retired before the new definition mount fails");
                require(newArtifact.equals(failure.view().engineDiagnostics().resources().get(RUNTIME)
                    .resources().getFirst().artifact().revision()),
                    "new runtime resource must remain owned after the committed failure");
                require(probe.closedUpdates.equals(List.of(1)),
                    "only the old resource update may retire before process termination");
                require(probe.ownerCloses.get() == 0,
                    "the old engine must retain the owner while the new update remains pending");
                System.out.println("verified saved target mount failure");
                System.out.flush();
                Runtime.getRuntime().halt(73);
            } catch (Throwable failure) {
                failure.printStackTrace(System.err);
                Runtime.getRuntime().halt(74);
            }
        }

        private static EngineChangeException expectMountFailure(FibraEngine engine,
                                                                 ApplyDeployment deployment) {
            try {
                engine.submit(deployment).block(TIMEOUT);
                throw new AssertionError("new definition mount should fail");
            } catch (EngineChangeException failure) {
                require(failure.getCause() instanceof IllegalStateException,
                    "mount failure must remain the change cause");
                return failure;
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Probe implements PluginRuntimeAdapter {
        private final boolean failNewMount;
        private final AtomicInteger updates = new AtomicInteger();
        private final AtomicInteger ownerCloses = new AtomicInteger();
        private final List<Integer> closedUpdates = new CopyOnWriteArrayList<>();

        private Probe(boolean failNewMount) {
            this.failNewMount = failNewMount;
        }

        @Override public RuntimeId id() { return RUNTIME; }

        @Override public Mono<DeploymentArtifact> probe(com.sstlfsj.fibra.artifact.ArtifactPackage artifact) {
            return Mono.error(new AssertionError("deployment tests do not probe installation packages"));
        }

        @Override public Mono<RuntimeArtifactInspection> inspect(ArtifactRecord artifact) {
            return Mono.just(new RuntimeArtifactInspection(RUNTIME, artifact.id(), Map.of()));
        }

        @Override public RuntimeResourceOwner create() {
            return new RuntimeResourceOwner() {
                private RuntimeCatalog catalog = RuntimeCatalog.empty();
                private List<ArtifactRecord> active = List.of();

                @Override public RuntimeResourceUpdate createUpdate(List<ArtifactRecord> target) {
                    var generation = updates.incrementAndGet();
                    var next = List.copyOf(target);
                    var definition = PluginDefinition.builder("sample", String.class, () -> {
                        if (failNewMount && next.getFirst().version().equals("2.0.0")) {
                            throw new IllegalStateException("new artifact definition cannot mount");
                        }
                        return (context, config) -> Mono.<Void>empty();
                    }).build();
                    var nextCatalog = new RuntimeCatalog(PluginCatalog.of(
                        new PluginCatalogEntry<>(definition, value -> (String) value)),
                        Map.of(definition.name(), next.getFirst().id()));
                    return new RuntimeResourceUpdate() {
                        @Override public Mono<Void> prepareAsync() { return Mono.empty(); }
                        @Override public Set<ArtifactId> affectedArtifacts() {
                            return next.stream().map(ArtifactRecord::id)
                                .collect(java.util.stream.Collectors.toSet());
                        }
                        @Override public RuntimeCatalog catalog() { return nextCatalog; }
                        @Override public RuntimeResourceSnapshot snapshot() {
                            return Probe.this.snapshot(next, RuntimeResourceSnapshot.State.PREPARED);
                        }
                        @Override public void adopt() { active = next; catalog = nextCatalog; }
                        private final Mono<Void> close = Mono.<Void>fromRunnable(() ->
                            closedUpdates.add(generation)).cache();
                        @Override public Mono<Void> closeAsync() { return close; }
                    };
                }

                @Override public RuntimeCatalog catalog() { return catalog; }
                @Override public RuntimeResourceSnapshot snapshot() {
                    return Probe.this.snapshot(active, RuntimeResourceSnapshot.State.ACTIVE);
                }
                @Override public Mono<Void> closeAsync() {
                    return Mono.fromRunnable(ownerCloses::incrementAndGet);
                }
            };
        }

        private RuntimeResourceSnapshot snapshot(List<ArtifactRecord> artifacts,
                                                 RuntimeResourceSnapshot.State state) {
            return new RuntimeResourceSnapshot(RUNTIME, artifacts.stream().map(artifact ->
                RuntimeResourceSnapshot.Resource.builder().artifact(artifact).identity(artifact.revision())
                    .state(state).build()).toList());
        }
    }
}
