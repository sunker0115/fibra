package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.sstlfsj.fibra.config.ConfigSchemaTest.source;
import static org.junit.jupiter.api.Assertions.*;

class ConfigSchemaBatchOrderTest {
    @Test
    void earlierValueFailurePrecedesLaterSchemaFailure() {
        var valid = source(Map.of("type", "string"));
        var invalid = source(Map.of("type", "string", "default", "forbidden"));

        assertFailure(ConfigSchema.validateAll(List.of(input(valid, 42), input(invalid, "ok"))),
            0, "VALUE_MISMATCH");
    }

    @Test
    void earlierValueFailurePrecedesLaterUnsupportedProfile() {
        var valid = source(Map.of("type", "string"));
        var unknown = new ConfigSchemaSource("future", valid.schema());

        assertFailure(ConfigSchema.validateAll(List.of(input(valid, 42), input(unknown, "ok"))),
            0, "VALUE_MISMATCH");
    }

    @Test
    void reportsLaterSchemaFailureWhenEarlierValueIsValid() {
        var valid = source(Map.of("type", "string"));
        var invalid = source(Map.of("type", "string", "default", "forbidden"));

        assertFailure(ConfigSchema.validateAll(List.of(input(valid, "ok"), input(invalid, "ok"))),
            1, "INVALID_SCHEMA");
        assertFailure(ConfigSchema.checkSources(List.of(valid, invalid)), 1, "INVALID_SCHEMA");
    }

    @Test
    void laterBudgetFailuresStillPrecedeEarlierValueAndSchemaFailures() {
        var valid = source(Map.of("type", "string"));
        var invalid = source(Map.of("type", "string", "default", "forbidden"));
        var largeSchema = source(Map.of("type", "string", "description", "x".repeat(4097)));
        var overValueBudget = input(valid, "x".repeat(65537));

        assertFailure(ConfigSchema.validateAll(List.of(input(valid, 42), overValueBudget)), 1, "BUDGET_EXCEEDED");
        assertFailure(ConfigSchema.validateAll(List.of(input(invalid, "ok"), overValueBudget)), 1, "BUDGET_EXCEEDED");
        assertFailure(ConfigSchema.validateAll(List.of(input(valid, 42), input(largeSchema, "ok"))), 1, "BUDGET_EXCEEDED");
        assertFailure(ConfigSchema.checkSources(List.of(invalid, largeSchema)), 1, "BUDGET_EXCEEDED");
    }

    @Test
    void budgetRejectionNeverEntersTheLibraryAndControlDoes() throws Exception {
        // 独立加载生产类；库尚未初始化时设置哨兵，不能仅由返回 code 推断调用顺序。
        var classes = List.of(ConfigSchema.class, LiteralValue.class, com.networknt.schema.Schema.class,
            tools.jackson.databind.JsonNode.class, tools.jackson.core.json.JsonFactory.class);
        var urls = classes.stream().map(type -> type.getProtectionDomain().getCodeSource().getLocation())
            .distinct().toArray(URL[]::new);
        try (var loader = new LibrarySentinel(urls)) {
            var literalType = loader.loadClass(LiteralValue.class.getName());
            var objectType = loader.loadClass(LiteralValue.ObjectValue.class.getName());
            var sourceType = loader.loadClass(ConfigSchemaSource.class.getName());
            var schemaType = loader.loadClass(ConfigSchema.class.getName());
            var inputType = loader.loadClass(ConfigValidationInput.class.getName());
            var of = literalType.getMethod("of", Object.class);
            var sourceConstructor = sourceType.getConstructor(String.class, objectType);
            var inputConstructor = inputType.getConstructor(sourceType, literalType);
            var rule = sourceConstructor.newInstance("fibra-config-1", of.invoke(null,
                source(Map.of("type", "string")).schema().toJava()));
            var firstWrongValue = inputConstructor.newInstance(rule, of.invoke(null, 42));
            var laterLargeValue = inputConstructor.newInstance(rule, of.invoke(null, "x".repeat(65537)));
            var validateAll = schemaType.getMethod("validateAll", List.class);
            var result = (Optional<?>) validateAll.invoke(null, List.of(firstWrongValue, laterLargeValue));
            assertIsolatedFailure(result, 1);
            assertEquals(0, loader.libraryAttempts);

            var manySources = new ArrayList<>();
            for (var index = 0; index < 129; index++) {
                manySources.add(sourceConstructor.newInstance("fibra-config-1", of.invoke(null,
                    source(Map.of("type", "null", "title", "schema" + index)).schema().toJava())));
            }
            result = (Optional<?>) schemaType.getMethod("checkSources", List.class).invoke(null, manySources);
            assertIsolatedFailure(result, -1);
            assertEquals(0, loader.libraryAttempts);

            var arrayRule = sourceConstructor.newInstance("fibra-config-1", of.invoke(null,
                source(Map.of("type", "array", "items", true, "description", "x".repeat(4096))).schema().toJava()));
            var repeated = inputConstructor.newInstance(arrayRule, of.invoke(null, Collections.nCopies(9999, 0)));
            result = (Optional<?>) validateAll.invoke(null, Collections.nCopies(100, repeated));
            assertIsolatedFailure(result, -1);
            assertEquals(0, loader.libraryAttempts);

            var thrown = assertThrows(InvocationTargetException.class,
                () -> schemaType.getMethod("compile", sourceType).invoke(null, rule));
            assertInstanceOf(AssertionError.class, thrown.getCause());
            assertEquals(1, loader.libraryAttempts);
        }
    }

    private static ConfigValidationInput input(ConfigSchemaSource source, Object value) {
        return new ConfigValidationInput(source, LiteralValue.of(value));
    }

    private static void assertFailure(Optional<ConfigBatchFailure> result, int index, String code) {
        assertTrue(result.isPresent());
        assertEquals(index, result.orElseThrow().inputIndex());
        assertEquals(code, result.orElseThrow().violation().code());
    }

    private static void assertIsolatedFailure(Optional<?> result, int index) throws Exception {
        assertTrue(result.isPresent());
        var failure = result.orElseThrow();
        assertEquals(index, failure.getClass().getMethod("inputIndex").invoke(failure));
        var violation = failure.getClass().getMethod("violation").invoke(failure);
        assertEquals("BUDGET_EXCEEDED", violation.getClass().getMethod("code").invoke(violation));
    }

    private static final class LibrarySentinel extends URLClassLoader {
        private int libraryAttempts;

        private LibrarySentinel(URL[] urls) {
            super(urls, ClassLoader.getPlatformClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals("com.networknt.schema.SchemaRegistry")) {
                libraryAttempts++;
                throw new AssertionError("library execution sentinel");
            }
            return super.loadClass(name, resolve);
        }
    }
}
