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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraEngineRuntimeAdapterTest {
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
            assertEquals(java.util.Set.of("sample"),
                installed.engine().runtimes().get(runtimeId).definitions());
            assertEquals(com.sstlfsj.fibra.artifact.ArtifactState.INSTALLED,
                installed.engine().runtimes().get(runtimeId).artifacts().get(artifact).state());
            assertEquals(1, adapter.commits.get());
            assertEquals(1, adapter.retires.get());

            var removed = engine.submit(new UninstallArtifact(
                installed.viewRevision(), artifact)).block().view();

            assertFalse(removed.engine().artifacts().containsKey(artifact));
            assertFalse(removed.engine().runtimes().containsKey(runtimeId));
            assertEquals(2, adapter.commits.get());
            assertEquals(2, adapter.retires.get());
        }
    }

    @Test
    void startIsLazyRecoversInstalledArtifactsAndCloseOwnsRuntimeAdapters(
        @TempDir Path work) throws Exception {
        var artifact = new ArtifactId("sample");
        var runtimeId = new RuntimeId("fake");
        var source = work.resolve("sample.bin");
        Files.writeString(source, "plugin");
        var store = new ArtifactStore(work.resolve("artifacts"));
        store.prepareInstall(artifact, runtimeId, "1.0.0", source).commit();
        var repository = InMemoryDesiredStateRepository.empty();
        var adapter = new FakeRuntimeAdapter(runtimeId);
        var engine = FibraEngine.builder(repository).artifactStore(store)
            .runtimeAdapter(adapter).build();

        assertEquals(EngineState.NEW, engine.published().current().engine().state());
        assertEquals(0, adapter.commits.get());
        var started = engine.start().block().engine();
        assertTrue(started.artifacts().containsKey(artifact));
        assertTrue(started.runtimes().containsKey(runtimeId));

        engine.close();
        assertEquals(1, adapter.closes.get());
    }

    @Test
    void rejectsMissingAndMismatchedRuntimeIdsWithoutPublishing(@TempDir Path work)
        throws Exception {
        var source = work.resolve("sample.bin");
        Files.writeString(source, "plugin");
        var store = new ArtifactStore(work.resolve("artifacts"));
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(store).build()) {
            var started = engine.start().block();

            assertThrows(UnknownRuntimeException.class, () -> engine.submit(
                InstallArtifact.builder().expectedRevision(started.viewRevision())
                    .artifactId(new ArtifactId("sample"))
                    .runtimeId(new RuntimeId("missing")).version("1.0.0")
                    .source(source).build()).block());

            assertEquals(started, engine.published().current());
            assertTrue(store.find(new ArtifactId("sample")).isEmpty());
        }
    }

    @Test
    void appliesArtifactsAndDesiredGraphInOneChangeSet(@TempDir Path work)
        throws Exception {
        var runtimeId = new RuntimeId("fake");
        var artifactId = new ArtifactId("sample");
        var source = work.resolve("sample.bin");
        Files.writeString(source, "plugin");
        var journal = new InMemoryTransactionJournal();
        var repository = InMemoryDesiredStateRepository.empty();
        try (var engine = FibraEngine.builder(repository)
            .artifactStore(new ArtifactStore(work.resolve("artifacts")))
            .runtimeAdapter(new FakeRuntimeAdapter(runtimeId)).journal(journal).build()) {
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
            assertEquals(2, journal.records().stream()
                .map(TransactionRecord::transactionId).distinct().count());
        }
    }

    @Test
    void restartsFromACommittedDiskDecisionAndInstalledArtifact(@TempDir Path work)
        throws Exception {
        var runtimeId = new RuntimeId("fake");
        var artifactId = new ArtifactId("sample");
        var source = work.resolve("sample.bin");
        var artifactRoot = work.resolve("artifacts");
        var journalRoot = work.resolve("transactions");
        Files.writeString(source, "plugin");
        try (var store = new ArtifactStore(artifactRoot)) {
            store.prepareInstall(artifactId, runtimeId, "1.0.0", source).commit();
        }
        try (var journal = new FileTransactionJournal(journalRoot)) {
            journal.append(new TransactionRecord("interrupted-deployment",
                TransactionState.COMMITTED, List.of("artifact", "runtime:fake"),
                null, Instant.now()));
        }

        var recoveredJournal = new FileTransactionJournal(journalRoot);
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(new ArtifactStore(artifactRoot))
            .runtimeAdapter(new FakeRuntimeAdapter(runtimeId))
            .journal(recoveredJournal).build()) {
            var recovered = engine.start().block().engine();

            assertTrue(recovered.artifacts().containsKey(artifactId));
            assertTrue(recovered.runtimes().containsKey(runtimeId));
            assertEquals(TransactionState.RETIRED,
                recoveredJournal.records().getLast().state());
        }
    }

    private static final class FakeRuntimeAdapter implements PluginRuntimeAdapter {
        private final RuntimeId id;
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger retires = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

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
        public Mono<PreparedRuntimeGeneration> prepare(RuntimeChangeRequest request) {
            var definition = PluginDefinition.builder("sample", Void.class,
                () -> (context, config) -> Mono.empty()).build();
            var catalog = request.artifacts().isEmpty() ? PluginCatalog.empty()
                : PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> null));
            var snapshot = new RuntimeGenerationSnapshot(id,
                Integer.toString(commits.get() + 1),
                request.artifacts().stream().collect(java.util.stream.Collectors.toMap(
                    ArtifactRecord::id, value -> value)),
                catalog.entries().stream().map(value -> value.definition().name())
                    .collect(java.util.stream.Collectors.toSet()));
            return Mono.just(new PreparedRuntimeGeneration() {
                @Override
                public RuntimeGenerationSnapshot snapshot() {
                    return snapshot;
                }

                @Override
                public PluginCatalog catalog() {
                    return catalog;
                }

                @Override
                public Mono<Void> commit() {
                    return Mono.fromRunnable(commits::incrementAndGet);
                }

                @Override
                public Mono<Void> rollback() {
                    return Mono.empty();
                }

                @Override
                public Mono<Void> retire() {
                    return Mono.fromRunnable(retires::incrementAndGet);
                }
            });
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
