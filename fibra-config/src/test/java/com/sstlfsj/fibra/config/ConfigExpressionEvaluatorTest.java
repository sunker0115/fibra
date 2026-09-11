package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigExpressionEvaluatorTest {
    private static final LiteralValue.ObjectValue CONTEXT = (LiteralValue.ObjectValue) LiteralValue.of(
        Map.of("flags", Map.of("a/b", true, "tilde~key", "ok"), "value", 7));

    @Test
    void evaluatesReferencesPointerEscapesAndRecursiveTemplates() {
        var template = LiteralValue.of(Map.of(
            "slash", Map.of("$ref", "/flags/a~1b"),
            "tilde", Map.of("$ref", "/flags/tilde~0key"),
            "items", List.of(Map.of("$ref", "/value"))));

        assertEquals(LiteralValue.of(Map.of(
                "slash", true, "tilde", "ok", "items", List.of(7))),
            ConfigExpressionEvaluator.evaluate(template, CONTEXT));
    }

    @Test
    void supportsLiteralShortCircuitAndLazyIf() {
        assertFalse(ConfigExpressionEvaluator.evaluateCondition(LiteralValue.of(Map.of(
            "$all", List.of(false, Map.of("$ref", "/missing")))), CONTEXT));
        assertTrue(ConfigExpressionEvaluator.evaluateCondition(LiteralValue.of(Map.of(
            "$any", List.of(true, Map.of("$ref", "/missing")))), CONTEXT));
        assertEquals("chosen", ConfigExpressionEvaluator.evaluate(LiteralValue.of(Map.of(
            "$if", List.of(true, "chosen", Map.of("$ref", "/missing")))), CONTEXT).toJava());
        assertEquals(Map.of("$ref", "/missing"), ConfigExpressionEvaluator.evaluate(
            LiteralValue.of(Map.of("$literal", Map.of("$ref", "/missing"))), CONTEXT).toJava());
    }

    @Test
    void validatesOperatorShapeMissingReferencesConditionTypesAndDepth() {
        assertFailure("EXPRESSION_SHAPE_INVALID", Map.of("$eq", List.of(1)));
        assertFailure("CONTEXT_REFERENCE_MISSING", Map.of("$ref", "/missing"));
        assertFailure("CONDITION_NOT_BOOLEAN", Map.of("$ref", "/value"));
        assertFailure("CONTEXT_POINTER_INVALID", Map.of("$ref", "/bad~2escape"));

        LiteralValue nested = LiteralValue.of(true);
        for (var index = 0; index < 101; index++) nested = LiteralValue.of(Map.of("$not", nested));
        var tooDeep = nested;
        var failure = assertThrows(ConfigException.class,
            () -> ConfigExpressionEvaluator.evaluateCondition(tooDeep, CONTEXT));
        assertEquals("EXPRESSION_DEPTH_EXCEEDED", failure.diagnostic().code());
    }

    @Test
    void definedAndEqualityHaveStrictSemantics() {
        assertTrue(ConfigExpressionEvaluator.evaluateCondition(LiteralValue.of(Map.of(
            "$defined", "/value")), CONTEXT));
        assertFalse(ConfigExpressionEvaluator.evaluateCondition(LiteralValue.of(Map.of(
            "$defined", "/absent")), CONTEXT));
        assertTrue(ConfigExpressionEvaluator.evaluateCondition(LiteralValue.of(Map.of(
            "$eq", List.of(Map.of("$ref", "/value"), 7))), CONTEXT));
        assertTrue(ConfigExpressionEvaluator.evaluateCondition(LiteralValue.of(Map.of(
            "$not", false)), CONTEXT));
        assertEquals(LiteralValue.of(Map.of("$ref", 7, "ordinary", true)),
            ConfigExpressionEvaluator.evaluate(LiteralValue.of(
                Map.of("$ref", Map.of("$ref", "/value"), "ordinary", true)), CONTEXT));
    }

    @Test
    void rejectsNonCanonicalArrayIndexesFromJsonPointer() {
        var context = (LiteralValue.ObjectValue) LiteralValue.of(Map.of("items", List.of("zero", "one")));

        for (var token : List.of("01", "+1", "-0", "١")) {
            var expression = LiteralValue.of(Map.of("$ref", "/items/" + token));
            var failure = assertThrows(ConfigException.class,
                () -> ConfigExpressionEvaluator.evaluate(expression, context));
            assertEquals("CONTEXT_REFERENCE_MISSING", failure.diagnostic().code());
        }
        assertEquals("one", ConfigExpressionEvaluator.evaluate(
            LiteralValue.of(Map.of("$ref", "/items/1")), context).toJava());
    }

    private static void assertFailure(String code, Object expression) {
        var failure = assertThrows(ConfigException.class, () ->
            ConfigExpressionEvaluator.evaluateCondition(LiteralValue.of(expression), CONTEXT));
        assertEquals(code, failure.diagnostic().code());
    }
}
