package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.ServiceKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesiredConfigCompilerTest {
    private static final ServiceKey<String> MESSAGE = ServiceKey.of("message", String.class);

    @Test
    void compilesGroupsIncludesPatchesAndInheritedPolicyWithoutRuntime(@TempDir Path work)
        throws Exception {
        var included = work.resolve("included.yaml");
        Files.writeString(included, """
            - id: provider
              plugin: provider
              config:
                value: original
            """);
        var root = work.resolve("root.yaml");
        Files.writeString(root, """
            - id: agents
              group: true
              enabled: false
              realm:
                message: tenant-a
              intercept:
                message:
                  trace: true
              entries:
                - id: consumer
                  plugin: consumer
            - id: bundle
              include: included.yaml
              patches:
                - target: provider
                  set:
                    config:
                      value: patched
                - after: provider
                  insert:
                    id: second
                    plugin: provider
                    enabled: false
                    config:
                      value: second
            """);
        var resolver = resolver();

        var result = new DesiredConfigCompiler(ConfigLimits.defaults())
            .compile(root, resolver);

        assertEquals(3, result.graph().entries().size());
        var consumer = result.graph().require("agents:consumer");
        var provider = result.graph().require("bundle:provider");
        var second = result.graph().require("bundle:second");
        assertFalse(consumer.enabled());
        assertEquals(Map.of("message", "tenant-a"), consumer.realms());
        assertEquals(Map.of("message", Map.of("trace", true)), consumer.intercepts());
        assertEquals(new ProviderConfig("patched"), provider.config());
        assertFalse(second.enabled());
        assertEquals(Set.of(root.toRealPath(), included.toRealPath()), result.snapshot().sources());
        assertEquals(64, result.snapshot().revision().length());
        assertEquals(java.util.List.of(), result.diagnostics());
    }

    @Test
    void rejectsUnknownDefinitionsAndIncludeCyclesWithStructuredDiagnostics(@TempDir Path work)
        throws Exception {
        var unknown = work.resolve("unknown.yaml");
        Files.writeString(unknown, "- id: x\n  plugin: missing\n");
        var first = work.resolve("first.yaml");
        var second = work.resolve("second.yaml");
        Files.writeString(first, "- id: second\n  include: second.yaml\n");
        Files.writeString(second, "- id: first\n  include: first.yaml\n");
        var compiler = new DesiredConfigCompiler(ConfigLimits.defaults());

        var unknownFailure = assertThrows(ConfigException.class,
            () -> compiler.compile(unknown, resolver()));
        var cycleFailure = assertThrows(ConfigException.class,
            () -> compiler.compile(first, resolver()));

        assertEquals(ConfigStage.COMPILE, unknownFailure.diagnostic().stage());
        assertEquals("x", unknownFailure.diagnostic().entryId());
        assertEquals(ConfigStage.RESOLVE, cycleFailure.diagnostic().stage());
        assertEquals("second:first", cycleFailure.diagnostic().entryId());
    }

    private static PluginDefinitionResolver resolver() {
        return name -> switch (name) {
            case "consumer" -> Optional.of(PluginContract.builder("consumer")
                .configType(Void.class)
                .require(MESSAGE)
                .binder(value -> null)
                .build());
            case "provider" -> Optional.of(PluginContract.builder("provider")
                .configType(ProviderConfig.class)
                .provide(MESSAGE)
                .binder(value -> {
                    @SuppressWarnings("unchecked")
                    var map = (Map<String, Object>) value;
                    return new ProviderConfig((String) map.get("value"));
                })
                .build());
            default -> Optional.empty();
        };
    }

    private record ProviderConfig(String value) {
    }
}
