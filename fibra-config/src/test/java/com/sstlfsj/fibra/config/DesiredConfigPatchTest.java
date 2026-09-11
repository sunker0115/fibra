package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesiredConfigPatchTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{enabled: false}|PATCH_ID_MISSING",
        "{id: absent, enabled: false}|PATCH_TARGET_MISSING",
        "{id: absent, enabled: \"false\"}|PATCH_TARGET_MISSING",
        "{id: absent, plugin: 42}|PATCH_TARGET_MISSING",
        "{id: worker, plugin: other, enabled: false}|PATCH_NAME_MISMATCH",
        "{id: absent, insert: []}|PATCH_TARGET_MISSING",
        "{id: worker, insert: []}|PATCH_TARGET_NOT_GROUP"
    })
    void skippedPatchesWarnAndAllowLaterPatches(String skipped, String code, @TempDir Path work)
        throws Exception {
        var result = compile(work, "- {id: worker, plugin: sample, config: original}\n",
            "- " + skipped + "\n- {id: worker, plugin: sample, config: changed}\n");

        var worker = plugin(result, "bundle:worker");
        assertEquals("sample", worker.definitionName());
        assertTrue(worker.enabled());
        assertEquals(LiteralValue.of("changed"), worker.config());
        assertEquals(1, result.diagnostics().size());
        var diagnostic = result.diagnostics().getFirst();
        assertEquals(ConfigStage.RESOLVE, diagnostic.stage());
        assertEquals(code, diagnostic.code());
        assertEquals(work.resolve("included.yaml").toRealPath(), diagnostic.source());
        assertEquals("bundle", diagnostic.entryId());
    }

    @Test
    void rootAndGroupAppendsIndexInsertedDescendantsWithoutGroupNamespaces(@TempDir Path work)
        throws Exception {
        var result = compile(work, """
            - id: group
              group: true
              realm: {message: tenant}
              entries: [{id: first, plugin: sample}]
            """, """
            - insert:
                - {id: tail, plugin: sample}
            - id: group
              insert:
                - id: nested
                  group: true
                  enabled: false
                  entries: [{id: added, plugin: sample}]
            - {id: added, plugin: sample, config: patched}
            - {id: tail, enabled: false}
            """);

        var include = (DesiredInputInclude) result.graph().require("bundle");
        var roots = ((DesiredIncludeContent.Collected) include.content()).children();
        assertEquals(List.of("group", "tail"), roots.stream().map(DesiredInputNode::id).toList());
        var group = (DesiredInputGroup) result.graph().require("bundle:group");
        assertEquals(List.of("first", "nested"), group.children().stream().map(DesiredInputNode::id).toList());
        assertEquals(LiteralValue.of("patched"), plugin(result, "bundle:added").config());
        assertTrue(plugin(result, "bundle:added").enabled());
        assertFalse(result.graph().effective("bundle:added").enabled());
        assertEquals("bundle:group", result.graph().effective("bundle:added")
            .realms().get("message").ownerEntryId());
        assertFalse(plugin(result, "bundle:tail").enabled());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void ordinaryGroupReplacementDoesNotReindexItsNewChildren(@TempDir Path work) throws Exception {
        var result = compile(work, "- {id: group, group: true, entries: []}\n", """
            - id: group
              entries: [{id: child, plugin: sample, config: original}]
            - {id: child, config: must-be-skipped}
            """);

        assertEquals(LiteralValue.of("original"), plugin(result, "bundle:child").config());
        assertEquals(List.of("PATCH_TARGET_MISSING"),
            result.diagnostics().stream().map(ConfigDiagnostic::code).toList());
    }

    @Test
    void patchesStayWithinTheCurrentIncludeNamespace(@TempDir Path work) throws Exception {
        Files.writeString(work.resolve("nested.yaml"), "- {id: worker, plugin: sample, config: nested}\n");
        var result = compile(work, """
            - {id: worker, plugin: sample, config: outer}
            - {id: nested, include: nested.yaml}
            """, """
            - {id: 'nested:worker', config: must-be-skipped}
            - {id: worker, config: patched}
            """);

        assertEquals(LiteralValue.of("patched"), plugin(result, "bundle:worker").config());
        assertEquals(LiteralValue.of("nested"), plugin(result, "bundle:nested:worker").config());
        assertEquals(List.of("PATCH_TARGET_MISSING"),
            result.diagnostics().stream().map(ConfigDiagnostic::code).toList());
    }

    @Test
    void shallowOverridesReplaceWholeValuesAndKeepLiteralNull(@TempDir Path work) throws Exception {
        var result = compile(work, """
            - id: worker
              plugin: sample
              config: {original: true, retained: false}
              realm: {message: tenant}
              intercept: {message: {trace: true}}
            """, """
            - {id: worker, config: {only: replacement}, realm: {message: null}, intercept: {message: null}}
            - insert: [{id: nullable, plugin: sample, config: previous}]
            - {id: nullable, config: null}
            """);

        assertEquals(LiteralValue.of(Map.of("only", "replacement")), plugin(result, "bundle:worker").config());
        assertEquals(LiteralValue.of(null), plugin(result, "bundle:nullable").config());
        assertTrue(plugin(result, "bundle:worker").realms().containsKey("message"));
        assertEquals(LiteralValue.of(null), plugin(result, "bundle:worker").realms().get("message"));
        assertTrue(plugin(result, "bundle:worker").intercepts().containsKey("message"));
    }

    @Test
    void explicitNullIsNotAnInstructionToRemoveANodeField(@TempDir Path work) {
        var failure = assertThrows(ConfigException.class, () -> compile(work,
            "- {id: group, group: true, entries: []}\n", "- {id: group, group: null}\n"));

        assertEquals("GROUP_NOT_TRUE", failure.diagnostic().code());
    }

    @Test
    void repeatedCompilationDoesNotBakePatchesIntoSharedInput(@TempDir Path work) throws Exception {
        var source = "- {id: worker, plugin: sample, config: original}\n";
        var first = compile(work, source, "- {id: worker, config: first}\n");
        var second = compile(work, source, "- {id: worker, config: second}\n");
        var removed = compile(work, source, "[]\n");

        assertEquals(LiteralValue.of("first"), plugin(first, "bundle:worker").config());
        assertEquals(LiteralValue.of("second"), plugin(second, "bundle:worker").config());
        assertEquals(LiteralValue.of("original"), plugin(removed, "bundle:worker").config());
        assertEquals(source, Files.readString(work.resolve("included.yaml")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{target: worker, set: {enabled: false}}",
        "{after: worker, insert: {id: extra, plugin: sample}}",
        "{id: absent, surprise: true}",
        "{insert: [], surprise: true}"
    })
    void rejectsUnknownFieldsIncludingBothOldFormats(String patch, @TempDir Path work) {
        var failure = assertThrows(ConfigException.class, () -> compile(work,
            "- {id: worker, plugin: sample}\n", "- " + patch + "\n"));

        assertEquals("PATCH_FIELDS_INVALID", failure.diagnostic().code());
    }

    @Test
    void insertMustBeAnEntryList(@TempDir Path work) {
        var failure = assertThrows(ConfigException.class, () -> compile(work,
            "- {id: worker, plugin: sample}\n", "- {insert: {id: extra, plugin: sample}}\n"));

        assertEquals("PATCH_FIELD_INVALID", failure.diagnostic().code());
    }

    @Test
    void insertedEntriesStillUndergoDuplicateAndNodeValidation(@TempDir Path work) {
        var duplicate = assertThrows(ConfigException.class, () -> compile(work,
            "- {id: worker, plugin: sample}\n", "- {insert: [{id: worker, plugin: sample}]}\n"));
        assertEquals("DUPLICATE_ID", duplicate.diagnostic().code());

        var invalid = assertThrows(ConfigException.class, () -> compile(work,
            "- {id: worker, plugin: sample}\n", "- {insert: [{id: broken, plugin: sample, group: true}]}\n"));
        assertEquals("ENTRY_KIND_INVALID", invalid.diagnostic().code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "- {id: group, entries: [42]}"})
    void nonObjectGroupChildrenHaveStructuredDiagnostics(String patches, @TempDir Path work) {
        var source = patches.equals("[]")
            ? "- {id: group, group: true, entries: [42]}\n"
            : "- {id: group, group: true, entries: []}\n";
        var failure = assertThrows(ConfigException.class, () -> compile(work, source, patches));

        assertEquals(ConfigStage.VALIDATE, failure.diagnostic().stage());
        assertEquals("INVALID_LITERAL", failure.diagnostic().code());
        assertEquals("bundle:group", failure.diagnostic().entryId());
    }

    private static DesiredCompilation compile(Path work, String source, String patches) throws Exception {
        Files.writeString(work.resolve("included.yaml"), source);
        var root = work.resolve("root.yaml");
        Files.writeString(root, "- id: bundle\n  include: included.yaml\n  patches:\n" + patches.indent(4));
        return new DesiredConfigCompiler(ConfigLimits.defaults()).compile(root);
    }

    private static DesiredInputEntry plugin(DesiredCompilation result, String id) {
        return (DesiredInputEntry) result.graph().require(id);
    }
}
