package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesiredConfigCompilerTest {
    @Test
    void collectsLiteralInputWithoutResolvingOrBindingPluginTypes(@TempDir Path work)
        throws Exception {
        var source = work.resolve("input.yaml");
        Files.writeString(source, "- id: x\n  plugin: not-loaded\n  config: {value: text}\n");
        var result = new DesiredConfigCompiler(ConfigLimits.defaults()).compile(source);

        assertEquals(com.sstlfsj.fibra.value.LiteralValue.of(Map.of("value", "text")),
            result.graph().require("x").config());
    }

    @Test
    void graphIdentityDoesNotDependOnSourcePaths(@TempDir Path work) throws Exception {
        var first = work.resolve("first.yaml");
        var second = work.resolve("second.yaml");
        var content = "- id: p\n  plugin: p\n  config: {number: 2.50}\n";
        Files.writeString(first, content);
        Files.writeString(second, content);
        var compiler = new DesiredConfigCompiler(ConfigLimits.defaults());
        var left = compiler.compile(first);
        var right = compiler.compile(second);

        assertEquals(left.graph(), right.graph());
        assertEquals(left.graph().hashCode(), right.graph().hashCode());
        org.junit.jupiter.api.Assertions.assertNotEquals(left.entrySources(), right.entrySources());
    }

    @Test
    void compilesGroupsIncludesPatchesAndInheritedPolicyWithoutRuntime(@TempDir Path work)
        throws Exception {
        var included = work.resolve("included.yaml");
        Files.writeString(included, """
            - id: provider
              plugin: provider
              publication: pending-allowed
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
        var result = new DesiredConfigCompiler(ConfigLimits.defaults())
            .compile(root);

        assertEquals(3, result.graph().entries().size());
        var consumer = result.graph().require("agents:consumer");
        var provider = result.graph().require("bundle:provider");
        var second = result.graph().require("bundle:second");
        assertFalse(consumer.enabled());
        assertEquals(Map.of("message", LiteralValue.of("tenant-a")), consumer.realms());
        assertEquals(Map.of("message", LiteralValue.of(Map.of("trace", true))), consumer.intercepts());
        assertEquals(LiteralValue.of(Map.of("value", "patched")), provider.config());
        assertEquals(included.toRealPath(), result.entrySources().get("bundle:provider"));
        assertEquals(PublicationRequirement.PENDING_ALLOWED,
            provider.publicationRequirement());
        assertEquals(PublicationRequirement.ACTIVE_REQUIRED,
            consumer.publicationRequirement());
        assertFalse(second.enabled());
        assertEquals(Set.of(root.toRealPath(), included.toRealPath()), result.snapshot().sources());
        assertEquals(64, result.snapshot().revision().length());
        assertEquals(java.util.List.of(), result.diagnostics());
    }

    @Test
    void rejectsIncludeCyclesWithStructuredDiagnostics(@TempDir Path work)
        throws Exception {
        var first = work.resolve("first.yaml");
        var second = work.resolve("second.yaml");
        Files.writeString(first, "- id: second\n  include: second.yaml\n");
        Files.writeString(second, "- id: first\n  include: first.yaml\n");
        var compiler = new DesiredConfigCompiler(ConfigLimits.defaults());

        var cycleFailure = assertThrows(ConfigException.class,
            () -> compiler.compile(first));

        assertEquals(ConfigStage.RESOLVE, cycleFailure.diagnostic().stage());
        assertEquals("second:first", cycleFailure.diagnostic().entryId());
    }

}
