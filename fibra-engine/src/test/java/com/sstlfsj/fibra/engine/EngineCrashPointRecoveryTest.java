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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class EngineCrashPointRecoveryTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ArtifactId ARTIFACT = new ArtifactId("sample");
    private static final RuntimeId RUNTIME = new RuntimeId("probe");

    @ParameterizedTest
    @EnumSource(value = CrashPoint.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void restartRecoversThePersistedDeploymentAfterAnAbruptChangeTermination(
        CrashPoint crashPoint, @TempDir Path work) throws Exception {
        var artifactRoot = work.resolve("artifacts");
        var stateRoot = work.resolve("state");
        var old = install(artifactRoot, work.resolve("old.bin"), "1.0.0", "old artifact bytes");
        saveTarget(stateRoot, old, graph("old"));
        var upgrade = write(work.resolve("upgrade.bin"), "new artifact bytes");

        var output = terminateAt(crashPoint, artifactRoot, stateRoot, upgrade);
        assertTrue(output.contains("halting at " + crashPoint), output);

        try (var artifacts = new ArtifactStore(artifactRoot);
             var state = new FileEngineStateStore(stateRoot)) {
            var target = state.load().orElseThrow();
            if (crashPoint == CrashPoint.RETIRE) {
                var selected = artifacts.find(ARTIFACT, target.artifacts().get(ARTIFACT)).orElseThrow();
                assertEquals(graph("new"), target.desiredGraph());
                assertEquals("2.0.0", selected.version());
                try (var restored = engine(artifacts, state, new Probe(CrashPoint.NONE))) {
                    var view = restored.start().block(TIMEOUT);
                    assertTrue(view.engineDiagnostics().targetSatisfied());
                    assertEquals(PluginInstanceState.ACTIVE,
                        view.engine().instances().get("sample").state());
                    assertEquals(selected.revision(),
                        view.engine().artifacts().get(ARTIFACT).revision());
                }
            } else {
                assertEquals(graph("old"), target.desiredGraph());
                assertEquals(old.revision(), target.artifacts().get(ARTIFACT));
                assertEquals(old.revision(), artifacts.find(ARTIFACT,
                    target.artifacts().get(ARTIFACT)).orElseThrow().revision());
                if (crashPoint == CrashPoint.PREPARE) {
                    assertFalse(artifacts.history(ARTIFACT).stream()
                        .anyMatch(record -> record.version().equals("2.0.0")));
                } else {
                    var savedNew = artifacts.history(ARTIFACT).stream()
                        .filter(record -> record.version().equals("2.0.0")).findFirst().orElseThrow();
                    assertFalse(savedNew.revision().equals(target.artifacts().get(ARTIFACT)));
                }
                try (var restored = engine(artifacts, state, new Probe(CrashPoint.NONE))) {
                    var view = restored.start().block(TIMEOUT);
                    assertTrue(view.engineDiagnostics().targetSatisfied());
                    assertEquals(PluginInstanceState.ACTIVE,
                        view.engine().instances().get("sample").state());
                    assertEquals(old.revision(), view.engine().artifacts().get(ARTIFACT).revision());
                }
            }
        }
    }

    private static String terminateAt(CrashPoint crashPoint, Path artifactRoot, Path stateRoot,
                                      Path upgrade) throws Exception {
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), CrashProcess.class.getName(),
            crashPoint.name(), artifactRoot.toString(), stateRoot.toString(), upgrade.toString());
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                fail("子 JVM 未在 5 秒内终止");
            }
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(crashPoint.exitCode, process.exitValue(), output);
            return output;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    "强制终止后子 JVM 仍未退出");
            }
        }
    }

    private static FibraEngine engine(ArtifactStore artifacts, EngineStateStore state,
                                      PluginRuntimeAdapter probe) {
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
            var crashPoint = CrashPoint.valueOf(args[0]);
            try {
                var artifacts = new ArtifactStore(Path.of(args[1]));
                var fileState = new FileEngineStateStore(Path.of(args[2]));
                EngineStateStore state = crashPoint == CrashPoint.SAVE
                    ? new HaltBeforeSaveStateStore(fileState, crashPoint) : fileState;
                var engine = engine(artifacts, state, new Probe(crashPoint));
                var initial = engine.start().block(TIMEOUT);
                engine.submit(deployment(initial, Path.of(args[3]))).block(TIMEOUT);
                throw new AssertionError("change must terminate at " + crashPoint);
            } catch (Throwable failure) {
                failure.printStackTrace(System.err);
                Runtime.getRuntime().halt(74);
            }
        }
    }

    private enum CrashPoint {
        PREPARE(71), SAVE(72), RETIRE(73), NONE(-1);

        private final int exitCode;

        CrashPoint(int exitCode) {
            this.exitCode = exitCode;
        }
    }

    private static final class HaltBeforeSaveStateStore implements EngineStateStore {
        private final FileEngineStateStore delegate;
        private final CrashPoint crashPoint;

        private HaltBeforeSaveStateStore(FileEngineStateStore delegate, CrashPoint crashPoint) {
            this.delegate = delegate;
            this.crashPoint = crashPoint;
        }

        @Override public Optional<DeploymentManifest> load() { return delegate.load(); }

        @Override public void save(DeploymentManifest manifest) {
            halt(crashPoint);
            delegate.save(manifest);
        }

        @Override public void close() { delegate.close(); }
    }

    private static final class Probe implements PluginRuntimeAdapter {
        private final CrashPoint crashPoint;

        private Probe(CrashPoint crashPoint) {
            this.crashPoint = crashPoint;
        }

        @Override public RuntimeId id() { return RUNTIME; }

        @Override public Mono<DeploymentArtifact> probe(
            com.sstlfsj.fibra.artifact.ArtifactPackage artifact) {
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
                    var next = List.copyOf(target);
                    var nextCatalog = Probe.catalog(next);
                    return new RuntimeResourceUpdate() {
                        @Override public Mono<Void> prepareAsync() {
                            return Mono.fromRunnable(() -> {
                                if (crashPoint == CrashPoint.PREPARE && isNew(next)) halt(crashPoint);
                            });
                        }

                        @Override public Set<ArtifactId> affectedArtifacts() {
                            return next.stream().map(ArtifactRecord::id)
                                .collect(java.util.stream.Collectors.toSet());
                        }

                        @Override public RuntimeCatalog catalog() { return nextCatalog; }

                        @Override public RuntimeResourceSnapshot snapshot() {
                            return Probe.snapshot(next, RuntimeResourceSnapshot.State.PREPARED);
                        }

                        @Override public void adopt() { active = next; catalog = nextCatalog; }

                        @Override public Mono<Void> closeAsync() {
                            return Mono.fromRunnable(() -> {
                                if (crashPoint == CrashPoint.RETIRE && isNew(next)) halt(crashPoint);
                            });
                        }
                    };
                }

                @Override public RuntimeCatalog catalog() { return catalog; }

                @Override public RuntimeResourceSnapshot snapshot() {
                    return Probe.snapshot(active, RuntimeResourceSnapshot.State.ACTIVE);
                }

                @Override public Mono<Void> closeAsync() { return Mono.empty(); }
            };
        }

        private static RuntimeCatalog catalog(List<ArtifactRecord> artifacts) {
            if (artifacts.isEmpty()) return RuntimeCatalog.empty();
            var definition = PluginDefinition.builder("sample", String.class,
                () -> (context, config) -> Mono.empty()).build();
            return new RuntimeCatalog(PluginCatalog.of(
                new PluginCatalogEntry<>(definition, value -> (String) value)),
                Map.of(definition.name(), artifacts.getFirst().id()));
        }

        private static RuntimeResourceSnapshot snapshot(List<ArtifactRecord> artifacts,
                                                        RuntimeResourceSnapshot.State state) {
            return new RuntimeResourceSnapshot(RUNTIME, artifacts.stream().map(artifact ->
                RuntimeResourceSnapshot.Resource.builder().artifact(artifact).identity(artifact.revision())
                    .state(state).build()).toList());
        }

        private static boolean isNew(List<ArtifactRecord> artifacts) {
            return artifacts.stream().anyMatch(artifact -> artifact.version().equals("2.0.0"));
        }
    }

    private static void halt(CrashPoint crashPoint) {
        System.out.println("halting at " + crashPoint);
        System.out.flush();
        Runtime.getRuntime().halt(crashPoint.exitCode);
    }
}
