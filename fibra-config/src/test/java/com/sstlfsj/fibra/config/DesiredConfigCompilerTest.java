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
        Files.writeString(source, "- id: x\n  plugin: {id: plugin, facet: main, definition: not-loaded}\n  config: {value: text}\n");
        var result = new DesiredConfigCompiler(ConfigLimits.defaults()).compile(source);

        assertEquals(com.sstlfsj.fibra.value.LiteralValue.of(Map.of("value", "text")),
            ((DesiredInputEntry) result.graph().require("x")).config());
    }

    @Test
    void graphIdentityDoesNotDependOnSourcePaths(@TempDir Path work) throws Exception {
        var first = work.resolve("first.yaml");
        var second = work.resolve("second.yaml");
        var content = "- id: p\n  plugin: {id: plugin, facet: main, definition: p}\n  config: {number: 2.50}\n";
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
              plugin: {id: provider-plugin, facet: main, definition: provider}
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
                  plugin: {id: consumer-plugin, facet: main, definition: consumer}
            - id: bundle
              include: included.yaml
              patches:
                - id: provider
                  plugin: {id: provider-plugin, facet: main, definition: provider}
                  config:
                    value: patched
                - insert:
                    - id: second
                      plugin: {id: provider-plugin, facet: main, definition: provider}
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
              entries: [{id: worker, plugin: {id: sample-plugin, facet: main, definition: sample}}]
            - id: two
              group: true
              entries: [{id: worker, plugin: {id: sample-plugin, facet: main, definition: sample}}]
            """);
        var duplicateFailure = assertThrows(ConfigException.class,
            () -> new DesiredConfigCompiler(ConfigLimits.defaults()).compile(duplicate));
        assertEquals("DUPLICATE_ID", duplicateFailure.diagnostic().code());

        var invalidRealm = work.resolve("realm.yaml");
        Files.writeString(invalidRealm, "- id: worker\n  plugin: {id: sample-plugin, facet: main, definition: sample}\n  realm: {scope: 42}\n");
        var realmFailure = assertThrows(ConfigException.class,
            () -> new DesiredConfigCompiler(ConfigLimits.defaults()).compile(invalidRealm));
        assertEquals("REALM_POLICY_INVALID", realmFailure.diagnostic().code());
    }

    @Test
    void appliesPatchesBeforeValidatingRawExpressionsAndKeepsIncludeCollectionIndependentOfWhen(
        @TempDir Path work) throws Exception {
        var included = work.resolve("included.yaml");
        Files.writeString(included, """
            - id: worker
              plugin: {id: sample-plugin, facet: main, definition: sample}
              when: {$eq: [1]}
              config: {value: {$ref: /value}}
            """);
        var root = work.resolve("root.yaml");
        Files.writeString(root, """
            - id: bundle
              include: included.yaml
              when: true
              context: {value: include}
              patches:
                - id: worker
                  plugin: {id: sample-plugin, facet: main, definition: sample}
                  when: {$ref: /active}
                  context: {active: true, value: patched}
            - id: dormant
              include: included.yaml
              when: false
              patches:
                - id: worker
                  plugin: {id: sample-plugin, facet: main, definition: sample}
                  when: true
            """);

        var compilation = new DesiredConfigCompiler(ConfigLimits.defaults()).compile(root);
        var include = (DesiredInputInclude) compilation.graph().require("bundle");
        var dormant = (DesiredInputInclude) compilation.graph().require("dormant");
        var worker = (DesiredInputEntry) compilation.graph().require("bundle:worker");

        assertTrue(include.content() instanceof DesiredIncludeContent.Collected);
        assertTrue(dormant.content() instanceof DesiredIncludeContent.Collected);
        assertEquals(LiteralValue.of(false), dormant.when());
        assertEquals(LiteralValue.of(Map.of("$ref", "/active")), worker.when());
        assertEquals(LiteralValue.of(Map.of("value", Map.of("$ref", "/value"))), worker.config());
        var evaluation = DesiredEvaluation.evaluate(compilation.graph(), ConfigContextSnapshot.empty());
        assertEquals(LiteralValue.of(Map.of("value", "patched")),
            evaluation.require("bundle:worker").resolvedConfig().orElseThrow());
        assertFalse(evaluation.require("dormant:worker").effective().enabled());
    }

    @Test
    void rejectsReservedLocalMetadataAndMalformedRawExpressionAfterPatch(@TempDir Path work)
        throws Exception {
        var reserved = work.resolve("reserved.yaml");
        Files.writeString(reserved, "- id: x\n  plugin: {id: sample-plugin, facet: main, definition: sample}\n  context: {entry: forged}\n");
        var reservedFailure = assertThrows(ConfigException.class,
            () -> new DesiredConfigCompiler(ConfigLimits.defaults()).compile(reserved));
        assertEquals("CONTEXT_ENTRY_RESERVED", reservedFailure.diagnostic().code());

        var invalid = work.resolve("invalid.yaml");
        Files.writeString(invalid, "- id: x\n  plugin: {id: sample-plugin, facet: main, definition: sample}\n  when: {$if: [true, false]}\n");
        var invalidFailure = assertThrows(ConfigException.class,
            () -> new DesiredConfigCompiler(ConfigLimits.defaults()).compile(invalid));
        assertEquals("EXPRESSION_SHAPE_INVALID", invalidFailure.diagnostic().code());

        var nonBoolean = work.resolve("non-boolean.yaml");
        Files.writeString(nonBoolean, "- id: x\n  plugin: {id: sample-plugin, facet: main, definition: sample}\n  when: 1\n");
        var typeFailure = assertThrows(ConfigException.class,
            () -> new DesiredConfigCompiler(ConfigLimits.defaults()).compile(nonBoolean));
        assertEquals("CONDITION_NOT_BOOLEAN", typeFailure.diagnostic().code());
    }

    @Test
    void requiresACompletePluginFacetAndDefinitionReference(@TempDir Path work) throws Exception {
        var legacy = work.resolve("legacy.yaml");
        Files.writeString(legacy, "- {id: x, plugin: sample}\n");
        var legacyFailure = assertThrows(ConfigException.class,
            () -> new DesiredConfigCompiler(ConfigLimits.defaults()).compile(legacy));

        assertEquals("FIELD_NOT_OBJECT", legacyFailure.diagnostic().code());

        var incomplete = work.resolve("incomplete.yaml");
        Files.writeString(incomplete, "- {id: x, plugin: {id: sample, facet: main}}\n");
        var incompleteFailure = assertThrows(ConfigException.class,
            () -> new DesiredConfigCompiler(ConfigLimits.defaults()).compile(incomplete));

        assertEquals("FIELD_NOT_TEXT", incompleteFailure.diagnostic().code());
    }

}
