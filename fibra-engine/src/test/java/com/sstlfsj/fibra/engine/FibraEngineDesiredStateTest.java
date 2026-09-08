package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FibraEngineDesiredStateTest {
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

            assertEquals(EngineState.RUNNING, first.state());
            assertEquals(PluginInstanceState.ACTIVE,
                first.instances().get("sample").state());
            assertEquals("one", first.instances().get("sample").config());
            assertEquals(1, starts.get());
            assertEquals(0, stops.get());

            var desiredRevision = first.desiredSource().revision();
            var second = engine.submit(new ReplaceDesiredGraph(
                first.revision(), desiredRevision,
                new DesiredGraph(List.of(entry("two"))))).block().snapshot();

            assertNotEquals(first.revision(), second.revision());
            assertEquals("two", second.instances().get("sample").config());
            assertEquals(2, starts.get());
            assertEquals(1, stops.get());
            assertEquals(second, engine.snapshot());
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

            assertThrows(EngineConflictException.class, () -> engine.submit(
                new ReplaceDesiredGraph("stale", started.desiredSource().revision(),
                    new DesiredGraph(List.of(entry("two"))))).block());

            assertEquals(started, engine.snapshot());
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
            var failed = engine.snapshots()
                .filter(value -> value.state() == EngineState.FAILED)
                .next().block(Duration.ofSeconds(5));

            assertNotEquals(started.revision(), failed.revision());
            assertEquals(PluginInstanceState.FAILED,
                failed.instances().get("sample").state());
            assertEquals(failed, engine.snapshot());
        }
    }

    private static DesiredEntry entry(String config) {
        return DesiredEntry.builder("sample", "sample").config(config)
            .source(Path.of("memory")).build();
    }
}
