package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.config.ConfigLimits;
import com.sstlfsj.fibra.config.ConfigException;
import com.sstlfsj.fibra.config.ConfigStage;
import com.sstlfsj.fibra.config.FileDesiredStateRepository;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DesiredInputBindingTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void skippedSourcePatchDoesNotPreventStartupAndRemainsVisibleOnRefresh(@TempDir Path work)
        throws Exception {
        Files.writeString(work.resolve("included.yaml"), "- {id: sample, plugin: sample}\n");
        var root = work.resolve("root.yaml");
        Files.writeString(root, """
            - id: bundle
              include: included.yaml
              patches:
                - {id: absent, enabled: false}
                - {id: sample, config: patched}
            """);
        var starts = new AtomicInteger();
        var definition = PluginDefinition.builder("sample", String.class, () -> (context, config) -> {
            assertEquals("patched", config);
            starts.incrementAndGet();
            return Mono.empty();
        }).build();
        try (var engine = FibraEngine.builder(new FileDesiredStateRepository(root, ConfigLimits.defaults()))
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> (String) value))).build()) {
            var initial = engine.start().block(TIMEOUT);
            var refreshed = engine.submit(new RefreshDesired(null)).block(TIMEOUT);
            assertTrue(initial.engineDiagnostics().targetSatisfied());
            assertTrue(refreshed.view().engineDiagnostics().targetSatisfied());
            assertEquals(1, refreshed.warnings().size());
            assertTrue(refreshed.warnings().getFirst().contains("absent"));
            assertEquals(initial.engine().instances(), refreshed.view().engine().instances());
            assertEquals(1, starts.get());
        }
    }

    @Test
    void invalidFileRefreshKeepsTheLastGoodTreeAndAcceptsTheNextValidEdit(@TempDir Path work)
        throws Exception {
        // DSH a66e470: app-boot/tests/config-reload.spec.ts, invalid include refresh.
        var root = work.resolve("root.yaml");
        var included = work.resolve("included.yaml");
        Files.writeString(root, "- id: bundle\n  include: included.yaml\n");
        Files.writeString(included, "- id: sample\n  plugin: sample\n  config: first\n");
        var started = new CopyOnWriteArrayList<String>();
        var stops = new AtomicInteger();
        var definition = PluginDefinition.builder("sample", String.class, () -> (context, config) -> {
            started.add(config);
            context.effects().add(() -> {
                stops.incrementAndGet();
                return Mono.empty();
            });
            return Mono.empty();
        }).build();
        try (var engine = FibraEngine.builder(new FileDesiredStateRepository(root, ConfigLimits.defaults()))
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> (String) value))).build()) {
            var initial = engine.start().block(TIMEOUT);
            for (var invalid : List.of("invalid: [unclosed\n", "", "{}")) {
                Files.writeString(included, invalid);
                var failed = assertThrows(ConfigException.class,
                    () -> engine.submit(new RefreshDesired(null)).block(TIMEOUT));
                assertEquals(included.toRealPath(), failed.diagnostic().source());
                assertEquals("bundle", failed.diagnostic().entryId());
                assertEquals(invalid.equals("{}") ? ConfigStage.VALIDATE : ConfigStage.PARSE,
                    failed.diagnostic().stage());
                var retained = engine.published().current();
                assertEquals(initial.engine().desiredGraph(), retained.engine().desiredGraph());
                assertEquals(initial.engine().desiredSource(), retained.engine().desiredSource());
                assertEquals(initial.engine().instances(), retained.engine().instances());
                assertTrue(retained.engineDiagnostics().targetSatisfied());
                assertTrue(retained.engineDiagnostics().mutationGateOpen());
                assertEquals(List.of("first"), started);
                assertEquals(0, stops.get());
            }

            Files.writeString(included, "- id: sample\n  plugin: sample\n  config: second\n");
            var refreshed = engine.submit(new RefreshDesired(null)).block(TIMEOUT).view();
            assertTrue(refreshed.engineDiagnostics().targetSatisfied());
            assertNotEquals(initial.engine().desiredSource().revision(), refreshed.engine().desiredSource().revision());
            assertEquals(initial.engine().instances().get("bundle:sample").identity(),
                refreshed.engine().instances().get("bundle:sample").identity());
            assertEquals(LiteralValue.of("second"), refreshed.engine().instances().get("bundle:sample").config());
            assertEquals(List.of("first", "second"), started);
            assertEquals(1, stops.get());
        }
    }

    @Test
    void validatesAndNormalizesConfigurationExactlyOnceBeforeMount() {
        var validations = new AtomicInteger();
        var started = new CopyOnWriteArrayList<String>();
        var definition = PluginDefinition.builder("normalizing", String.class,
            () -> (context, config) -> { started.add(config); return Mono.empty(); })
            .validator(config -> {
                validations.incrementAndGet();
                return config + "-normalized";
            }).build();
        var graph = new DesiredInputGraph(List.of(DesiredInputEntry.builder("p", "normalizing")
            .config(LiteralValue.of("input")).build()));
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> (String) value)))
            .build()) {
            engine.start().block(TIMEOUT);

            assertEquals(1, validations.get());
            assertEquals(List.of("input-normalized"), started);
        }
    }

    @Test
    void disabledDeclarationDoesNotRequireAnInstalledDefinition() {
        var graph = new DesiredInputGraph(List.of(
            DesiredInputEntry.builder("absent", "not-installed").enabled(false)
                .config(LiteralValue.of(Map.of("future", "configuration"))).build()));
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph)).build()) {
            var view = engine.start().block(TIMEOUT);

            assertEquals(graph, view.engine().desiredGraph());
            assertTrue(view.engine().instances().isEmpty());
        }
    }

    @Test
    void bindingFailurePreventsEveryPluginStartAndRetainsIncludedSourceLocation(@TempDir Path work)
        throws Exception {
        var included = work.resolve("included.yaml");
        Files.writeString(included, "- id: bad\n  plugin: number\n  config: invalid\n");
        var root = work.resolve("root.yaml");
        Files.writeString(root, "- id: first\n  plugin: ready\n- id: bundle\n  include: included.yaml\n");
        var starts = new AtomicInteger();
        var ready = PluginDefinition.builder("ready", Void.class, () -> (context, config) -> {
            starts.incrementAndGet();
            return Mono.empty();
        }).build();
        var number = PluginDefinition.builder("number", Integer.class,
            () -> (context, config) -> Mono.empty()).build();
        var catalog = PluginCatalog.of(new PluginCatalogEntry<>(ready, value -> null),
            new PluginCatalogEntry<>(number, value -> Integer.valueOf((String) value)));
        try (var engine = FibraEngine.builder(new FileDesiredStateRepository(root,
            ConfigLimits.defaults())).catalog(catalog).build()) {
            var failed = assertThrows(EngineChangeException.class, () -> engine.start().block(TIMEOUT));
            assertFalse(failed.targetSaved());
            var binding = assertInstanceOf(DesiredBindingException.class, failed.getCause());

            assertEquals("CONFIG_BIND_FAILED", binding.diagnostic().code());
            assertEquals(ConfigStage.COMPILE, binding.diagnostic().stage());
            assertEquals("bundle:bad", binding.diagnostic().entryId());
            assertEquals(included.toRealPath(), binding.diagnostic().source());
            assertEquals(0, starts.get());
        }
    }

    @Test
    void unknownEnabledDefinitionIsRejectedDuringTargetBinding() {
        var graph = new DesiredInputGraph(List.of(DesiredInputEntry.builder("absent", "missing").build()));
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph)).build()) {
            var failed = assertThrows(EngineChangeException.class, () -> engine.start().block(TIMEOUT));
            assertFalse(failed.targetSaved());
            var binding = assertInstanceOf(DesiredBindingException.class, failed.getCause());

            assertEquals("DEFINITION_NOT_FOUND", binding.diagnostic().code());
            assertEquals("absent", binding.diagnostic().entryId());
        }
    }

    @Test
    void reusesUnchangedBindingsAndRebindsOnlyChangedConfigurationWithoutLeakingInput() {
        var bindings = new AtomicInteger();
        var started = new CopyOnWriteArrayList<Settings>();
        var definition = PluginDefinition.builder("sample", Settings.class,
            () -> (context, config) -> {
                started.add(config);
                config.values.add("runtime-only");
                return Mono.empty();
            }).build();
        var literal = LiteralValue.of(Map.of("values", List.of("input")));
        var graph = new DesiredInputGraph(List.of(DesiredInputEntry.builder("sample", "sample")
            .config(literal).build()));
        var changedLiteral = LiteralValue.of(Map.of("values", List.of("changed")));
        var changed = new DesiredInputGraph(List.of(DesiredInputEntry.builder("sample", "sample")
            .config(changedLiteral).build()));
        var catalog = PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> {
            bindings.incrementAndGet();
            var values = (List<?>) ((Map<?, ?>) value).get("values");
            return new Settings(new ArrayList<>(values.stream().map(String.class::cast).toList()));
        }));

        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .catalog(catalog).build()) {
            var first = engine.start().block(TIMEOUT);
            var second = engine.submit(new ReplaceDesiredGraph(first.viewRevision(),
                first.engine().desiredSource().revision(), graph)).block(TIMEOUT).view();
            var third = engine.submit(new ReplaceDesiredGraph(second.viewRevision(),
                second.engine().desiredSource().revision(), changed)).block(TIMEOUT).view();

            assertEquals(2, bindings.get());
            assertNotSame(started.getFirst(), started.getLast());
            assertEquals(2, started.size());
            assertEquals(List.of("input", "runtime-only"), started.getFirst().values);
            assertEquals(List.of("changed", "runtime-only"), started.getLast().values);
            assertEquals(literal, first.engine().instances().get("sample").config());
            assertEquals(literal, second.engine().instances().get("sample").config());
            assertEquals(changedLiteral, third.engine().instances().get("sample").config());
            assertEquals(literal, first.engine().desiredGraph().plugins().get("sample").config());
        }
    }

    private record Settings(List<String> values) { }
}
