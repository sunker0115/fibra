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
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesiredConfigCompilerTest {
    @Test
    void collectsLiteralInputWithoutResolvingOrBindingPluginTypes(@TempDir Path work)
        throws Exception {
        var source = work.resolve("input.yaml");
        Files.writeString(source, "- id: x\n  plugin: not-loaded\n  config: {value: text}\n");
        var result = new DesiredConfigCompiler(ConfigLimits.defaults()).compile(source);

        assertEquals(com.sstlfsj.fibra.value.LiteralValue.of(Map.of("value", "text")),
            ((DesiredInputEntry) result.graph().require("x")).config());
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
                - id: provider
                  plugin: provider
                  config:
                    value: patched
                - insert:
                    - id: second
                      plugin: provider
                      enabled: false
                      config:
                        value: second
            """);
        var result = new DesiredConfigCompiler(ConfigLimits.defaults())
            .compile(root);

        assertEquals(3, result.graph().plugins().size());
        var consumer = (DesiredInputEntry) result.graph().require("consumer");
        var provider = (DesiredInputEntry) result.graph().require("bundle:provider");
        var second = (DesiredInputEntry) result.graph().require("bundle:second");
        assertFalse(result.graph().effective("consumer").enabled());
        assertEquals(Map.of(), consumer.realms());
        assertEquals(Map.of(), consumer.intercepts());
        assertEquals(LiteralValue.of("tenant-a"),
            result.graph().effective("consumer").realms().get("message").value());
        assertEquals("agents", result.graph().effective("consumer").realms()
            .get("message").ownerEntryId());
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

    @Test
    void keepsDisabledMissingIncludeUncollectedWithoutReadingIt(@TempDir Path work) throws Exception {
        var root = work.resolve("root.yaml");
        Files.writeString(root, "- id: missing\n  include: absent.yaml\n  enabled: false\n");

        var result = new DesiredConfigCompiler(ConfigLimits.defaults()).compile(root);

        assertEquals(Set.of(root.toRealPath()), result.snapshot().sources());
        assertTrue(result.graph().require("missing") instanceof DesiredInputInclude);
        assertThrows(ConfigException.class, () -> result.graph().withEnabled("missing", true));
    }

    @Test
    void validatesDisabledIncludeDeclarationBeforeSkippingItsRead(@TempDir Path work) throws Exception {
        var root = work.resolve("root.yaml");
        Files.writeString(root, "- id: missing\n  include: 123\n  enabled: false\n");

        var failure = assertThrows(ConfigException.class,
            () -> new DesiredConfigCompiler(ConfigLimits.defaults()).compile(root));

        assertEquals("FIELD_NOT_TEXT", failure.diagnostic().code());
    }

    @Test
    void rejectsDuplicateLocalIdAcrossGroupsAndInvalidRealmType(@TempDir Path work) throws Exception {
        var duplicate = work.resolve("duplicate.yaml");
        Files.writeString(duplicate, """
            - id: one
              group: true
              entries: [{id: worker, plugin: sample}]
            - id: two
              group: true
              entries: [{id: worker, plugin: sample}]
            """);
        var duplicateFailure = assertThrows(ConfigException.class,
            () -> new DesiredConfigCompiler(ConfigLimits.defaults()).compile(duplicate));
        assertEquals("DUPLICATE_ID", duplicateFailure.diagnostic().code());

        var invalidRealm = work.resolve("realm.yaml");
        Files.writeString(invalidRealm, "- id: worker\n  plugin: sample\n  realm: {scope: 42}\n");
        var realmFailure = assertThrows(ConfigException.class,
            () -> new DesiredConfigCompiler(ConfigLimits.defaults()).compile(invalidRealm));
        assertEquals("REALM_POLICY_INVALID", realmFailure.diagnostic().code());
    }

}
