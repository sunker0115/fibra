package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.config.ConfigLimits;
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
            var failed = assertThrows(ChangeSetException.class, () -> engine.start().block(TIMEOUT));
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
            var failed = assertThrows(ChangeSetException.class, () -> engine.start().block(TIMEOUT));
            var binding = assertInstanceOf(DesiredBindingException.class, failed.getCause());

            assertEquals("DEFINITION_NOT_FOUND", binding.diagnostic().code());
            assertEquals("absent", binding.diagnostic().entryId());
        }
    }

    @Test
    void bindsFreshConfigurationForEachGenerationWithoutExposingItInPublishedViews() {
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

            assertEquals(2, bindings.get());
            assertNotSame(started.getFirst(), started.getLast());
            assertEquals(List.of("input", "runtime-only"), started.getLast().values);
            assertEquals(literal, first.engine().instances().get("sample").config());
            assertEquals(literal, second.engine().instances().get("sample").config());
            assertEquals(literal, first.engine().desiredGraph().require("sample").config());
        }
    }

    private record Settings(List<String> values) { }
}
