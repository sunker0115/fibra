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

class RuntimeResourceOwnershipTest {
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
            var notified = engine.published().views().filter(view -> !view.viewRevision()
                .equals(started.viewRevision())).next().doOnNext(ignored -> engine.close()).toFuture();
            var result = engine.submit(new RefreshDesired(null)).block(TIMEOUT);
            notified.get(5, TimeUnit.SECONDS);
            assertNotEquals(started.viewRevision(), result.view().viewRevision());
            assertEquals(EngineState.CLOSED, engine.published().current().engine().state());
        }
    }

    @Test
    void shutdownWaitsForRuntimeUpdatePreparationBeforeClosingItsOwner(@TempDir Path work) throws Exception {
        var adapter = new Probe();
        var engine = engine(work, adapter);
        var started = engine.start().block(TIMEOUT);
        adapter.calls.clear();
        adapter.prepareGate = Sinks.one();
        adapter.prepareEntered = new java.util.concurrent.CountDownLatch(1);
        var command = engine.submit(replaceArtifact(started, graph("new"), work, adapter)).toFuture();
        assertTrue(adapter.prepareEntered.await(5, TimeUnit.SECONDS));
        var closingEntered = new java.util.concurrent.CountDownLatch(1);
        var closing = java.util.concurrent.CompletableFuture.runAsync(() -> {
            closingEntered.countDown();
            engine.close();
        });
        try {
            assertTrue(closingEntered.await(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                () -> closing.get(100, TimeUnit.MILLISECONDS));
            assertEquals(2, adapter.prepared.get());
            assertTrue(adapter.calls.isEmpty());
            var rejected = assertThrows(IllegalStateException.class, () ->
                engine.published().invoke(engine.published().current().viewRevision(),
                    COMMAND, CONTRIBUTION, "late").block(TIMEOUT));
            assertEquals("engine is closing", rejected.getMessage());
            adapter.prepareGate.tryEmitEmpty();
            command.get(5, TimeUnit.SECONDS);
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(2, adapter.prepared.get());
            assertTrue(adapter.calls.contains("domain:1"));
            assertTrue(adapter.calls.contains("runtime-owner"));
            assertEquals(EngineState.CLOSED, engine.published().current().engine().state());
        } finally {
            adapter.prepareGate.tryEmitEmpty();
            engine.close();
        }
    }

    @Test
    void interruptedShutdownWaiterDoesNotCloseAStillPreparingCandidate(@TempDir Path work)
        throws Exception {
        var adapter = new Probe();
        var engine = engine(work, adapter);
        var started = engine.start().block(TIMEOUT);
        adapter.calls.clear();
        adapter.prepareGate = Sinks.one();
        adapter.prepareEntered = new java.util.concurrent.CountDownLatch(1);
        var command = engine.submit(replaceArtifact(started, graph("new"), work, adapter)).toFuture();
        assertTrue(adapter.prepareEntered.await(5, TimeUnit.SECONDS));
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
            assertEquals(2, adapter.prepared.get());
            assertTrue(adapter.calls.isEmpty(), "shutdown must still be waiting for preparation");
            adapter.prepareGate.tryEmitEmpty();
            command.get(5, TimeUnit.SECONDS);
            engine.close();
            assertTrue(adapter.calls.contains("runtime-owner"));
        } finally {
            adapter.prepareGate.tryEmitEmpty();
            thread.join(5000);
            try { engine.close(); } catch (RuntimeException expectedBeforeFix) { }
        }
    }

    @Test
    void stateStoreCloseFailureDoesNotSkipArtifactStoreRelease(@TempDir Path work) {
        var store = new ArtifactStore(work.resolve("store"));
        var stateStoreFailure = new IllegalStateException("state store close failed");
        var stateStore = new EngineStateStore() {
            @Override public java.util.Optional<DeploymentManifest> load() { return java.util.Optional.empty(); }
            @Override public void save(DeploymentManifest manifest) { }
            @Override public void close() { throw stateStoreFailure; }
        };
        var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(store).stateStore(stateStore).build();
        try {
            engine.start().block(TIMEOUT);
            var failure = assertThrows(RuntimeException.class, engine::close);
            assertAll(
                () -> assertSame(stateStoreFailure, failure),
                () -> assertSame(failure, assertThrows(RuntimeException.class, engine::close)),
                () -> assertDoesNotThrow(() -> {
                    try (var reopened = new ArtifactStore(work.resolve("store"))) {
                        assertTrue(reopened.history(new ArtifactId("sample")).isEmpty());
                    }
                }));
        } finally {
            try { engine.close(); } catch (RuntimeException expectedCleanupFailure) { }
            store.close();
        }
    }

    @Test
    void retiredRuntimeResourcesCloseOnlyAfterInvocationAndDomainCleanup(@TempDir Path work) throws Exception {
        var adapter = new Probe();
        adapter.response = Sinks.one();
        try (var engine = engine(work, adapter)) {
            var first = engine.start().block(TIMEOUT);
            adapter.calls.clear();
            var invocation = engine.published().invoke(first.viewRevision(), COMMAND, CONTRIBUTION, "x")
                .toFuture();
            var next = engine.published().views().filter(view -> !view.viewRevision()
                .equals(first.viewRevision())).next().toFuture();
            var replacement = engine.submit(replaceArtifact(first, graph("new"), work, adapter)).toFuture();
            try {
                next.get(5, TimeUnit.SECONDS);
                assertFalse(replacement.isDone());
                assertTrue(adapter.calls.isEmpty());
                adapter.response.tryEmitValue("done");
                assertEquals("old:x", invocation.get(5, TimeUnit.SECONDS));
                replacement.get(5, TimeUnit.SECONDS);
                assertTrue(adapter.calls.contains("domain:1"));
                assertTrue(adapter.calls.contains("runtime-update:2"));
            } finally {
                adapter.response.tryEmitValue("done");
            }
        }
        assertTrue(adapter.calls.contains("runtime-owner"));
    }

    @Test
    void bindingFailureClosesOnlyTheCandidateRuntimeUpdate(@TempDir Path work) throws Exception {
        var adapter = new Probe();
        try (var engine = engine(work, adapter)) {
            var first = engine.start().block(TIMEOUT);
            var invalid = new DesiredInputGraph(List.of(DesiredInputEntry.builder("p", "sample")
                .config(LiteralValue.of(123)).build()));
            assertThrows(EngineChangeException.class, () -> engine.submit(
                replaceArtifact(first, invalid, work, adapter)).block(TIMEOUT));

            var current = engine.published().current();
            assertEquals(first.engine().artifacts(), current.engine().artifacts());
            assertTrue(adapter.calls.contains("runtime-update:2"));
            assertEquals("old:x", engine.published().invoke(current.viewRevision(), COMMAND, CONTRIBUTION, "x")
                .block(TIMEOUT));
        }
    }

    @Test
    void failedCandidateCleanupRetainsItsArtifactAndItsEngineOwner(@TempDir Path work) throws Exception {
        var adapter = new Probe();
        var stateStore = new RecordingStateStore();
        var engine = engine(work, adapter, stateStore);
        var first = engine.start().block(TIMEOUT);
        var savedTarget = stateStore.load().orElseThrow();
        adapter.closeFails = 2;
        var upgraded = work.resolve("upgrade.bin");
        Files.writeString(upgraded, "different content");
        var invalid = new DesiredInputGraph(List.of(DesiredInputEntry.builder("p", "sample")
            .config(LiteralValue.of(123)).build()));
        var command = ApplyDeployment.builder(invalid).expectedRevision(first.viewRevision())
            .expectedDesiredRevision(first.engine().desiredSource().revision())
            .artifacts(List.of(DeploymentArtifact.builder().artifactId(new ArtifactId("sample"))
                .runtimeId(adapter.id()).version("2.0.0").source(upgraded).build())).build();
        try {
            assertThrows(EngineChangeException.class, () -> engine.submit(command).block(TIMEOUT));
            var current = engine.published().current();
            assertAll(
                () -> assertTrue(Files.exists(adapter.lastArtifact), "unclosed candidate still owns these bytes"),
                () -> assertEquals("different content", Files.readString(adapter.lastArtifact)),
                () -> assertEquals(savedTarget, stateStore.load().orElseThrow()),
                () -> assertEquals(savedTarget.artifacts(), current.engine().artifacts().entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().revision()))),
                () -> assertEquals("old:x", engine.published().invoke(current.viewRevision(), COMMAND,
                    CONTRIBUTION, "x").block(TIMEOUT)),
                () -> assertFalse(current.engineDiagnostics().mutationGateOpen()),
                () -> assertThrows(RuntimeException.class, engine::close));
        } finally {
            try { engine.close(); } catch (RuntimeException expectedCleanupFailure) { }
        }
        assertTrue(adapter.calls.contains("runtime-update:2"));
    }

    @Test
    void retirementFailureAfterTargetSaveClosesTheMutationGateWithoutForcingItsOwner(@TempDir Path work)
        throws Exception {
        var adapter = new Probe();
        var stateStore = new RecordingStateStore();
        var engine = engine(work, adapter, stateStore);
        var first = engine.start().block(TIMEOUT);
        adapter.closeFails = 2;
        var failure = assertThrows(EngineChangeException.class, () -> engine.submit(
            replaceArtifact(first, graph("new"), work, adapter)).block(TIMEOUT));
        assertTrue(failure.targetSaved());
        var failed = failure.view();
        assertEquals(EngineState.RUNNING, failed.engine().state());
        assertEquals(ChangePhase.FAILED, failed.engineDiagnostics().phase());
        assertFalse(failed.engineDiagnostics().mutationGateOpen());
        assertEquals(stateStore.load().orElseThrow().artifacts(), failed.engine().artifacts().entrySet()
            .stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                entry -> entry.getValue().revision())));
        assertThrows(MutationGateClosedException.class,
            () -> engine.submit(new RefreshDesired(null)).block(TIMEOUT));

        var closeFailure = assertThrows(RuntimeException.class, engine::close);
        assertTrue(adapter.calls.contains("runtime-update:2"));
        assertFalse(adapter.calls.contains("runtime-owner"),
            "owner must remain while its failed update still owns dependent resources");
        assertSame(closeFailure, assertThrows(RuntimeException.class, engine::close));
    }

    private static ApplyDeployment replaceArtifact(PublishedView view, DesiredInputGraph graph,
                                                   Path work, Probe adapter) throws Exception {
        var source = work.resolve("upgrade-" + System.nanoTime() + ".bin");
        Files.writeString(source, "upgrade");
        return ApplyDeployment.builder(graph).expectedRevision(view.viewRevision())
            .expectedDesiredRevision(view.engine().desiredSource().revision())
            .artifacts(List.of(DeploymentArtifact.builder().artifactId(new ArtifactId("sample"))
                .runtimeId(adapter.id()).version("2.0.0").source(source).build())).build();
    }

    private static DesiredInputGraph graph(String value) {
        return new DesiredInputGraph(List.of(DesiredInputEntry.builder("p", "sample")
            .config(LiteralValue.of(value)).build()));
    }

    private static FibraEngine engine(Path work, Probe adapter) throws Exception {
        return engine(work, adapter, EngineStateStore.inMemory());
    }

    private static FibraEngine engine(Path work, Probe adapter, EngineStateStore stateStore) throws Exception {
        var source = work.resolve("plugin.bin");
        Files.writeString(source, "plugin");
        var store = new ArtifactStore(work.resolve("store"));
        var saved = store.prepareInstall(new ArtifactId("sample"), adapter.id(), "1.0.0", source).save();
        stateStore.save(new DeploymentManifest(Map.of(saved.id(), saved.revision()), graph("old")));
        return FibraEngine.builder(new InMemoryDesiredStateRepository(graph("old")))
            .artifactStore(store).runtimeAdapter(adapter).stateStore(stateStore).build();
    }

    private static final class Probe implements PluginRuntimeAdapter {
        private Sinks.One<Void> prepareGate;
        private java.util.concurrent.CountDownLatch prepareEntered;
        private final AtomicInteger prepared = new AtomicInteger();
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private Sinks.One<String> response;
        private int closeFails;
        private Path lastArtifact;

        @Override public RuntimeId id() { return new RuntimeId("probe"); }
        @Override public Mono<DeploymentArtifact> probe(com.sstlfsj.fibra.artifact.ArtifactPackage artifact) {
            return Mono.error(new AssertionError("ownership tests do not probe installation packages"));
        }
        @Override public Mono<RuntimeArtifactInspection> inspect(ArtifactRecord artifact) {
            return Mono.just(new RuntimeArtifactInspection(id(), artifact.id(), Map.of()));
        }
        @Override public RuntimeResourceOwner create() {
            return new RuntimeResourceOwner() {
                private RuntimeCatalog active = RuntimeCatalog.empty();
                private List<ArtifactRecord> activeArtifacts = List.of();
                private RuntimeResourceUpdate pending;
                private final Mono<Void> close = Mono.defer(() -> pending == null ? Mono.<Void>empty()
                    : pending.closeAsync()).then(Mono.<Void>fromRunnable(() -> calls.add("runtime-owner"))).cache();

                @Override public RuntimeResourceUpdate createUpdate(List<ArtifactRecord> target) {
                    var generation = prepared.incrementAndGet();
                    var next = List.copyOf(target);
                    lastArtifact = next.isEmpty() ? null : next.getFirst().location();
                    var definition = PluginDefinition.builder("sample", String.class,
                        () -> (context, config) -> {
                            context.effects().add(Disposables.from(() -> calls.add("domain:" + generation)));
                            return context.services().require(ContributionServices.REGISTRAR).register(context,
                                COMMAND, "p", "run", "Run", (invocation, input) -> {
                                    var result = config + ":" + input;
                                    return generation == 1 && response != null
                                        ? response.asMono().thenReturn(result) : Mono.just(result);
                                }).then();
                        }).require(ContributionServices.REGISTRAR).build();
                    var catalog = next.isEmpty() ? RuntimeCatalog.empty() : new RuntimeCatalog(
                        PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> (String) value)),
                        Map.of(definition.name(), next.getFirst().id()));
                    var update = new RuntimeResourceUpdate() {
                        @Override public Mono<Void> prepareAsync() {
                            if (prepareGate == null) return Mono.empty();
                            return Mono.defer(() -> {
                                prepareEntered.countDown();
                                return prepareGate.asMono();
                            });
                        }
                        @Override public Set<ArtifactId> affectedArtifacts() {
                            return next.stream().map(ArtifactRecord::id)
                                .collect(java.util.stream.Collectors.toSet());
                        }
                        @Override public RuntimeCatalog catalog() { return catalog; }
                        @Override public RuntimeResourceSnapshot snapshot() {
                            return Probe.this.snapshot(next, RuntimeResourceSnapshot.State.PREPARED);
                        }
                        @Override public void adopt() {
                            active = catalog;
                            activeArtifacts = next;
                        }
                        private final Mono<Void> close = Mono.<Void>defer(() -> {
                            calls.add("runtime-update:" + generation);
                            return generation == closeFails
                                ? Mono.error(new IllegalStateException("close failed")) : Mono.empty();
                        }).cache();
                        @Override public Mono<Void> closeAsync() { return close; }
                    };
                    pending = update;
                    return update;
                }

                @Override public RuntimeCatalog catalog() { return active; }
                @Override public RuntimeResourceSnapshot snapshot() {
                    return Probe.this.snapshot(activeArtifacts, RuntimeResourceSnapshot.State.ACTIVE);
                }
                @Override public Mono<Void> closeAsync() { return close; }
            };
        }

        private RuntimeResourceSnapshot snapshot(List<ArtifactRecord> artifacts,
                                                 RuntimeResourceSnapshot.State state) {
            return new RuntimeResourceSnapshot(id(), artifacts.stream().map(artifact ->
                RuntimeResourceSnapshot.Resource.builder().artifact(artifact).identity(artifact.revision())
                    .state(state).build()).toList());
        }
    }

    private static final class RecordingStateStore implements EngineStateStore {
        private DeploymentManifest manifest;
        @Override public java.util.Optional<DeploymentManifest> load() {
            return java.util.Optional.ofNullable(manifest);
        }
        @Override public void save(DeploymentManifest value) { manifest = value; }
    }
}
