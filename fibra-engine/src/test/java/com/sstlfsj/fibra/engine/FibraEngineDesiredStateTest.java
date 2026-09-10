package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.config.DesiredEntry;
import com.sstlfsj.fibra.config.DesiredGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FibraEngineDesiredStateTest {
    @Test
    void waitsForTheWholeServiceDependencyChainBeforePublishing() {
        var number = ServiceKey.of("number", Integer.class);
        var text = ServiceKey.of("text", String.class);
        var observed = new AtomicReference<String>();
        var provider = PluginDefinition.builder("provider", Void.class,
                () -> (context, config) -> {
                    context.services().provide(number, 7);
                    return Mono.empty();
                })
            .provide(number)
            .build();
        var consumer = PluginDefinition.builder("consumer", Void.class,
                () -> (context, config) -> {
                    var numbers = context.services().reference(number);
                    context.services().provide(text, numbers.invoke(
                        (invocation, value) -> "value-" + value));
                    return Mono.empty();
                })
            .require(number)
            .provide(text)
            .build();
        var projection = PluginDefinition.builder("projection", Void.class,
                () -> (context, config) -> {
                    observed.set(context.services().reference(text).invoke(
                        (invocation, value) -> value));
                    return Mono.empty();
                })
            .require(text)
            .build();
        var graph = new DesiredGraph(List.of(
            entry("projection", "projection"),
            entry("consumer", "consumer"),
            entry("provider", "provider")));
        var catalog = PluginCatalog.of(
            new PluginCatalogEntry<>(provider, value -> null),
            new PluginCatalogEntry<>(consumer, value -> null),
            new PluginCatalogEntry<>(projection, value -> null));

        try (var engine = FibraEngine.builder(
            new InMemoryDesiredStateRepository(graph)).catalog(catalog).build()) {
            var started = engine.start().block().engine();

            assertEquals("value-7", observed.get());
            assertEquals(PluginInstanceState.ACTIVE,
                started.instances().get("provider").state());
            assertEquals(PluginInstanceState.ACTIVE,
                started.instances().get("consumer").state());
            assertEquals(PluginInstanceState.ACTIVE,
                started.instances().get("projection").state());
        }
    }

    @Test
    void atomicallyPublishesAWholeDesiredGenerationAndRetiresThePreviousOne() {
        var starts = new AtomicInteger();
        var stops = new AtomicInteger();
        var definition = PluginDefinition.builder("sample", String.class,
            () -> (context, config) -> {
                starts.incrementAndGet();
                context.effects().add((Disposable) () -> Mono.fromRunnable(
                    stops::incrementAndGet));
                return Mono.empty();
            }).build();
        var catalog = PluginCatalog.of(new PluginCatalogEntry<>(definition,
            literal -> (String) literal));
        var repository = new InMemoryDesiredStateRepository(
            new DesiredGraph(List.of(entry("one"))));

        try (var engine = FibraEngine.builder(repository).catalog(catalog).build()) {
            var first = engine.start().block();

            assertEquals(EngineState.RUNNING, first.engine().state());
            assertEquals(PluginInstanceState.ACTIVE,
                first.engine().instances().get("sample").state());
            assertEquals("one", first.engine().instances().get("sample").config());
            assertEquals(1, starts.get());
            assertEquals(0, stops.get());

            var desiredRevision = first.engine().desiredSource().revision();
            var second = engine.submit(new ReplaceDesiredGraph(
                first.viewRevision(), desiredRevision,
                new DesiredGraph(List.of(entry("two"))))).block().view();

            assertNotEquals(first.viewRevision(), second.viewRevision());
            assertEquals("two", second.engine().instances().get("sample").config());
            assertEquals(2, starts.get());
            assertEquals(1, stops.get());
            assertEquals(second, engine.published().current());
        }
        assertEquals(2, stops.get());
    }

    @Test
    void rejectsStaleEngineRevisionWithoutChangingRuntimeOrRepository() {
        var definition = PluginDefinition.builder("sample", String.class,
            () -> (context, config) -> Mono.empty()).build();
        var repository = new InMemoryDesiredStateRepository(
            new DesiredGraph(List.of(entry("one"))));

        try (var engine = FibraEngine.builder(repository)
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition,
                literal -> (String) literal))).build()) {
            var started = engine.start().block();

            assertThrows(PublishedRevisionConflictException.class, () -> engine.submit(
                new ReplaceDesiredGraph("stale", started.engine().desiredSource().revision(),
                    new DesiredGraph(List.of(entry("two"))))).block());

            assertEquals(started, engine.published().current());
            assertEquals("one", repository.load(name -> java.util.Optional.empty())
                .graph().require("sample").config());
        }
    }

    @Test
    void publishesAnObservedSnapshotWhenAnActiveInstanceFailsAsynchronously() {
        var health = Sinks.<Void>one();
        var definition = PluginDefinition.builder("sample", String.class,
            () -> (context, config) -> {
                context.effects().supervise(health.asMono(), "external-process");
                return Mono.empty();
            }).build();
        var repository = new InMemoryDesiredStateRepository(
            new DesiredGraph(List.of(entry("one"))));

        try (var engine = FibraEngine.builder(repository)
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition,
                literal -> (String) literal))).build()) {
            var started = engine.start().block();

            health.tryEmitError(new IllegalStateException("process exited"));
            var failed = engine.published().views()
                .filter(value -> value.engine().instances().get("sample").state()
                    == PluginInstanceState.FAILED)
                .next().block(Duration.ofSeconds(5));

            assertNotEquals(started.viewRevision(), failed.viewRevision());
            assertEquals(started.generationRevision(), failed.generationRevision());
            assertEquals(EngineState.RUNNING, failed.engine().state());
            assertEquals(PluginInstanceState.FAILED,
                failed.engine().instances().get("sample").state());
            assertEquals(failed, engine.published().current());
        }
    }

    private static DesiredEntry entry(String config) {
        return DesiredEntry.builder("sample", "sample").config(config)
            .source(Path.of("memory")).build();
    }

    private static DesiredEntry entry(String instanceId, String definitionName) {
        return DesiredEntry.builder(instanceId, definitionName)
            .source(Path.of("memory")).build();
    }
}
