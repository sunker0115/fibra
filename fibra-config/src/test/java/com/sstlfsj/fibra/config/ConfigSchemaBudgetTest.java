package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sstlfsj.fibra.config.ConfigSchemaTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigSchemaBudgetTest {
    @Test
    void sourceIsDeeplyImmutableAndNeutralUntilQualification() {
        var mutable = new LinkedHashMap<String, Object>();
        mutable.put("unknown", new ArrayList<>(List.of("original")));
        var source = source(mutable);
        mutable.clear();
        assertTrue(source.schema().values().containsKey("unknown"));
        assertThrows(UnsupportedOperationException.class, () -> source.schema().values().clear());
        assertThrows(ConfigSchemaException.class, () -> ConfigSchema.compile(source));
        var unknownProfile = new ConfigSchemaSource("future", source.schema());
        assertEquals(ConfigSchemaException.Code.UNSUPPORTED_PROFILE,
            assertThrows(ConfigSchemaException.class, () -> ConfigSchema.compile(unknownProfile)).code());
    }

    @Test
    void boundsDecimalPrecisionScaleAndExponentBeforeEncoding() {
        for (var number : List.of("1e257", "1e-257", "1".repeat(257), "1e2147483647")) {
            assertEquals(ConfigSchemaException.Code.BUDGET_EXCEEDED,
                assertThrows(ConfigSchemaException.class, () -> source(Map.of("number", new BigDecimal(number)))).code());
            assertEquals("BUDGET_EXCEEDED", compile(Map.of("type", "number"))
                .validate(LiteralValue.of(new BigDecimal(number))).orElseThrow().code());
        }
        for (var number : List.of("1e256", "1e-256", "9".repeat(256), "0e2147483647")) {
            accepts(compile(Map.of("type", "number")), new BigDecimal(number));
        }
    }

    @Test
    void boundsEffectiveStringsNodesAndDepthBeforeLibraryNodes() {
        var open = compile(Map.of("type", "array", "items", true));
        assertEquals("BUDGET_EXCEEDED", open.validate(LiteralValue.of(Collections.nCopies(10_000, 0))).orElseThrow().code());
        accepts(open, Collections.nCopies(9_999, 0));
        var text = compile(Map.of("type", "string"));
        accepts(text, "x".repeat(65_536));
        assertEquals("BUDGET_EXCEEDED", text.validate(LiteralValue.of("x".repeat(65_537))).orElseThrow().code());
        LiteralValue nested = LiteralValue.of(0);
        for (var i = 0; i < 65; i++) nested = new LiteralValue.ListValue(List.of(nested));
        assertEquals("BUDGET_EXCEEDED", open.validate(nested).orElseThrow().code());
    }

    @Test
    void boundsRefsCombinationsAndDiamondExpansionIncludingDormantDefs() {
        var defs = new LinkedHashMap<String, Object>();
        defs.put("d0", Map.of("type", "null"));
        for (var i = 1; i <= 17; i++) defs.put("d" + i, Map.of("$ref", "#/$defs/d" + (i - 1)));
        assertBudget(Map.of("$defs", defs, "$ref", "#/$defs/d17"));
        Map<String, ?> branch = Map.of("type", "null");
        for (var i = 0; i < 5; i++) branch = Map.of("anyOf", List.of(branch, Map.of("type", "string")));
        assertBudget(branch);
        defs.clear();
        defs.put("d0", Map.of("type", "object", "additionalProperties", false));
        for (var i = 1; i <= 13; i++) defs.put("d" + i, Map.of("type", "object", "properties", Map.of(
            "a", Map.of("$ref", "#/$defs/d" + (i - 1)), "b", Map.of("$ref", "#/$defs/d" + (i - 1))), "additionalProperties", false));
        assertBudget(Map.of("type", "null", "$defs", defs));
    }

    @Test
    void admitsWholeBatchBeforeCompilingAndCountsEveryInstance() {
        var unique = new ArrayList<ConfigSchemaSource>();
        for (var i = 0; i < 129; i++) unique.add(source(Map.of("type", "null", "title", "schema" + i)));
        assertEquals(-1, ConfigSchema.checkSources(unique).orElseThrow().inputIndex());
        var rule = source(Map.of("type", "array", "items", true, "description", "x".repeat(4096)));
        var input = new ConfigValidationInput(rule, LiteralValue.of(Collections.nCopies(9_999, 0)));
        assertTrue(ConfigSchema.validateAll(List.of(input)).isEmpty());
        var repeated = Collections.nCopies(100, input);
        var failure = ConfigSchema.validateAll(repeated).orElseThrow();
        assertEquals(-1, failure.inputIndex());
        assertEquals("BUDGET_EXCEEDED", failure.violation().code());
        var batch = new ArrayList<>(repeated);
        batch.set(0, new ConfigValidationInput(source(Map.of("typo", "secret")), LiteralValue.of(null)));
        assertEquals(-1, ConfigSchema.validateAll(batch).orElseThrow().inputIndex());
    }

    private static void assertBudget(Map<String, ?> assertions) {
        assertEquals(ConfigSchemaException.Code.BUDGET_EXCEEDED,
            assertThrows(ConfigSchemaException.class, () -> compile(assertions)).code());
    }

    @Test
    void countAndTextQualificationLimitsHaveIndependentExactBoundaries() {
        for (var type : List.of("string", "array", "object")) {
            var fields = new LinkedHashMap<String, Object>();
            fields.put("type", type);
            if (type.equals("array")) fields.put("items", true);
            if (type.equals("object")) fields.put("additionalProperties", true);
            int maximum = type.equals("string") ? 65536 : 10000;
            String suffix = type.equals("string") ? "Length" : type.equals("array") ? "Items" : "Properties";
            for (var prefix : List.of("min", "max")) {
                fields.put(prefix + suffix, maximum);
                assertDoesNotThrow(() -> compile(fields));
                fields.put(prefix + suffix, maximum + 1);
                assertBudget(fields);
                fields.put(prefix + suffix, -1);
                assertEquals(ConfigSchemaException.Code.INVALID_SCHEMA,
                    assertThrows(ConfigSchemaException.class, () -> compile(fields)).code());
                fields.put(prefix + suffix, new BigDecimal("0.5"));
                assertEquals(ConfigSchemaException.Code.INVALID_SCHEMA,
                    assertThrows(ConfigSchemaException.class, () -> compile(fields)).code());
                fields.remove(prefix + suffix);
            }
        }
        accepts(compile(Map.of("type", "string", "maxLength", 65536)), "界".repeat(21845));
        assertEquals("BUDGET_EXCEEDED", compile(Map.of("type", "string", "maxLength", 65536))
            .validate(LiteralValue.of("界".repeat(21846))).orElseThrow().code());
        assertDoesNotThrow(() -> compile(Map.of("type", "null", "title", "x".repeat(256), "description", "x".repeat(4096))));
        assertBudget(Map.of("type", "null", "title", "x".repeat(257)));
        assertBudget(Map.of("type", "null", "description", "x".repeat(4097)));
    }

    @Test
    void rawSchemaAndNeutralSourceNodeDepthAndByteBoundsAreSeparate() {
        var unknown = (LiteralValue.ObjectValue) LiteralValue.of(Map.of("x", "a".repeat(1_048_568)));
        assertEquals(1_048_576, unknown.canonicalJson().length());
        assertDoesNotThrow(() -> new ConfigSchemaSource("future", unknown));
        assertThrows(ConfigSchemaException.class, () -> new ConfigSchemaSource("future",
            (LiteralValue.ObjectValue) LiteralValue.of(Map.of("x", "a".repeat(1_048_569)))));
        LiteralValue nested = LiteralValue.of(0);
        for (int i = 0; i < 62; i++) nested = new LiteralValue.ListValue(List.of(nested));
        var at64 = new LiteralValue.ObjectValue(Map.of("x", nested));
        assertDoesNotThrow(() -> new ConfigSchemaSource("future", at64));
        assertThrows(ConfigSchemaException.class, () -> new ConfigSchemaSource("future", new LiteralValue.ObjectValue(Map.of("x", at64))));
        var many = new LiteralValue.ObjectValue(Map.of("x", LiteralValue.of(Collections.nCopies(32766, 0))));
        assertDoesNotThrow(() -> new ConfigSchemaSource("future", many));
        assertThrows(ConfigSchemaException.class, () -> new ConfigSchemaSource("future",
            new LiteralValue.ObjectValue(Map.of("x", LiteralValue.of(Collections.nCopies(32767, 0))))));
    }

    @Test
    void batchCountsUniqueSchemaBytesAndDoesNotDeduplicateInstanceWork() {
        var sources = new ArrayList<ConfigSchemaSource>();
        var props = new LinkedHashMap<String, Object>();
        for (int i = 0; i < 12; i++) props.put("p" + i, Map.of("type", "string", "description", "x".repeat(4096)));
        for (int i = 0; i < 22; i++) sources.add(source(Map.of("type", "object", "title", "s" + i,
            "properties", props, "additionalProperties", false)));
        assertTrue(ConfigSchema.checkSources(sources.subList(0, 20)).isEmpty());
        assertEquals(-1, ConfigSchema.checkSources(sources).orElseThrow().inputIndex());
        assertTrue(ConfigSchema.checkSources(Collections.nCopies(1000, sources.getFirst())).isEmpty());
    }
}
