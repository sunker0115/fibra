package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstance;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.config.PublicationRequirement;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDisableTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void rootRequestPersistsDisabledTargetRemovesOnlyTargetAndDrainsItsEffects() {
        var targetDisable = new AtomicReference<Runnable>();
        var targetStops = new AtomicInteger();
        var siblingStops = new AtomicInteger();
        var target = definition("target", targetDisable, targetStops, null);
        var sibling = definition("sibling", new AtomicReference<>(), siblingStops, null);
        var store = new RecordingStateStore();
        var graph = graph("target", "sibling");

        try (var engine = engine(graph, store, target, sibling)) {
            var started = engine.start().block(TIMEOUT);
            var siblingIdentity = started.engine().instances().get("sibling").identity();
            targetDisable.get().run();

            var disabled = await(engine, view -> !view.engine().desiredGraph().plugins()
                .get("target").enabled() && !view.engine().instances().containsKey("target"));

            assertFalse(disabled.engine().desiredGraph().plugins().get("target").enabled());
            assertFalse(store.load().orElseThrow().desiredGraph().plugins().get("target").enabled());
            assertFalse(disabled.engine().instances().containsKey("target"));
            assertEquals(siblingIdentity, disabled.engine().instances().get("sibling").identity());
            assertEquals(1, targetStops.get());
            assertEquals(0, siblingStops.get());
            assertTrue(disabled.engineDiagnostics().targetSatisfied());
            assertEquals(2, store.saves.get());
        }
    }

    @Test
    void repeatedRootRequestsSaveOnlyOneReplacementTarget() {
        var disable = new AtomicReference<Runnable>();
        var store = new RecordingStateStore();
        var definition = definition("target", disable, new AtomicInteger(), null);

        try (var engine = engine(graph("target"), store, definition)) {
            engine.start().block(TIMEOUT);
            disable.get().run();
            disable.get().run();

            await(engine, view -> !view.engine().desiredGraph().plugins().get("target").enabled()
                && !view.engine().instances().containsKey("target"));

            assertEquals(2, store.saves.get());
        }
    }

    @Test
    void requestQueuedWhileStartingRunsAfterTheRootBecomesActive() {
        var definition = PluginDefinition.builder("target", Void.class, () -> (context, config) -> {
            context.plugins().requestDisable();
            return Mono.empty();
        }).build();
        var store = new RecordingStateStore();

        try (var engine = engine(graph("target"), store, definition)) {
            engine.start().block(TIMEOUT);

            var disabled = await(engine, view -> !view.engine().desiredGraph().plugins()
                .get("target").enabled() && !view.engine().instances().containsKey("target"));

            assertTrue(disabled.engineDiagnostics().targetSatisfied());
            assertEquals(2, store.saves.get());
        }
    }

    @Test
    void pendingAllowedConsumerRequestWaitsForDelayedDependencyRecovery() throws Exception {
        var service = ServiceKey.of("provider-service", String.class);
        var providerContext = new AtomicReference<com.sstlfsj.fibra.Context>();
        var consumerStarts = new AtomicInteger();
        var requestIssued = Sinks.<Void>one();
        var completeStart = Sinks.<Void>one();
        var provider = PluginDefinition.builder("provider", Void.class, () -> (context, config) -> {
            providerContext.set(context);
            return Mono.empty();
        }).provide(service).build();
        var consumer = PluginDefinition.builder("consumer", Void.class, () -> (context, config) -> {
            consumerStarts.incrementAndGet();
            context.plugins().requestDisable();
            requestIssued.tryEmitEmpty();
            return completeStart.asMono();
        }).require(service).build();
        var graph = new DesiredInputGraph(List.of(
            DesiredInputEntry.builder("provider", "provider").build(),
            DesiredInputEntry.builder("consumer", "consumer")
                .publicationRequirement(PublicationRequirement.PENDING_ALLOWED).build()));
        var store = new RecordingStateStore();

        try (var engine = engine(graph, store, provider, consumer)) {
            var started = engine.start().block(TIMEOUT);
            assertEquals(PluginInstanceState.PENDING,
                started.engine().instances().get("consumer").state());

            CompletableFuture<EngineCommandResult> barrier;
            try {
                providerContext.get().services().provide(service, "available");
                requestIssued.asMono().block(TIMEOUT);
                var current = engine.published().current();
                barrier = engine.submit(new ReplaceConfigContext(null,
                    current.engineDiagnostics().contextRevision(),
                    com.sstlfsj.fibra.config.ConfigContextSnapshot.empty())).toFuture();
                await(engine, view -> view.engineDiagnostics().phase() == ChangePhase.RECONCILING);
            } finally {
                completeStart.tryEmitEmpty();
            }
            barrier.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            var disabled = await(engine, view -> !view.engine().desiredGraph().plugins()
                .get("consumer").enabled() && !view.engine().instances().containsKey("consumer"));

            assertFalse(store.load().orElseThrow().desiredGraph().plugins().get("consumer").enabled());
            assertEquals(1, consumerStarts.get());
            assertTrue(disabled.engineDiagnostics().targetSatisfied());
        }
    }

    @Test
    void dynamicChildRequestDoesNotReplaceTheManagedTarget() {
        var childDisable = new AtomicReference<Runnable>();
        var child = PluginDefinition.builder("child", Void.class, () -> (context, config) -> {
            childDisable.set(context.plugins()::requestDisable);
            return Mono.empty();
        }).build();
        var parent = PluginDefinition.builder("parent", Void.class, () -> (context, config) -> {
            context.plugins().mount("child", child.prepare(null));
            return Mono.empty();
        }).build();
        var store = new RecordingStateStore();

        try (var engine = engine(graph("parent"), store, parent)) {
            var started = engine.start().block(TIMEOUT);
            childDisable.get().run();

            var current = engine.published().current();
            engine.submit(new ReplaceConfigContext(null,
                current.engineDiagnostics().contextRevision(),
                com.sstlfsj.fibra.config.ConfigContextSnapshot.empty())).block(TIMEOUT);

            assertTrue(engine.published().current().engine().desiredGraph().plugins()
                .get("parent").enabled());
            assertEquals(started.engine().instances().get("parent").identity(), engine.published()
                .current().engine().instances().get("parent").identity());
            assertEquals(1, store.saves.get());
        }
    }

    @Test
    void disposedAndFailedRootsDoNotReplaceTheDesiredTarget() {
        var disposedDisable = new AtomicReference<Runnable>();
        var disposedInstance = new AtomicReference<PluginInstance<?>>();
        var disposed = PluginDefinition.builder("disposed", Void.class, () -> (context, config) -> {
            disposedDisable.set(context.plugins()::requestDisable);
            disposedInstance.set(context.plugins().current().orElseThrow());
            return Mono.empty();
        }).build();
        var failedDisable = new AtomicReference<Runnable>();
        var health = Sinks.<Void>one();
        var failed = PluginDefinition.builder("failed", Void.class, () -> (context, config) -> {
            failedDisable.set(context.plugins()::requestDisable);
            context.effects().supervise(health.asMono(), "health");
            return Mono.empty();
        }).build();
        var store = new RecordingStateStore();

        try (var engine = engine(graph("disposed", "failed"), store, disposed, failed)) {
            engine.start().block(TIMEOUT);
            disposedInstance.get().dispose().block(TIMEOUT);
            disposedDisable.get().run();
            health.tryEmitError(new IllegalStateException("failed"));
            await(engine, view -> view.engine().instances().get("failed").state()
                == PluginInstanceState.FAILED);
            failedDisable.get().run();

            assertTrue(engine.published().current().engine().desiredGraph().plugins()
                .get("disposed").enabled());
            assertTrue(engine.published().current().engine().desiredGraph().plugins()
                .get("failed").enabled());
            assertEquals(1, store.saves.get());
        }
    }

    @Test
    void saveFailureKeepsActiveInstanceOldDesiredAndOpenGateAndPublishesFailure() {
        var disable = new AtomicReference<Runnable>();
        var stops = new AtomicInteger();
        var store = new RecordingStateStore();
        var definition = definition("target", disable, stops, null);

        try (var engine = engine(graph("target"), store, definition)) {
            var started = engine.start().block(TIMEOUT);
            store.failSaves = true;
            disable.get().run();

            var failed = await(engine, view -> view.engineDiagnostics().failure() != null);

            assertTrue(failed.engine().desiredGraph().plugins().get("target").enabled());
            assertEquals(started.engine().instances().get("target").identity(), failed.engine()
                .instances().get("target").identity());
            assertEquals(PluginInstanceState.ACTIVE, failed.engine().instances().get("target").state());
            assertTrue(failed.engineDiagnostics().mutationGateOpen());
            assertNotNull(failed.engineDiagnostics().failure());
            assertEquals(1, store.saves.get());
            assertEquals(0, stops.get());
        }
    }

    private static PluginDefinition<Void> definition(String name, AtomicReference<Runnable> disable,
                                                      AtomicInteger stops, Sinks.One<Void> health) {
        return PluginDefinition.builder(name, Void.class, () -> (context, config) -> {
            disable.set(context.plugins()::requestDisable);
            context.effects().add((Disposable) () -> Mono.fromRunnable(stops::incrementAndGet));
            if (health != null) context.effects().supervise(health.asMono(), "health");
            return Mono.empty();
        }).build();
    }

    @SafeVarargs
    private static FibraEngine engine(DesiredInputGraph graph, RecordingStateStore store,
                                      PluginDefinition<Void>... definitions) {
        var entries = java.util.Arrays.stream(definitions)
            .map(definition -> new PluginCatalogEntry<>(definition, value -> null))
            .toArray(PluginCatalogEntry[]::new);
        return FibraEngine.builder(new InMemoryDesiredStateRepository(graph)).stateStore(store)
            .catalog(PluginCatalog.of(entries)).build();
    }

    private static DesiredInputGraph graph(String... ids) {
        return new DesiredInputGraph(java.util.Arrays.stream(ids)
            .map(id -> DesiredInputEntry.builder(id, id).build()).toList());
    }

    private static PublishedView await(FibraEngine engine, Predicate<PublishedView> condition) {
        return engine.published().views().filter(condition).next().block(TIMEOUT);
    }

    private static final class RecordingStateStore implements EngineStateStore {
        final AtomicInteger saves = new AtomicInteger();
        private DeploymentManifest manifest;
        boolean failSaves;

        @Override public Optional<DeploymentManifest> load() { return Optional.ofNullable(manifest); }
        @Override public void save(DeploymentManifest value) {
            if (failSaves) throw new IllegalStateException("save failed");
            manifest = value;
            saves.incrementAndGet();
        }
    }
}
