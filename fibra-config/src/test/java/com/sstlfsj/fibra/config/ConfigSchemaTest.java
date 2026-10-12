package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigSchemaTest {
    static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

    static ConfigSchemaSource source(Map<String, ?> assertions) {
        var schema = new LinkedHashMap<String, Object>(assertions);
        schema.put("$schema", DIALECT);
        return new ConfigSchemaSource("fibra-config-1", (LiteralValue.ObjectValue) LiteralValue.of(schema));
    }

    static ConfigSchema compile(Map<String, ?> assertions) {
        return ConfigSchema.compile(source(assertions));
    }

    static void accepts(ConfigSchema schema, Object value) {
        assertTrue(schema.validate(LiteralValue.of(value)).isEmpty());
    }

    static void rejects(ConfigSchema schema, Object value) {
        var violation = schema.validate(LiteralValue.of(value));
        assertTrue(violation.isPresent());
        assertEquals("VALUE_MISMATCH", violation.orElseThrow().code());
    }

    @Test
    void validatesEveryTypeWithoutCoercionOrDefaultMutation() {
        for (var entry : Map.<String, Object>of("string", "text", "boolean", true,
            "number", new BigDecimal("1.25"), "integer", new BigDecimal("1.00")).entrySet()) {
            var schema = compile(Map.of("type", entry.getKey()));
            accepts(schema, entry.getValue());
            rejects(schema, List.of());
            rejects(schema, null);
        }
        accepts(compile(Map.of("type", "null")), null);
        rejects(compile(Map.of("type", "integer")), new BigDecimal("1.0000000000000000001"));
        for (var type : List.of("string", "number", "integer", "boolean")) {
            accepts(compile(Map.of("type", List.of(type, "null"))), null);
        }
        var schema = compile(Map.of("type", "object", "properties", Map.of("x", Map.of("type", "string")),
            "additionalProperties", false));
        var input = LiteralValue.of(Map.of());
        assertTrue(schema.validate(input).isEmpty());
        assertEquals("{}", input.canonicalJson());
        rejects(schema, Map.of("x", 1));
    }

    @Test
    void usesExactDecimalsForAllNumericAssertionsAndScalarEquality() {
        var low = new BigDecimal("0.123456789012345678901");
        var high = new BigDecimal("0.123456789012345678902");
        for (var keyword : List.of("minimum", "exclusiveMinimum")) {
            rejects(compile(Map.of("type", "number", keyword, high)), low);
        }
        for (var keyword : List.of("maximum", "exclusiveMaximum")) {
            rejects(compile(Map.of("type", "number", keyword, low)), high);
        }
        accepts(compile(Map.of("type", "number", "minimum", high, "maximum", high)), high);
        rejects(compile(Map.of("type", "number", "exclusiveMinimum", high)), high);
        rejects(compile(Map.of("type", "number", "exclusiveMaximum", high)), high);
        accepts(compile(Map.of("type", "integer", "const", new BigDecimal("9007199254740993"))),
            new BigDecimal("9007199254740993"));
        rejects(compile(Map.of("type", "integer", "maximum", new BigDecimal("9007199254740992"))),
            new BigDecimal("9007199254740993"));
        rejects(compile(Map.of("type", "number", "enum", List.of(low))), high);
        accepts(compile(Map.of("type", "number", "const", 1)), new BigDecimal("1.00"));
        var withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add("disabled");
        accepts(compile(Map.of("type", List.of("string", "null"), "enum", withNull)), null);
    }

    @Test
    void enforcesObjectArrayAndUnicodeLengths() {
        var object = compile(Map.of("type", "object", "properties", Map.of("x", Map.of("type", "string")),
            "required", List.of("x"), "additionalProperties", false, "minProperties", 1, "maxProperties", 1));
        accepts(object, Map.of("x", "a"));
        rejects(object, Map.of());
        rejects(object, Map.of("x", "a", "extra", true));
        var array = compile(Map.of("type", "array", "items", Map.of("type", "integer"), "minItems", 1, "maxItems", 2));
        accepts(array, List.of(1, 2));
        rejects(array, List.of());
        rejects(array, List.of(1, 2, 3));
        rejects(array, List.of("1"));
        accepts(compile(Map.of("type", "array", "items", false)), List.of());
        rejects(compile(Map.of("type", "array", "items", false)), List.of(1));
        accepts(compile(Map.of("type", "array", "items", true)), List.of(Map.of()));
        var string = compile(Map.of("type", "string", "minLength", 1, "maxLength", 1));
        accepts(string, "\ud83d\ude00");
        rejects(string, "");
        rejects(string, "ab");
    }

    @Test
    void supportsTypedMapsAndFiniteVariantsWithStandardSemantics() {
        var local = Map.of("type", "object", "properties", Map.of(
            "type", Map.of("type", "string", "const", "local"),
            "command", Map.of("type", "array", "items", Map.of("type", "string"))),
            "required", List.of("type", "command"), "additionalProperties", false);
        var remote = Map.of("type", "object", "properties", Map.of(
            "type", Map.of("type", "string", "const", "remote"),
            "headers", Map.of("type", "object", "additionalProperties", Map.of("type", "string"))),
            "required", List.of("type"), "additionalProperties", false);
        var variants = compile(Map.of("oneOf", List.of(local, remote)));
        accepts(variants, Map.of("type", "local", "command", List.of("run")));
        accepts(variants, Map.of("type", "remote", "headers", Map.of("X-Token", "secret")));
        rejects(variants, Map.of("type", "local"));
        rejects(variants, Map.of("type", "remote", "headers", Map.of("X-Token", 1)));
        rejects(variants, Map.of("type", "remote", "command", List.of()));
        rejects(compile(Map.of("oneOf", List.of(Map.of("type", "number"), Map.of("type", "integer")))), 1);
        accepts(compile(Map.of("anyOf", List.of(Map.of("type", "number"), Map.of("type", "integer")))), 1);
        rejects(compile(Map.of("allOf", List.of(local, remote))), Map.of("type", "remote"));
        accepts(compile(Map.of("oneOf", List.of(Map.of("type", "boolean", "const", false),
            Map.of("type", "object", "additionalProperties", false)))), false);
        accepts(compile(Map.of("oneOf", List.of(Map.of("type", "number"),
            Map.of("type", "string", "const", "disabled")))), "disabled");
    }

    @Test
    void acceptsRootReferenceMetadataAndRejectsEveryUnsupportedForm() {
        var ref = compile(Map.of("$defs", Map.of("Name", Map.of("type", "string", "minLength", 1)),
            "$ref", "#/$defs/Name", "title", "Name", "description", "Reusable name"));
        accepts(ref, "name");
        rejects(ref, "");
        for (var keyword : List.of("pattern", "format", "not", "if", "multipleOf", "uniqueItems",
            "contains", "unevaluatedProperties", "dependentRequired", "default", "examples",
            "readOnly", "writeOnly", "$id", "$dynamicRef", "$anchor", "x-extra", "typo")) {
            assertInvalid(Map.of("type", "string", keyword, "canary"));
        }
        for (var invalid : List.<Map<String, ?>>of(Map.of(), Map.of("type", List.of("number", "string")),
            Map.of("type", "object"), Map.of("type", "array"), Map.of("type", "array", "items", List.of()),
            Map.of("type", "string", "enum", List.of(1, new BigDecimal("1.0"))),
            Map.of("type", "object", "additionalProperties", false, "required", 7),
            Map.of("type", "object", "additionalProperties", false, "required", List.of("absent")),
            Map.of("type", "string", "const", Map.of()), Map.of("type", "string", "minLength", -1),
            Map.of("type", "string", "minLength", 0.5),
            Map.of("oneOf", List.of(Map.of("type", "null"))),
            Map.of("type", "null", "oneOf", List.of(Map.of("type", "null"), Map.of("type", "string"))),
            Map.of("$ref", "https://canary.invalid/schema"), Map.of("$ref", "#/$defs/unknown"),
            Map.of("$defs", Map.of("a", Map.of("$ref", "#/$defs/a")), "type", "null"))) {
            assertInvalid(invalid);
        }
        assertThrows(ConfigSchemaException.class, () -> ConfigSchema.compile(new ConfigSchemaSource(
            "fibra-config-1", (LiteralValue.ObjectValue) LiteralValue.of(Map.of("type", "null")))));
    }

    private static void assertInvalid(Map<String, ?> schema) {
        assertEquals(ConfigSchemaException.Code.INVALID_SCHEMA,
            assertThrows(ConfigSchemaException.class, () -> compile(schema)).code(), schema.toString());
    }

    @Test
    void projectsSafeStaticPathsWithoutSecretKeysValuesOrLibraryCauses() {
        var leaf = Map.of("type", "object", "properties", Map.of("count", Map.of("type", "integer")),
            "additionalProperties", false);
        var schema = compile(Map.of("type", "object", "properties", Map.of("credentials",
            Map.of("type", "object", "additionalProperties", Map.of("type", "array", "items", leaf))),
            "additionalProperties", false));
        var violation = schema.validate(LiteralValue.of(Map.of("credentials", Map.of(
            "SECRET_KEY_CANARY", List.of(Map.of("count", "SECRET_VALUE_CANARY")))))).orElseThrow();
        assertEquals("/credentials", violation.instancePointer());
        assertFalse(violation.toString().contains("SECRET"));
        assertEquals("", schema.validate(LiteralValue.of(Map.of("SECRET_KEY_CANARY", true)))
            .orElseThrow().instancePointer());
        var simple = compile(Map.of("type", "object", "properties", Map.of("known", Map.of("type", "integer")),
            "additionalProperties", false));
        assertEquals("/known", simple.validate(LiteralValue.of(Map.of("known", "SECRET"))).orElseThrow().instancePointer());
        var failure = assertThrows(ConfigSchemaException.class, () -> compile(Map.of("SECRET_KEY_CANARY", "SECRET_VALUE_CANARY")));
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.toString().contains("SECRET"));
    }

    @Test
    void batchReportsStableIndicesAndRejectsProgrammingErrorsSynchronously() {
        var valid = source(Map.of("type", "null"));
        var invalid = source(Map.of("type", "null", "default", "secret"));
        assertEquals(1, ConfigSchema.checkSources(List.of(valid, invalid)).orElseThrow().inputIndex());
        assertEquals(1, ConfigSchema.validateAll(List.of(new ConfigValidationInput(valid, LiteralValue.of(null)),
            new ConfigValidationInput(valid, LiteralValue.of(1)))).orElseThrow().inputIndex());
        assertTrue(ConfigSchema.checkSources(List.of(valid, valid)).isEmpty());
        assertTrue(ConfigSchema.validateAll(List.of()).isEmpty());
        assertThrows(NullPointerException.class, () -> ConfigSchema.validateAll(null));
        assertThrows(NullPointerException.class, () -> new ConfigValidationInput(valid, null));
    }

    @Test
    void neverUsesPluginContextLoaderOrExternalResources() {
        var thread = Thread.currentThread();
        var original = thread.getContextClassLoader();
        var lookups = new java.util.concurrent.atomic.AtomicInteger();
        thread.setContextClassLoader(new ClassLoader(null) {
            @Override
            public java.net.URL getResource(String name) {
                lookups.incrementAndGet();
                throw new AssertionError("plugin resource lookup");
            }

            @Override
            public java.io.InputStream getResourceAsStream(String name) {
                lookups.incrementAndGet();
                throw new AssertionError("plugin resource lookup");
            }
        });
        try {
            accepts(compile(Map.of("type", "null")), null);
            for (var ref : List.of("file:/secret", "classpath:secret", "https://secret.invalid/schema", "#")) {
                assertInvalid(Map.of("$ref", ref));
            }
            assertEquals(0, lookups.get());
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    @Test
    void supportsBooleanPropertySchemasAndNullableContainers() {
        var rule = compile(Map.of("type", List.of("null", "object"), "properties", Map.of("free", true, "forbidden", false),
            "additionalProperties", true));
        accepts(rule, null);
        accepts(rule, Map.of("free", List.of(1), "other", false));
        rejects(rule, Map.of("forbidden", LiteralValue.NullValue.INSTANCE));
        accepts(compile(Map.of("type", List.of("array", "null"), "items", true)), null);
        var nullableNumber = new LinkedHashMap<String, Object>();
        nullableNumber.put("type", "null");
        nullableNumber.put("const", null);
        accepts(compile(nullableNumber), null);
    }

}
