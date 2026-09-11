package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.artifact.ArtifactId;
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
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeGenerationOwnershipTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ContributionKind<String, String, String> COMMAND =
        ContributionKind.local("command", String.class, String.class, String.class);
    private static final ContributionId CONTRIBUTION = new ContributionId("p", "run");

    @Test
    void commandResultCallbackCanCloseTheEngine() {
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty()).build()) {
            engine.start().block(TIMEOUT);
            engine.submit(new RefreshDesired(null)).doOnSuccess(ignored -> engine.close()).block(TIMEOUT);
            assertEquals(EngineState.CLOSED, engine.published().current().engine().state());
        }
    }

    @RepeatedTest(20)
    void closedViewCallbackCanRepeatCloseWithoutWaitingForItself() throws Exception {
        var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty()).build();
        engine.start().block(TIMEOUT);
        var notified = engine.published().views().filter(view -> view.engine().state() == EngineState.CLOSED)
            .next().doOnNext(ignored -> engine.close()).toFuture();
        engine.close();
        assertEquals(EngineState.CLOSED, notified.get(5, TimeUnit.SECONDS).engine().state());
    }

    @Test
    void publicationViewCallbackCanCloseTheEngine() throws Exception {
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty()).build()) {
            var started = engine.start().block(TIMEOUT);
            var notified = engine.published().views().filter(view -> !view.generationRevision()
                .equals(started.generationRevision())).next().doOnNext(ignored -> engine.close()).toFuture();
            var result = engine.submit(new RefreshDesired(null)).block(TIMEOUT);
            notified.get(5, TimeUnit.SECONDS);
            assertNotEquals(started.generationRevision(), result.view().generationRevision());
            assertEquals(EngineState.CLOSED, engine.published().current().engine().state());
        }
    }

    @Test
    void shutdownWaitsForInspectionBeforeClosingTheResultingGeneration(@TempDir Path work) throws Exception {
        var adapter = new Probe();
        var engine = engine(work, adapter);
        var started = engine.start().block(TIMEOUT);
        adapter.inspectionGate = Sinks.one();
        adapter.inspectionEntered = new java.util.concurrent.CountDownLatch(1);
        var command = engine.submit(replace(started, graph("new"))).toFuture();
        assertTrue(adapter.inspectionEntered.await(5, TimeUnit.SECONDS));
        var closingEntered = new java.util.concurrent.CountDownLatch(1);
        var closing = java.util.concurrent.CompletableFuture.runAsync(() -> {
            closingEntered.countDown();
            engine.close();
        });
        try {
            assertTrue(closingEntered.await(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                () -> closing.get(100, TimeUnit.MILLISECONDS));
            assertEquals(1, adapter.prepared.get());
            assertTrue(adapter.calls.isEmpty());
            var rejected = assertThrows(IllegalStateException.class, () ->
                engine.published().invoke(engine.published().current().viewRevision(),
                    COMMAND, CONTRIBUTION, "late").block(TIMEOUT));
            assertEquals("engine is closing", rejected.getMessage());
            adapter.inspectionGate.tryEmitEmpty();
            command.get(5, TimeUnit.SECONDS);
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(2, adapter.prepared.get());
            assertEquals(List.of("domain:1", "runtime:1", "domain:2", "runtime:2"), adapter.calls);
            assertEquals(EngineState.CLOSED, engine.published().current().engine().state());
        } finally {
            adapter.inspectionGate.tryEmitEmpty();
            engine.close();
        }
    }

    @Test
    void interruptedShutdownWaiterDoesNotCloseAStillPreparingCandidate(@TempDir Path work)
        throws Exception {
        var adapter = new Probe();
        var engine = engine(work, adapter);
        var started = engine.start().block(TIMEOUT);
        adapter.inspectionGate = Sinks.one();
        adapter.inspectionEntered = new java.util.concurrent.CountDownLatch(1);
        var command = engine.submit(replace(started, graph("new"))).toFuture();
        assertTrue(adapter.inspectionEntered.await(5, TimeUnit.SECONDS));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var waiter = new java.util.concurrent.CompletableFuture<Throwable>();
        var thread = Thread.ofPlatform().daemon().start(() -> {
            entered.countDown();
            try {
                engine.close();
                waiter.complete(null);
            } catch (Throwable failure) {
                waiter.complete(failure);
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                () -> waiter.get(100, TimeUnit.MILLISECONDS));
            thread.interrupt();
            assertNotNull(waiter.get(5, TimeUnit.SECONDS));
            assertEquals(1, adapter.prepared.get());
            assertTrue(adapter.calls.isEmpty(), "shutdown must still be waiting for preparation");
            adapter.inspectionGate.tryEmitEmpty();
            command.get(5, TimeUnit.SECONDS);
            engine.close();
            assertEquals(List.of("domain:1", "runtime:1", "domain:2", "runtime:2"), adapter.calls);
        } finally {
            adapter.inspectionGate.tryEmitEmpty();
            thread.join(5000);
            try { engine.close(); } catch (RuntimeException expectedBeforeFix) { }
        }
    }

    @Test
    void journalCloseFailureDoesNotSkipArtifactStoreRelease(@TempDir Path work) {
        var store = new ArtifactStore(work.resolve("store"));
        var journalFailure = new IllegalStateException("journal close failed");
        var journal = new TransactionJournal() {
            private final InMemoryTransactionJournal delegate = new InMemoryTransactionJournal();
            @Override public void append(TransactionRecord record) { delegate.append(record); }
            @Override public List<TransactionRecord> records() { return delegate.records(); }
            @Override public void close() { throw journalFailure; }
        };
        var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(store).journal(journal).build();
        try {
            engine.start().block(TIMEOUT);
            var failure = assertThrows(RuntimeException.class, engine::close);
            assertAll(
                () -> assertTrue(List.of(failure.getSuppressed()).contains(journalFailure)),
                () -> assertSame(failure, assertThrows(RuntimeException.class, engine::close)),
                () -> assertDoesNotThrow(() -> {
                    try (var reopened = new ArtifactStore(work.resolve("store"))) {
                        assertTrue(reopened.installed().isEmpty());
                    }
                }));
        } finally {
            try { engine.close(); } catch (RuntimeException expectedCleanupFailure) { }
            store.close();
        }
    }

    @Test
    void oldRuntimeClosesOnlyAfterInvocationAndDomainCleanup(@TempDir Path work) throws Exception {
        var adapter = new Probe();
        adapter.response = Sinks.one();
        try (var engine = engine(work, adapter)) {
            var first = engine.start().block(TIMEOUT);
            var invocation = engine.published().invoke(first.viewRevision(), COMMAND, CONTRIBUTION, "x")
                .toFuture();
            var next = engine.published().views().filter(view -> !view.generationRevision()
                .equals(first.generationRevision())).next().toFuture();
            var replacement = engine.submit(replace(first, graph("new"))).toFuture();
            try {
                next.get(5, TimeUnit.SECONDS);
                assertFalse(replacement.isDone());
                assertTrue(adapter.calls.isEmpty());
                adapter.response.tryEmitValue("done");
                assertEquals("old:x", invocation.get(5, TimeUnit.SECONDS));
                replacement.get(5, TimeUnit.SECONDS);
                assertEquals(List.of("domain:1", "runtime:1"), adapter.calls);
            } finally {
                adapter.response.tryEmitValue("done");
            }
        }
        assertEquals(List.of("domain:1", "runtime:1", "domain:2", "runtime:2"), adapter.calls);
    }

    @Test
    void bindingFailureClosesOnlyTheCandidateRuntime(@TempDir Path work) throws Exception {
        var adapter = new Probe();
        try (var engine = engine(work, adapter)) {
            var first = engine.start().block(TIMEOUT);
            var invalid = new DesiredInputGraph(List.of(DesiredInputEntry.builder("p", "sample")
                .config(LiteralValue.of(123)).build()));
            assertThrows(ChangeSetException.class, () -> engine.submit(replace(first, invalid)).block(TIMEOUT));

            var current = engine.published().current();
            assertEquals(first.generationRevision(), current.generationRevision());
            assertEquals(List.of("runtime:2"), adapter.calls);
            assertEquals("old:x", engine.published().invoke(current.viewRevision(), COMMAND, CONTRIBUTION, "x")
                .block(TIMEOUT));
        }
    }

    @Test
    void failedCandidateCleanupRetainsItsArtifactAndItsEngineOwner(@TempDir Path work) throws Exception {
        var adapter = new Probe();
        adapter.closeFails = 2;
        var engine = engine(work, adapter);
        var first = engine.start().block(TIMEOUT);
        var upgraded = work.resolve("upgrade.bin");
        Files.writeString(upgraded, "different content");
        var invalid = new DesiredInputGraph(List.of(DesiredInputEntry.builder("p", "sample")
            .config(LiteralValue.of(123)).build()));
        var command = ApplyDeployment.builder(invalid).expectedRevision(first.viewRevision())
            .expectedDesiredRevision(first.engine().desiredSource().revision())
            .artifacts(List.of(DeploymentArtifact.builder().artifactId(new ArtifactId("sample"))
                .runtimeId(adapter.id()).version("2.0.0").source(upgraded).build())).build();
        try {
            assertThrows(ChangeSetException.class, () -> engine.submit(command).block(TIMEOUT));
            var current = engine.published().current();
            assertAll(
                () -> assertTrue(Files.exists(adapter.lastArtifact), "unclosed candidate still owns these bytes"),
                () -> assertEquals(first.generationRevision(), current.generationRevision()),
                () -> assertEquals("old:x", engine.published().invoke(current.viewRevision(), COMMAND,
                    CONTRIBUTION, "x").block(TIMEOUT)),
                () -> assertFalse(current.engineDiagnostics().mutationGateOpen()),
                () -> assertThrows(RuntimeException.class, engine::close));
        } finally {
            try { engine.close(); } catch (RuntimeException expectedCleanupFailure) { }
        }
        assertEquals(List.of("runtime:2", "domain:1", "runtime:1"), adapter.calls);
    }

    @Test
    void retirementFailurePreservesDrainingAndCloseStillAttemptsTheCurrentRuntime(@TempDir Path work)
        throws Exception {
        var adapter = new Probe();
        adapter.closeFails = 1;
        var engine = engine(work, adapter);
        var first = engine.start().block(TIMEOUT);
        var result = engine.submit(replace(first, graph("new"))).block(TIMEOUT);
        assertNotEquals(first.generationRevision(), result.view().generationRevision());
        assertFalse(result.warnings().isEmpty());
        assertEquals(List.of(first.generationRevision()), result.view().engineDiagnostics().drainingGenerationRevisions());
        assertFalse(result.view().engineDiagnostics().mutationGateOpen());
        assertThrows(MutationGateClosedException.class,
            () -> engine.submit(new RefreshDesired(null)).block(TIMEOUT));

        var failure = assertThrows(RuntimeException.class, engine::close);
        assertEquals(List.of("domain:1", "runtime:1", "domain:2", "runtime:2"), adapter.calls);
        assertSame(failure, assertThrows(RuntimeException.class, engine::close));
    }

    private static ReplaceDesiredGraph replace(PublishedView view, DesiredInputGraph graph) {
        return new ReplaceDesiredGraph(view.viewRevision(), view.engine().desiredSource().revision(), graph);
    }

    private static DesiredInputGraph graph(String value) {
        return new DesiredInputGraph(List.of(DesiredInputEntry.builder("p", "sample")
            .config(LiteralValue.of(value)).build()));
    }

    private static FibraEngine engine(Path work, Probe adapter) throws Exception {
        var source = work.resolve("plugin.bin");
        Files.writeString(source, "plugin");
        var store = new ArtifactStore(work.resolve("store"));
        store.prepareInstall(new ArtifactId("sample"), adapter.id(), "1.0.0", source).commit();
        return FibraEngine.builder(new InMemoryDesiredStateRepository(graph("old")))
            .artifactStore(store).runtimeAdapter(adapter).build();
    }

    private static final class Probe implements PluginRuntimeAdapter {
        private Sinks.One<Void> inspectionGate;
        private java.util.concurrent.CountDownLatch inspectionEntered;
        private final AtomicInteger prepared = new AtomicInteger();
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private Sinks.One<String> response;
        private int closeFails;
        private Path lastArtifact;

        @Override public RuntimeId id() { return new RuntimeId("probe"); }
        @Override public Mono<RuntimeArtifactInspection> inspect(ArtifactRecord artifact) {
            var inspection = new RuntimeArtifactInspection(id(), artifact.id(), Map.of());
            if (inspectionGate == null) return Mono.just(inspection);
            return Mono.defer(() -> {
                inspectionEntered.countDown();
                return inspectionGate.asMono().thenReturn(inspection);
            });
        }
        @Override public RuntimeGeneration create(RuntimeGenerationRequest request) {
            int generation = prepared.incrementAndGet();
            lastArtifact = request.artifacts().getFirst().location();
            var definition = PluginDefinition.builder("sample", String.class, () -> (context, config) -> {
                context.effects().add(Disposables.from(() -> calls.add("domain:" + generation)));
                return context.services().require(ContributionServices.REGISTRAR).register(context,
                    COMMAND, "p", "run", "Run", (invocation, input) -> {
                        var result = config + ":" + input;
                        return generation == 1 && response != null ? response.asMono().thenReturn(result)
                            : Mono.just(result);
                    }).then();
            }).require(ContributionServices.REGISTRAR).build();
            var catalog = PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> (String) value));
            return new RuntimeGeneration() {
                @Override public Mono<Void> prepareAsync() { return Mono.empty(); }
                private final Mono<Void> close = Mono.<Void>defer(() -> {
                    calls.add("runtime:" + generation);
                    return generation == closeFails ? Mono.error(new IllegalStateException("close failed"))
                        : Mono.empty();
                }).cache();
                @Override public RuntimeGenerationSnapshot snapshot() {
                    return new RuntimeGenerationSnapshot(id(), Integer.toString(generation),
                        Map.of(request.artifacts().getFirst().id(), request.artifacts().getFirst()), Set.of("sample"));
                }
                @Override public PluginCatalog catalog() { return catalog; }
                @Override public Mono<Void> closeAsync() { return close; }
            };
        }
    }
}
