package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.config.ConfigLimits;
import com.sstlfsj.fibra.config.DesiredConfigCompiler;
import com.sstlfsj.fibra.config.FileDesiredStateRepository;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DesiredRealmIsolationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ServiceKey<String> MESSAGE = ServiceKey.of("message", String.class);

    @Test
    void groupLocalRealmsAreSharedWithinEachGroupAndDistinctAcrossGroups(@TempDir Path work)
        throws Exception {
        var source = work.resolve("groups.yaml");
        Files.writeString(source, """
            - id: first
              group: true
              realm:
                message: true
              entries:
                - id: first-provider
                  plugin: provider
                  config: first-value
                - id: first-consumer
                  plugin: consumer
                  config: first
            - id: second
              group: true
              realm:
                message: true
              entries:
                - id: second-provider
                  plugin: provider
                  config: second-value
                - id: second-consumer
                  plugin: consumer
                  config: second
            """);
        var observed = new ConcurrentHashMap<String, String>();
        try (var engine = FibraEngine.builder(new FileDesiredStateRepository(source,
            ConfigLimits.defaults())).catalog(catalog(observed)).build()) {
            var view = assertDoesNotThrow(() -> engine.start().block(TIMEOUT));

            assertEquals(Map.of("first", "first-value", "second", "second-value"), observed);
            assertEquals(List.of("first-provider", "first-consumer", "second-provider", "second-consumer"),
                view.engine().desiredGraph().plugins().keySet().stream().toList());
            assertEquals(2L, view.diagnostics().services().stream()
                .filter(service -> service.service().name().equals(MESSAGE.name()))
                .map(service -> service.service().realm()).distinct().count());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "null"})
    void namedRealmsShareAcrossGroupsAndNestedOverridesCanReturnToDefault(String reset, @TempDir Path work)
        throws Exception {
        var source = work.resolve("named.yaml");
        Files.writeString(source, """
            - id: default-provider
              plugin: provider
              config: default-value
            - id: providers
              group: true
              realm:
                message: shared
              entries:
                - id: named-provider
                  plugin: provider
                  config: shared-value
            - id: consumers
              group: true
              realm:
                message: shared
              entries:
                - id: named-consumer
                  plugin: consumer
                  config: named
                - id: nested
                  group: true
                  realm:
                    message: %s
                  entries:
                    - id: default-consumer
                      plugin: consumer
                      config: default
            """.formatted(reset));
        var observed = new ConcurrentHashMap<String, String>();
        try (var engine = FibraEngine.builder(new FileDesiredStateRepository(source,
            ConfigLimits.defaults())).catalog(catalog(observed)).build()) {
            var view = engine.start().block(TIMEOUT);

            assertEquals(Map.of("named", "shared-value", "default", "default-value"), observed);
            assertEquals("nested", view.engine().desiredGraph().effective("default-consumer").parentId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"configured", "null"})
    void policiesReachDynamicChildrenAndDiagnosticsDoNotTreatThemAsDeclarations(
        String intercept, @TempDir Path work) throws Exception {
        var source = work.resolve("dynamic.yaml");
        Files.writeString(source, """
            - id: group
              group: true
              realm:
                message: true
              intercept:
                message: group-policy
              entries:
                - id: provider
                  plugin: provider
                  config: isolated-value
                - id: parent
                  plugin: parent
                  intercept:
                    message: %s
            """.formatted(intercept));
        var observed = new ConcurrentHashMap<String, String>();
        var child = PluginDefinition.builder("child", Void.class, () -> (context, ignored) -> {
            observed.put("message", context.services().require(MESSAGE));
            observed.put("intercept", (String) context.intercept(MESSAGE));
            return Mono.empty();
        }).require(MESSAGE, "definition-default").build();
        var parent = PluginDefinition.builder("parent", Void.class, () -> (context, ignored) ->
            context.plugins().mount("dynamic-child", child.prepare(null)).settled().then()).build();
        var provider = PluginDefinition.builder("provider", String.class, () -> (context, config) -> {
            context.services().provide(MESSAGE, config);
            return Mono.empty();
        }).provide(MESSAGE).build();
        var catalog = PluginCatalog.of(new PluginCatalogEntry<>(parent, ignored -> null),
            new PluginCatalogEntry<>(provider, value -> (String) value));
        try (var engine = FibraEngine.builder(new FileDesiredStateRepository(source,
            ConfigLimits.defaults())).catalog(catalog).build()) {
            var view = assertDoesNotThrow(() -> engine.start().block(TIMEOUT));

            assertEquals(Map.of("message", "isolated-value", "intercept",
                intercept.equals("null") ? "definition-default" : "configured"), observed);
            assertEquals(2, view.engine().instances().size());
            assertEquals(3, view.diagnostics().plugins().size());
            assertEquals(List.of("provider", "parent"),
                view.engine().desiredGraph().plugins().keySet().stream().toList());
        }
    }

    @Test
    void restoresIncludeNamespacesAndRealmOwnersWithoutSourceFiles(@TempDir Path work) throws Exception {
        var included = work.resolve("included.yaml");
        Files.writeString(included, """
            - id: local
              group: true
              realm:
                message: true
              entries:
                - id: provider
                  plugin: provider
                  config: value
                - id: consumer
                  plugin: consumer
                  config: same-local-id
            """);
        var source = work.resolve("root.yaml");
        Files.writeString(source, """
            - id: first
              include: included.yaml
            - id: second
              include: included.yaml
            - id: not-collected
              enabled: false
              include: does-not-exist.yaml
            """);
        var graph = new DesiredConfigCompiler(ConfigLimits.defaults()).compile(source).graph();
        var manifest = new DeploymentManifest(Map.of(), graph);
        var stateRoot = work.resolve("engine");
        try (var store = new FileEngineStateStore(stateRoot)) {
            store.save(manifest);
        }
        Files.delete(source);
        Files.delete(included);
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .stateStore(new FileEngineStateStore(stateRoot))
            .catalog(catalog(new ConcurrentHashMap<>())).build()) {
            var view = assertDoesNotThrow(() -> engine.start().block(TIMEOUT));

            assertEquals(graph, view.engine().desiredGraph());

            assertEquals(List.of("first:provider", "first:consumer", "second:provider", "second:consumer"),
                view.engine().desiredGraph().plugins().keySet().stream().toList());
            assertEquals(2L, view.diagnostics().services().stream()
                .filter(service -> service.service().name().equals(MESSAGE.name()))
                .map(service -> service.service().realm()).distinct().count());
        }
    }

    private static PluginCatalog catalog(Map<String, String> observed) {
        var provider = PluginDefinition.builder("provider", String.class,
            () -> (context, config) -> {
                context.services().provide(MESSAGE, config);
                return Mono.empty();
            }).provide(MESSAGE).build();
        var consumer = PluginDefinition.builder("consumer", String.class,
            () -> (context, config) -> {
                observed.put(config, context.services().require(MESSAGE));
                return Mono.empty();
            }).require(MESSAGE).build();
        return PluginCatalog.of(new PluginCatalogEntry<>(provider, value -> (String) value),
            new PluginCatalogEntry<>(consumer, value -> (String) value));
    }
}
