package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.Scope;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.config.PublicationRequirement;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraEngineIncrementalTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void oneDesiredGraphChangeDoesNotStartAConsumerWithMixedProviderAndConsumerConfig() {
        var service = ServiceKey.of("value", Integer.class);
        var applied = new CopyOnWriteArrayList<String>();
        var provider = PluginDefinition.builder("provider", String.class,
            () -> (context, config) -> {
                context.services().provide(service, Integer.parseInt(config));
                return Mono.empty();
            }).provide(service).build();
        var consumer = PluginDefinition.builder("consumer", String.class,
            () -> (context, config) -> {
                applied.add(context.services().require(service) + ":" + config);
                return Mono.empty();
            }).require(service).build();
        var catalog = PluginCatalog.of(
            new PluginCatalogEntry<>(provider, value -> (String) value),
            new PluginCatalogEntry<>(consumer, value -> (String) value));
        var initial = graph(entry("provider", "provider", "1"),
            entry("consumer", "consumer", "old"));
        var changed = graph(entry("provider", "provider", "2"),
            entry("consumer", "consumer", "new"));

        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(initial))
            .catalog(catalog).build()) {
            var started = engine.start().block(TIMEOUT);

            engine.submit(new ReplaceDesiredGraph(started.viewRevision(),
                started.engine().desiredSource().revision(), changed)).block(TIMEOUT);

            assertEquals(List.of("1:old", "2:new"), applied);
        }
    }

    @Test
    void replacementSubmittedDuringStartupWaitsForTheAcceptedInitialTarget() throws Exception {
        var entered = Sinks.<Scope>one();
        var release = Sinks.<Void>one();
        var events = new CopyOnWriteArrayList<String>();
        var otherStarts = new AtomicInteger();
        var otherStops = new AtomicInteger();
        var old = PluginDefinition.builder("old", Void.class, () -> (context, config) -> {
            events.add("old-start");
            context.effects().add(() -> {
                events.add("old-stop");
                return Mono.empty();
            });
            entered.tryEmitValue(context.scope());
            return release.asMono().doOnSuccess(ignored -> events.add("old-ready"));
        }).build();
        var replacement = PluginDefinition.builder("replacement", Void.class,
            () -> (context, config) -> {
                events.add("replacement-start");
                return Mono.empty();
            }).build();
        var other = PluginDefinition.builder("other", Void.class, () -> (context, config) -> {
            otherStarts.incrementAndGet();
            context.effects().add(() -> {
                otherStops.incrementAndGet();
                return Mono.empty();
            });
            return Mono.empty();
        }).build();
        var initial = graph(entry("changing", "old", null), entry("other", "other", null));
        var target = graph(entry("changing", "replacement", null), entry("other", "other", null));
        var repository = new InMemoryDesiredStateRepository(initial);
        var initialRevision = repository.load().snapshot().revision();
        var catalog = PluginCatalog.of(new PluginCatalogEntry<>(old, ignored -> null),
            new PluginCatalogEntry<>(replacement, ignored -> null),
            new PluginCatalogEntry<>(other, ignored -> null));
        try (var engine = FibraEngine.builder(repository)
            .catalog(catalog).build()) {
            var starting = engine.start().toFuture();
            try {
                var oldScope = entered.asMono().block(TIMEOUT);
                var replacing = engine.submit(new ReplaceDesiredGraph(null,
                    initialRevision, target)).toFuture();

                assertFalse(starting.isDone());
                assertFalse(replacing.isDone());
                assertFalse(oldScope.isClosed());
                assertEquals(List.of("old-start"), events);
                release.tryEmitEmpty();

                var started = starting.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                var replaced = replacing.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).view();
                assertEquals(initial, started.engine().desiredGraph());
                assertTrue(started.engineDiagnostics().targetSatisfied());
                assertEquals(target, replaced.engine().desiredGraph());
                assertTrue(replaced.engineDiagnostics().targetSatisfied());
                assertEquals(List.of("old-start", "old-ready", "old-stop", "replacement-start"), events);
                assertTrue(oldScope.isClosed());
                assertNotEquals(started.engine().instances().get("changing").identity(),
                    replaced.engine().instances().get("changing").identity());
                assertEquals(started.engine().instances().get("other").identity(),
                    replaced.engine().instances().get("other").identity());
                assertEquals(1, otherStarts.get());
                assertEquals(0, otherStops.get());
            } finally {
                release.tryEmitEmpty();
            }
        }
    }

    @Test
    void reportsEveryNewInstanceStartupFailureAfterTheDomainSettles() {
        var firstFailure = new IllegalStateException("first startup failed");
        var secondFailure = new IllegalStateException("second startup failed");
        var first = PluginDefinition.builder("first", Void.class,
            () -> (context, config) -> Mono.error(firstFailure)).build();
        var second = PluginDefinition.builder("second", Void.class,
            () -> (context, config) -> Mono.error(secondFailure)).build();
        var healthy = PluginDefinition.builder("healthy", Void.class,
            () -> (context, config) -> Mono.empty()).build();
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(first, ignored -> null),
                new PluginCatalogEntry<>(second, ignored -> null),
                new PluginCatalogEntry<>(healthy, ignored -> null))).build()) {
            var initial = engine.start().block(TIMEOUT);
            var failure = assertThrows(EngineChangeException.class, () -> engine.submit(
                new ReplaceDesiredGraph(null, initial.engine().desiredSource().revision(),
                    graph(entry("first", "first", null), entry("second", "second", null),
                        entry("healthy", "healthy", null)))).block(TIMEOUT));

            var causes = Arrays.asList(failure.getCause().getSuppressed());
            assertEquals(2, causes.size());
            assertTrue(causes.stream().anyMatch(cause -> cause == firstFailure));
            assertTrue(causes.stream().anyMatch(cause -> cause == secondFailure));
            assertEquals(TargetSaveState.SAVED, failure.targetSaveState());
            assertTrue(failure.view().engineDiagnostics().mutationGateOpen());
            assertEquals(PluginInstanceState.ACTIVE, failure.view().engine().instances().get("healthy").state());
        }
    }

    @Test
    void dependencyTriggeredRestartReportsTheConsumersOriginalFailure() {
        var service = ServiceKey.of("message", String.class);
        var startupFailure = new IllegalStateException("consumer rejected provider value");
        var provider = PluginDefinition.builder("provider", String.class, () -> (context, config) -> {
            context.services().provide(service, config);
            return Mono.empty();
        }).provide(service).build();
        var consumer = PluginDefinition.builder("consumer", Void.class, () -> (context, config) ->
            "broken".equals(context.services().require(service)) ? Mono.error(startupFailure) : Mono.empty())
            .require(service).build();
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph(
            entry("provider", "provider", "ready"), entry("consumer", "consumer", null))))
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(provider, value -> (String) value),
                new PluginCatalogEntry<>(consumer, ignored -> null))).build()) {
            var initial = engine.start().block(TIMEOUT);
            var failure = assertThrows(EngineChangeException.class, () -> engine.submit(
                new ReplaceDesiredGraph(null, initial.engine().desiredSource().revision(), graph(
                    entry("provider", "provider", "broken"), entry("consumer", "consumer", null))))
                .block(TIMEOUT));

            assertTrue(Arrays.stream(failure.getCause().getSuppressed()).anyMatch(cause -> cause == startupFailure));
            assertEquals(PluginInstanceState.FAILED, failure.view().engine().instances().get("consumer").state());
            assertEquals(initial.engine().instances().get("consumer").identity(),
                failure.view().engine().instances().get("consumer").identity());
        }
    }

    @Test
    void aCleanlyFailedConfigUpdateCanBeCorrectedWithoutRestartingTheEngine() {
        var definition = PluginDefinition.builder("sample", String.class, () -> (context, config) ->
            "invalid-runtime".equals(config) ? Mono.error(new IllegalStateException("runtime rejected config"))
                : Mono.empty()).build();
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph(entry("sample", "sample", "old"))))
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> (String) value))).build()) {
            var initial = engine.start().block(TIMEOUT);
            var identity = initial.engine().instances().get("sample").identity();
            var failure = assertThrows(EngineChangeException.class, () -> engine.submit(new ReplaceDesiredGraph(null,
                initial.engine().desiredSource().revision(), graph(entry("sample", "sample", "invalid-runtime"))))
                .block(TIMEOUT));

            assertEquals(TargetSaveState.SAVED, failure.targetSaveState());
            assertEquals(PluginInstanceState.FAILED, failure.view().engine().instances().get("sample").state());
            assertEquals(LiteralValue.of("invalid-runtime"), failure.view().engine().instances().get("sample").config());
            assertTrue(failure.view().engineDiagnostics().mutationGateOpen(), "a clean startup failure is not a cleanup failure");
            var corrected = engine.submit(new ReplaceDesiredGraph(null,
                failure.view().engine().desiredSource().revision(), graph(entry("sample", "sample", "corrected"))))
                .block(TIMEOUT).view();
            assertTrue(corrected.engineDiagnostics().targetSatisfied());
            assertEquals(identity, corrected.engine().instances().get("sample").identity());
        }
    }

    @Test
    void unchangedDesiredGraphDoesNotRebindOrRestartAnInstance() {
        var binds = new AtomicInteger();
        var starts = new AtomicInteger();
        var definition = PluginDefinition.builder("sample", String.class,
            () -> (context, config) -> {
                starts.incrementAndGet();
                return Mono.empty();
            }).build();
        var catalog = PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> {
            binds.incrementAndGet();
            return (String) value;
        }));
        var graph = graph(entry("sample", "sample", "first"));
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .catalog(catalog).build()) {
            var started = engine.start().block(TIMEOUT);
            var identity = started.engine().instances().get("sample").identity();
            var bindsBefore = binds.get();
            var startsBefore = starts.get();

            var refreshed = engine.submit(new ReplaceDesiredGraph(started.viewRevision(),
                started.engine().desiredSource().revision(), graph)).block(TIMEOUT).view();

            assertEquals(bindsBefore, binds.get());
            assertEquals(startsBefore, starts.get());
            assertEquals(identity, refreshed.engine().instances().get("sample").identity());
        }
    }

    @Test
    void changingAConfigKeepsAHandleAndLeavesUnrelatedBRunning() {
        var startsA = new AtomicInteger();
        var startsB = new AtomicInteger();
        var stopsB = new AtomicInteger();
        var a = PluginDefinition.builder("a", String.class,
            () -> (context, config) -> {
                startsA.incrementAndGet();
                return Mono.empty();
            }).build();
        var b = PluginDefinition.builder("b", Void.class,
            () -> (context, config) -> {
                startsB.incrementAndGet();
                context.effects().add(() -> {
                    stopsB.incrementAndGet();
                    return Mono.empty();
                });
                return Mono.empty();
            }).build();
        var catalog = PluginCatalog.of(new PluginCatalogEntry<>(a, value -> (String) value),
            new PluginCatalogEntry<>(b, value -> null));
        var initial = graph(entry("a", "a", "first"), entry("b", "b", null));
        var changed = graph(entry("a", "a", "second"), entry("b", "b", null));
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(initial))
            .catalog(catalog).build()) {
            var started = engine.start().block(TIMEOUT);
            var aIdentity = started.engine().instances().get("a").identity();
            var bIdentity = started.engine().instances().get("b").identity();

            var refreshed = engine.submit(new ReplaceDesiredGraph(started.viewRevision(),
                started.engine().desiredSource().revision(), changed)).block(TIMEOUT).view();

            assertEquals(aIdentity, refreshed.engine().instances().get("a").identity());
            assertEquals(bIdentity, refreshed.engine().instances().get("b").identity());
            assertEquals(2, startsA.get());
            assertEquals(1, startsB.get());
            assertEquals(0, stopsB.get());
        }
    }

    @Test
    void disablingAProviderLeavesPendingAllowedConsumerAndUnrelatedPluginRunning() {
        var service = ServiceKey.of("message", String.class);
        var startsOther = new AtomicInteger();
        var stopsOther = new AtomicInteger();
        var provider = PluginDefinition.builder("provider", Void.class,
            () -> (context, config) -> {
                context.services().provide(service, "available");
                return Mono.empty();
            }).provide(service).build();
        var consumer = PluginDefinition.builder("consumer", Void.class,
            () -> (context, config) -> Mono.empty()).require(service).build();
        var other = PluginDefinition.builder("other", Void.class,
            () -> (context, config) -> {
                startsOther.incrementAndGet();
                context.effects().add(() -> {
                    stopsOther.incrementAndGet();
                    return Mono.empty();
                });
                return Mono.empty();
            }).build();
        var catalog = PluginCatalog.of(new PluginCatalogEntry<>(provider, value -> null),
            new PluginCatalogEntry<>(consumer, value -> null),
            new PluginCatalogEntry<>(other, value -> null));
        var initial = graph(entry("provider", "provider", null),
            entry("consumer", "consumer", null).toBuilder()
                .publicationRequirement(PublicationRequirement.PENDING_ALLOWED).build(),
            entry("other", "other", null));
        var withoutProvider = graph(entry("provider", "provider", null).toBuilder().enabled(false).build(),
            entry("consumer", "consumer", null).toBuilder()
                .publicationRequirement(PublicationRequirement.PENDING_ALLOWED).build(),
            entry("other", "other", null));
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(initial))
            .catalog(catalog).build()) {
            var started = engine.start().block(TIMEOUT);
            var otherIdentity = started.engine().instances().get("other").identity();

            var refreshed = engine.submit(new ReplaceDesiredGraph(started.viewRevision(),
                started.engine().desiredSource().revision(), withoutProvider)).block(TIMEOUT).view();

            assertFalse(refreshed.engine().instances().containsKey("provider"));
            assertEquals(PluginInstanceState.PENDING,
                refreshed.engine().instances().get("consumer").state());
            assertTrue(refreshed.engineDiagnostics().targetSatisfied());
            assertEquals(otherIdentity, refreshed.engine().instances().get("other").identity());
            assertEquals(1, startsOther.get());
            assertEquals(0, stopsOther.get());
        }
    }

    @Test
    void savedTargetSurvivesStartupFailureAndOverridesAnEmptySourceOnReopen(@TempDir Path work) {
        var starts = new AtomicInteger();
        var definition = PluginDefinition.builder("saved", Void.class,
            () -> (context, config) -> starts.getAndIncrement() == 0
                ? Mono.error(new IllegalStateException("first start fails")) : Mono.empty()).build();
        var catalog = PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> null));
        var target = graph(entry("saved", "saved", null));
        var stateRoot = work.resolve("state");

        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(target))
            .catalog(catalog).stateStore(new FileEngineStateStore(stateRoot)).build()) {
            var failure = assertThrows(EngineChangeException.class,
                () -> engine.start().block(TIMEOUT));
            assertEquals(TargetSaveState.SAVED, failure.targetSaveState());
        }
        try (var store = new FileEngineStateStore(stateRoot)) {
            assertEquals(target, store.load().orElseThrow().desiredGraph());
        }
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .catalog(catalog).stateStore(new FileEngineStateStore(stateRoot)).build()) {
            var reopened = engine.start().block(TIMEOUT);

            assertEquals(List.of("saved"), reopened.engine().desiredGraph().plugins().keySet().stream().toList());
            assertEquals(PluginInstanceState.ACTIVE, reopened.engine().instances().get("saved").state());
            assertEquals(2, starts.get());
        }
    }

    private static DesiredInputGraph graph(DesiredInputEntry... entries) {
        return new DesiredInputGraph(List.of(entries));
    }

    private static DesiredInputEntry entry(String id, String definition, String config) {
        var builder = DesiredInputEntry.builder(id, definition);
        if (config != null) builder.config(LiteralValue.of(config));
        return builder.build();
    }
}
