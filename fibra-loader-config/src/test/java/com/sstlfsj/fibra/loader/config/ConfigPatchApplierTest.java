package com.sstlfsj.fibra.loader.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class ConfigPatchApplierTest {
    @Test
    void appliesOrderedInsertAndOverrideWithoutMutatingInputs() {
        var originalEntry = new LinkedHashMap<String, Object>();
        originalEntry.put("id", "base");
        originalEntry.put("name", "fixture");
        originalEntry.put("config", Map.of("value", "old"));
        var input = List.<Map<String, Object>>of(originalEntry);
        var patches = List.of(
            FibraConfigPatch.insert(Map.of(
                "id", "added",
                "group", true,
                "config", List.of())),
            FibraConfigPatch.insert("added", Map.of(
                "id", "child",
                "name", "fixture")),
            FibraConfigPatch.override("added:child", "fixture", Map.of(
                "disabled", true,
                "config", Map.of("value", "new")))
        );

        var result = new ConfigPatchApplier().apply(input, patches, ignored -> { });

        assertEquals(1, input.size());
        assertEquals(Map.of("value", "old"), originalEntry.get("config"));
        assertNotSame(input, result);
        @SuppressWarnings("unchecked")
        var children = (List<Map<String, Object>>) result.get(1).get("config");
        assertEquals(true, children.getFirst().get("disabled"));
        assertEquals(Map.of("value", "new"), children.getFirst().get("config"));
    }

    @Test
    void skipsMissingWrongKindAndNameMismatchWithStructuredWarnings() {
        var input = List.<Map<String, Object>>of(
            Map.of("id", "plugin", "name", "fixture"));
        var warnings = new ArrayList<FibraConfigWarning>();
        var patches = List.of(
            FibraConfigPatch.insert("missing", Map.of("id", "a", "name", "fixture")),
            FibraConfigPatch.insert("plugin", Map.of("id", "b", "name", "fixture")),
            FibraConfigPatch.override("plugin", "other", Map.of("disabled", true)),
            FibraConfigPatch.override("missing", null, Map.of("disabled", true))
        );

        var result = new ConfigPatchApplier().apply(input, patches, warnings::add);

        assertEquals(input, result);
        assertEquals(List.of("PATCH_TARGET_MISSING", "PATCH_TARGET_NOT_GROUP",
            "PATCH_NAME_MISMATCH", "PATCH_TARGET_MISSING"),
            warnings.stream().map(FibraConfigWarning::code).toList());
    }

    @Test
    void explicitNullRemovesAnOptionalField() {
        var input = List.<Map<String, Object>>of(new LinkedHashMap<>(Map.of(
            "id", "plugin", "name", "fixture", "disabled", true)));
        var fields = new LinkedHashMap<String, Object>();
        fields.put("disabled", null);

        var result = new ConfigPatchApplier().apply(input,
            List.of(FibraConfigPatch.override("plugin", null, fields)), ignored -> { });

        assertEquals(Map.of("id", "plugin", "name", "fixture"), result.getFirst());
    }
}
