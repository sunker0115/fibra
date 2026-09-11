package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.artifact.ArtifactException;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactPhase;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ApplyDeploymentPersistenceBoundaryTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ArtifactId ARTIFACT = new ArtifactId("sample");
    private static final ArtifactId AUXILIARY = new ArtifactId("auxiliary");
    private static final ContributionKind<String, String, String> COMMAND =
        ContributionKind.local("command", String.class, String.class, String.class);
    private static final ContributionId CONTRIBUTION = new ContributionId("sample", "run");

    @Test
    void targetSaveFailureRetainsOldRuntimeAndAllowsAnotherApplyDeployment(@TempDir Path work)
        throws Exception {
        var io = new FailingIo();
        var probe = new Probe(false);
        var stateStore = new FileEngineStateStore(work.resolve("state"), io);
        var artifactStore = new ArtifactStore(work.resolve("artifacts"));
        var old = install(artifactStore, work.resolve("old.bin"), probe, "1.0.0", "old-artifact");
        stateStore.save(manifest(old, graph("old")));
        var upgrade = write(work.resolve("upgrade.bin"), "new-artifact");
        try (var engine = engine(artifactStore, stateStore, probe, graph("old"))) {
            var initial = engine.start().block(TIMEOUT);
            var oldIdentity = initial.engine().instances().get("sample").identity();
            io.failBeforeReplace = true;

            var failure = assertThrows(EngineChangeException.class,
                () -> engine.submit(deployment(initial, graph("new"), upgrade, probe)).block(TIMEOUT));

            var installedNew = artifactStore.history(ARTIFACT).stream()
                .filter(record -> record.version().equals("2.0.0")).findFirst().orElseThrow();
            assertAll(
                () -> assertFalse(failure.targetSaved()),
                () -> assertTrue(failure.view().engineDiagnostics().mutationGateOpen()),
                () -> assertEquals(graph("old"), stateStore.load().orElseThrow().desiredGraph()),
                () -> assertEquals(old.revision(), stateStore.load().orElseThrow().artifacts().get(ARTIFACT)),
                () -> assertEquals(old.revision(), failure.view().engine().artifacts()
                    .get(ARTIFACT).revision()),
                () -> assertEquals(oldIdentity, failure.view().engine().instances().get("sample").identity()),
                () -> assertEquals(old.revision(), failure.view().engineDiagnostics().resources()
                    .get(probe.id()).resources().getFirst().artifact().revision()),
                () -> assertEquals("old:call", invoke(engine, failure.view())),
                () -> assertTrue(Files.exists(installedNew.location())),
                () -> assertNotEquals(installedNew.revision(),
                    stateStore.load().orElseThrow().artifacts().get(ARTIFACT)));

            io.failBeforeReplace = false;
            var recovered = engine.submit(deployment(failure.view(), graph("new"), upgrade, probe))
                .block(TIMEOUT).view();
            assertTrue(recovered.engineDiagnostics().targetSatisfied());
            assertEquals(graph("new"), stateStore.load().orElseThrow().desiredGraph());
        }
    }

    @Test
    void unconfirmedTargetSaveStopsOldEngineAndRestoresNewTargetOnRestart(@TempDir Path work)
        throws Exception {
        var io = new FailingIo();
        var probe = new Probe(false);
        var statePath = work.resolve("state");
        var artifactPath = work.resolve("artifacts");
        var stateStore = new FileEngineStateStore(statePath, io);
        var artifactStore = new ArtifactStore(artifactPath);
        var old = install(artifactStore, work.resolve("old.bin"), probe, "1.0.0", "old-artifact");
        stateStore.save(manifest(old, graph("old")));
        var upgrade = write(work.resolve("upgrade.bin"), "new-artifact");
        try (var engine = engine(artifactStore, stateStore, probe, graph("old"))) {
            var initial = engine.start().block(TIMEOUT);
            io.failAfterReplace = true;

            var failure = assertThrows(EngineChangeException.class,
                () -> engine.submit(deployment(initial, graph("new"), upgrade, probe)).block(TIMEOUT));

            assertAll(
                () -> assertInstanceOf(EngineStateStore.SaveUnconfirmedException.class, failure.getCause()),
                () -> assertFalse(failure.targetSaved()),
                () -> assertFalse(failure.view().engineDiagnostics().mutationGateOpen()),
                () -> assertEquals(graph("old"), failure.view().engine().desiredGraph()),
                () -> assertEquals(old.revision(), failure.view().engine().artifacts()
                    .get(ARTIFACT).revision()),
                () -> assertEquals("old:call", invoke(engine, failure.view())),
                () -> assertEquals(1, probe.mounts.get(), "unconfirmed target must not reconcile a new plugin"),
                () -> assertEquals(graph("new"), stateStore.load().orElseThrow().desiredGraph()),
                () -> assertTrue(artifactStore.history(ARTIFACT).stream()
                    .anyMatch(record -> record.version().equals("2.0.0"))),
                () -> assertThrows(MutationGateClosedException.class,
                    () -> engine.submit(deployment(failure.view(), graph("later"), upgrade, probe)).block(TIMEOUT)));
        }
        try (var restoredArtifacts = new ArtifactStore(artifactPath);
             var restoredState = new FileEngineStateStore(statePath);
             var restored = engine(restoredArtifacts, restoredState, new Probe(false), graph("old"))) {
            var expectedRevision = restoredState.load().orElseThrow().artifacts().get(ARTIFACT);
            var view = restored.start().block(TIMEOUT);
            assertAll(
                () -> assertEquals(graph("new"), view.engine().desiredGraph()),
                () -> assertTrue(view.engineDiagnostics().targetSatisfied()),
                () -> assertEquals(expectedRevision, view.engine().artifacts().get(ARTIFACT).revision()),
                () -> assertEquals(expectedRevision, view.engineDiagnostics().resources().get(new RuntimeId("probe"))
                    .resources().getFirst().artifact().revision()),
                () -> assertEquals("new:call", invoke(restored, view)));
        }
    }

    @Test
    void retirementFailureAfterTargetSaveClosesTheGateAndMakesNormalCloseFail(@TempDir Path work)
        throws Exception {
        var probe = new Probe(true);
        var statePath = work.resolve("state");
        var artifactPath = work.resolve("artifacts");
        var stateStore = new FileEngineStateStore(statePath);
        var artifactStore = new ArtifactStore(artifactPath);
        var old = install(artifactStore, work.resolve("old.bin"), probe, "1.0.0", "old-artifact");
        stateStore.save(manifest(old, graph("old")));
        var upgrade = write(work.resolve("upgrade.bin"), "new-artifact");
        var engine = engine(artifactStore, stateStore, probe, graph("old"));
        try {
            var initial = engine.start().block(TIMEOUT);
            var failure = assertThrows(EngineChangeException.class,
                () -> engine.submit(deployment(initial, graph("new"), upgrade, probe)).block(TIMEOUT));

            assertAll(
                () -> assertTrue(failure.targetSaved()),
                () -> assertFalse(failure.view().engineDiagnostics().mutationGateOpen()),
                () -> assertEquals(graph("new"), stateStore.load().orElseThrow().desiredGraph()),
                () -> assertTrue(artifactStore.history(ARTIFACT).stream()
                    .anyMatch(record -> record.version().equals("2.0.0"))),
                () -> assertThrows(MutationGateClosedException.class,
                    () -> engine.submit(deployment(failure.view(), graph("later"), upgrade, probe)).block(TIMEOUT)),
                () -> assertThrows(RuntimeException.class, engine::close));
        } finally {
            try {
                engine.close();
            } catch (RuntimeException expectedCleanupFailure) {
                // Failed resource retirement keeps the engine close signal failed.
            }
            stateStore.close();
            artifactStore.close();
        }
    }

    @Test
    void laterArtifactStageFailureRollsBackEarlierTransactionAndKeepsTheOldRuntime(@TempDir Path work)
        throws Exception {
        var probe = new Probe(false);
        var stateStore = new FileEngineStateStore(work.resolve("state"));
        var artifactStore = new ArtifactStore(work.resolve("artifacts"));
        var old = install(artifactStore, work.resolve("old.bin"), probe, "1.0.0", "old-artifact");
        stateStore.save(manifest(old, graph("old")));
        var upgrade = write(work.resolve("upgrade.bin"), "new-artifact");
        try (var engine = engine(artifactStore, stateStore, probe, graph("old"))) {
            var initial = engine.start().block(TIMEOUT);
            var oldIdentity = initial.engine().instances().get("sample").identity();
            var missing = work.resolve("missing-auxiliary.bin");

            var failure = assertThrows(EngineChangeException.class, () -> engine.submit(
                deployment(initial, graph("new"), List.of(
                    artifact(ARTIFACT, upgrade, "2.0.0", probe),
                    artifact(AUXILIARY, missing, "1.0.0", probe)))).block(TIMEOUT));

            assertAll(
                () -> assertFalse(failure.targetSaved()),
                () -> assertTrue(failure.view().engineDiagnostics().mutationGateOpen()),
                () -> assertEquals(graph("old"), stateStore.load().orElseThrow().desiredGraph()),
                () -> assertEquals(old.revision(), stateStore.load().orElseThrow().artifacts().get(ARTIFACT)),
                () -> assertEquals(old.revision(), failure.view().engine().artifacts()
                    .get(ARTIFACT).revision()),
                () -> assertEquals(oldIdentity, failure.view().engine().instances().get("sample").identity()),
                () -> assertEquals(old.revision(), failure.view().engineDiagnostics().resources().get(probe.id())
                    .resources().getFirst().artifact().revision()),
                () -> assertEquals("old:call", invoke(engine, failure.view())),
                () -> assertEquals(List.of(old.revision()), artifactStore.history(ARTIFACT).stream()
                    .map(ArtifactRecord::revision).toList()),
                () -> assertTrue(artifactStore.history(AUXILIARY).isEmpty()),
                () -> assertTransactionDirectoryEmpty(work.resolve("artifacts")));
        }
    }

    @Test
    void runtimePreparationFailureRollsBackAllStagedArtifactsAndKeepsTheOldRuntime(@TempDir Path work)
        throws Exception {
        var probe = new Probe(false);
        var stateStore = new FileEngineStateStore(work.resolve("state"));
        var artifactStore = new ArtifactStore(work.resolve("artifacts"));
        var old = install(artifactStore, work.resolve("old.bin"), probe, "1.0.0", "old-artifact");
        stateStore.save(manifest(old, graph("old")));
        var upgrade = write(work.resolve("upgrade.bin"), "new-artifact");
        var auxiliary = write(work.resolve("auxiliary.bin"), "auxiliary-artifact");
        try (var engine = engine(artifactStore, stateStore, probe, graph("old"))) {
            var initial = engine.start().block(TIMEOUT);
            var oldIdentity = initial.engine().instances().get("sample").identity();
            probe.failPrepareWhenAuxiliary = true;

            var failure = assertThrows(EngineChangeException.class, () -> engine.submit(
                deployment(initial, graph("new"), List.of(
                    artifact(ARTIFACT, upgrade, "2.0.0", probe),
                    artifact(AUXILIARY, auxiliary, "1.0.0", probe)))).block(TIMEOUT));

            assertAll(
                () -> assertFalse(failure.targetSaved()),
                () -> assertTrue(failure.view().engineDiagnostics().mutationGateOpen()),
                () -> assertEquals(graph("old"), stateStore.load().orElseThrow().desiredGraph()),
                () -> assertEquals(old.revision(), failure.view().engine().artifacts()
                    .get(ARTIFACT).revision()),
                () -> assertEquals(oldIdentity, failure.view().engine().instances().get("sample").identity()),
                () -> assertEquals(old.revision(), failure.view().engineDiagnostics().resources().get(probe.id())
                    .resources().getFirst().artifact().revision()),
                () -> assertEquals("old:call", invoke(engine, failure.view())),
                () -> assertEquals(List.of(old.revision()), artifactStore.history(ARTIFACT).stream()
                    .map(ArtifactRecord::revision).toList()),
                () -> assertTrue(artifactStore.history(AUXILIARY).isEmpty()),
                () -> assertTransactionDirectoryEmpty(work.resolve("artifacts")));
        }
    }

    @Test
    void artifactMetadataSaveFailureDoesNotPublishTheNewArtifactTarget(@TempDir Path work)
        throws Exception {
        var probe = new Probe(false);
        var statePath = work.resolve("state");
        var artifactPath = work.resolve("artifacts");
        var stateStore = new FileEngineStateStore(statePath);
        var artifactStore = new ArtifactStore(artifactPath);
        var old = install(artifactStore, work.resolve("old.bin"), probe, "1.0.0", "old-artifact");
        stateStore.save(manifest(old, graph("old")));
        var upgrade = write(work.resolve("upgrade.bin"), "new-artifact");
        var auxiliary = write(work.resolve("auxiliary.bin"), "auxiliary-artifact");
        try (var engine = engine(artifactStore, stateStore, probe, graph("old"))) {
            var initial = engine.start().block(TIMEOUT);
            probe.beforePrepare = () -> createMetadataBlocker(artifactPath, AUXILIARY);

            var failure = assertThrows(EngineChangeException.class, () -> engine.submit(
                deployment(initial, graph("new"), List.of(
                    artifact(ARTIFACT, upgrade, "2.0.0", probe),
                    artifact(AUXILIARY, auxiliary, "1.0.0", probe)))).block(TIMEOUT));

            var artifactFailure = assertInstanceOf(ArtifactException.class, failure.getCause());
            assertAll(
                () -> assertEquals(ArtifactPhase.COMMIT, artifactFailure.phase()),
                () -> assertFalse(failure.targetSaved()),
                () -> assertEquals(graph("old"), stateStore.load().orElseThrow().desiredGraph()),
                () -> assertEquals(old.revision(), stateStore.load().orElseThrow().artifacts().get(ARTIFACT)),
                () -> assertEquals(old.revision(), failure.view().engine().artifacts()
                    .get(ARTIFACT).revision()),
                () -> assertEquals("old:call", invoke(engine, failure.view())),
                () -> assertTrue(artifactStore.history(ARTIFACT).stream()
                    .anyMatch(record -> record.version().equals("2.0.0"))),
                () -> assertTrue(artifactStore.history(AUXILIARY).isEmpty()));
        }
        try (var restoredArtifacts = new ArtifactStore(artifactPath);
             var restoredState = new FileEngineStateStore(statePath);
             var restored = engine(restoredArtifacts, restoredState, new Probe(false), graph("new"))) {
            var expectedRevision = restoredState.load().orElseThrow().artifacts().get(ARTIFACT);
            var view = restored.start().block(TIMEOUT);
            assertAll(
                () -> assertEquals(graph("old"), view.engine().desiredGraph()),
                () -> assertTrue(view.engineDiagnostics().targetSatisfied()),
                () -> assertEquals(expectedRevision, view.engine().artifacts().get(ARTIFACT).revision()),
                () -> assertEquals(expectedRevision, view.engineDiagnostics().resources().get(new RuntimeId("probe"))
                    .resources().getFirst().artifact().revision()),
                () -> assertEquals("old:call", invoke(restored, view)));
        }
    }

    private static FibraEngine engine(ArtifactStore artifactStore, FileEngineStateStore stateStore,
                                      Probe probe, DesiredInputGraph source) {
        return FibraEngine.builder(new InMemoryDesiredStateRepository(source))
            .artifactStore(artifactStore).stateStore(stateStore).runtimeAdapter(probe).build();
    }

    private static ArtifactRecord install(ArtifactStore store, Path source, Probe probe,
                                          String version, String content) throws IOException {
        return store.prepareInstall(ARTIFACT, probe.id(), version, write(source, content)).save();
    }

    private static DeploymentManifest manifest(ArtifactRecord artifact, DesiredInputGraph graph) {
        return new DeploymentManifest(Map.of(artifact.id(), artifact.revision()), graph);
    }

    private static ApplyDeployment deployment(PublishedView view, DesiredInputGraph graph,
                                              Path source, Probe probe) {
        return deployment(view, graph, List.of(artifact(ARTIFACT, source, "2.0.0", probe)));
    }

    private static ApplyDeployment deployment(PublishedView view, DesiredInputGraph graph,
                                              List<DeploymentArtifact> artifacts) {
        return ApplyDeployment.builder(graph).expectedRevision(view.viewRevision())
            .expectedDesiredRevision(view.engine().desiredSource().revision())
            .artifacts(artifacts).build();
    }

    private static DeploymentArtifact artifact(ArtifactId artifactId, Path source,
                                               String version, Probe probe) {
        return DeploymentArtifact.builder().artifactId(artifactId).runtimeId(probe.id())
            .version(version).source(source).build();
    }

    private static DesiredInputGraph graph(String value) {
        return new DesiredInputGraph(List.of(DesiredInputEntry.builder("sample", "sample")
            .config(LiteralValue.of(value)).build()));
    }

    private static String invoke(FibraEngine engine, PublishedView view) {
        return engine.published().invoke(view.viewRevision(), COMMAND, CONTRIBUTION, "call").block(TIMEOUT);
    }

    private static Path write(Path path, String content) throws IOException {
        Files.writeString(path, content);
        return path;
    }

    private static void assertTransactionDirectoryEmpty(Path artifactPath) throws IOException {
        try (var transactions = Files.list(artifactPath.resolve("transactions"))) {
            assertTrue(transactions.findAny().isEmpty());
        }
    }

    private static void createMetadataBlocker(Path artifactPath, ArtifactId artifactId) {
        var encoded = java.util.HexFormat.of().formatHex(artifactId.value()
            .getBytes(StandardCharsets.UTF_8));
        try {
            Files.createFile(artifactPath.resolve("records").resolve(encoded));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static final class FailingIo implements FileEngineStateStore.StorageIo {
        boolean failBeforeReplace;
        boolean failAfterReplace;

        @Override public void force(Path path) throws IOException {
            if (failBeforeReplace && !Files.isDirectory(path)) {
                throw new IOException("staged target sync failed");
            }
            try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }

        @Override public void replace(Path staged, Path target) throws IOException {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (failAfterReplace) throw new IOException("target replacement confirmation is unknown");
        }
    }

    private static final class Probe implements PluginRuntimeAdapter {
        private final RuntimeId id = new RuntimeId("probe");
        private final AtomicInteger generation = new AtomicInteger();
        private final AtomicInteger mounts = new AtomicInteger();
        private final boolean failSecondUpdateClose;
        private boolean failPrepareWhenAuxiliary;
        private Runnable beforePrepare = () -> { };

        private Probe(boolean failSecondUpdateClose) {
            this.failSecondUpdateClose = failSecondUpdateClose;
        }

        @Override public RuntimeId id() { return id; }

        @Override public Mono<RuntimeArtifactInspection> inspect(ArtifactRecord artifact) {
            return Mono.just(new RuntimeArtifactInspection(id, artifact.id(), Map.of()));
        }

        @Override public RuntimeResourceOwner create() {
            return new RuntimeResourceOwner() {
                private RuntimeCatalog active = RuntimeCatalog.empty();
                private List<ArtifactRecord> activeArtifacts = List.of();
                private RuntimeResourceUpdate pending;

                @Override public RuntimeResourceUpdate createUpdate(List<ArtifactRecord> target) {
                    var updateGeneration = generation.incrementAndGet();
                    var next = List.copyOf(target);
                    var sample = next.stream().filter(record -> record.id().equals(ARTIFACT)).findFirst();
                    var definition = PluginDefinition.builder("sample", String.class,
                        () -> (context, config) -> {
                            mounts.incrementAndGet();
                            return context.services().require(ContributionServices.REGISTRAR).register(context,
                                COMMAND, "sample", "run", "Run", (invocation, input) ->
                                    Mono.just(config + ":" + input)).then();
                        }).require(ContributionServices.REGISTRAR).build();
                    var catalog = sample.isEmpty() ? RuntimeCatalog.empty() : new RuntimeCatalog(
                        PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> (String) value)),
                        Map.of(definition.name(), sample.orElseThrow().id()));
                    var update = new RuntimeResourceUpdate() {
                        @Override public Mono<Void> prepareAsync() {
                            return Mono.fromRunnable(beforePrepare).then(failPrepareWhenAuxiliary
                                && next.stream().anyMatch(record -> record.id().equals(AUXILIARY))
                                ? Mono.<Void>error(new IllegalStateException("auxiliary runtime preparation failed"))
                                : Mono.empty());
                        }
                        @Override public Set<ArtifactId> affectedArtifacts() {
                            return next.stream().map(ArtifactRecord::id).collect(java.util.stream.Collectors.toSet());
                        }
                        @Override public RuntimeCatalog catalog() { return catalog; }
                        @Override public RuntimeResourceSnapshot snapshot() {
                            return Probe.this.snapshot(next, RuntimeResourceSnapshot.State.PREPARED);
                        }
                        @Override public void adopt() {
                            active = catalog;
                            activeArtifacts = next;
                        }
                        private final Mono<Void> close = Mono.defer(() ->
                            failSecondUpdateClose && updateGeneration == 2
                                ? Mono.<Void>error(new IllegalStateException("retirement failed"))
                                : Mono.empty()).cache();
                        @Override public Mono<Void> closeAsync() { return close; }
                    };
                    pending = update;
                    return update;
                }

                @Override public RuntimeCatalog catalog() { return active; }
                @Override public RuntimeResourceSnapshot snapshot() {
                    return Probe.this.snapshot(activeArtifacts, RuntimeResourceSnapshot.State.ACTIVE);
                }
                @Override public Mono<Void> closeAsync() {
                    return pending == null ? Mono.empty() : pending.closeAsync();
                }
            };
        }

        private RuntimeResourceSnapshot snapshot(List<ArtifactRecord> artifacts,
                                                 RuntimeResourceSnapshot.State state) {
            return new RuntimeResourceSnapshot(id, artifacts.stream().map(artifact ->
                RuntimeResourceSnapshot.Resource.builder().artifact(artifact).identity(artifact.revision())
                    .state(state).build()).toList());
        }
    }
}
