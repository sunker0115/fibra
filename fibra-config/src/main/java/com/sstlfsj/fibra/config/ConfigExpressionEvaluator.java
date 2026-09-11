package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 对受限 LiteralValue 表达式和递归配置模板执行纯求值。 */
final class ConfigExpressionEvaluator {
    private static final int MAX_DEPTH = 100;
    private static final Set<String> OPERATORS = Set.of(
        "$ref", "$defined", "$eq", "$not", "$all", "$any", "$if", "$literal");

    private ConfigExpressionEvaluator() { }

    static LiteralValue evaluate(LiteralValue template,
                                 LiteralValue.ObjectValue context) {
        return evaluate(template, context, 0);
    }

    static boolean evaluateCondition(LiteralValue expression,
                                     LiteralValue.ObjectValue context) {
        return condition(expression, context, 0);
    }

    static void validateTemplate(LiteralValue template) {
        validate(template, false, 0);
    }

    static void validateCondition(LiteralValue expression) {
        validate(expression, true, 0);
    }

    private static LiteralValue evaluate(LiteralValue value, LiteralValue.ObjectValue context,
                                         int depth) {
        depth(depth);
        if (value instanceof LiteralValue.ListValue list) {
            var result = new ArrayList<LiteralValue>(list.values().size());
            for (var item : list.values()) result.add(evaluate(item, context, depth + 1));
            return new LiteralValue.ListValue(result);
        }
        if (!(value instanceof LiteralValue.ObjectValue object)) return value;
        var operator = operator(object);
        if (operator == null) {
            var result = new LinkedHashMap<String, LiteralValue>();
            object.values().forEach((key, item) ->
                result.put(key, evaluate(item, context, depth + 1)));
            return new LiteralValue.ObjectValue(result);
        }
        var operand = object.values().get(operator);
        return switch (operator) {
            case "$ref" -> required(context, pointer(operand), true);
            case "$defined" -> new LiteralValue.BooleanValue(
                lookup(context, pointer(operand)).defined());
            case "$eq" -> {
                var items = exactList(operand, "$eq", 2);
                yield new LiteralValue.BooleanValue(
                    evaluate(items.get(0), context, depth + 1).equals(
                        evaluate(items.get(1), context, depth + 1)));
            }
            case "$not" -> new LiteralValue.BooleanValue(
                !condition(operand, context, depth + 1));
            case "$all" -> new LiteralValue.BooleanValue(
                all(list(operand, "$all"), context, depth + 1));
            case "$any" -> new LiteralValue.BooleanValue(
                any(list(operand, "$any"), context, depth + 1));
            case "$if" -> {
                var items = exactList(operand, "$if", 3);
                yield evaluate(condition(items.get(0), context, depth + 1)
                    ? items.get(1) : items.get(2), context, depth + 1);
            }
            case "$literal" -> operand;
            default -> throw new AssertionError(operator);
        };
    }

    private static boolean condition(LiteralValue value, LiteralValue.ObjectValue context,
                                     int depth) {
        var result = evaluate(value, context, depth);
        if (result instanceof LiteralValue.BooleanValue bool) return bool.value();
        throw error("CONDITION_NOT_BOOLEAN", "condition must evaluate to a boolean");
    }

    private static boolean all(List<LiteralValue> values, LiteralValue.ObjectValue context,
                               int depth) {
        for (var value : values) if (!condition(value, context, depth)) return false;
        return true;
    }

    private static boolean any(List<LiteralValue> values, LiteralValue.ObjectValue context,
                               int depth) {
        for (var value : values) if (condition(value, context, depth)) return true;
        return false;
    }

    private static String operator(LiteralValue.ObjectValue object) {
        if (object.values().size() != 1) return null;
        var key = object.values().keySet().iterator().next();
        return OPERATORS.contains(key) ? key : null;
    }

    private static String pointer(LiteralValue value) {
        if (!(value instanceof LiteralValue.StringValue text)) {
            throw error("EXPRESSION_SHAPE_INVALID", "reference operand must be a string pointer");
        }
        validatePointer(text.value());
        return text.value();
    }

    private static void validatePointer(String pointer) {
        if (!pointer.isEmpty() && pointer.charAt(0) != '/') {
            throw error("CONTEXT_POINTER_INVALID", "JSON pointer must be empty or start with '/'");
        }
        for (var index = 0; index < pointer.length(); index++) {
            if (pointer.charAt(index) == '~'
                && (index + 1 >= pointer.length()
                || pointer.charAt(index + 1) != '0' && pointer.charAt(index + 1) != '1')) {
                throw error("CONTEXT_POINTER_INVALID", "JSON pointer contains an invalid escape");
            }
        }
    }

    private static LiteralValue required(LiteralValue root, String pointer, boolean failMissing) {
        var result = lookup(root, pointer);
        if (result.defined()) return result.value();
        if (failMissing) throw error("CONTEXT_REFERENCE_MISSING",
            "context reference does not exist: " + pointer);
        return LiteralValue.NullValue.INSTANCE;
    }

    private static Lookup lookup(LiteralValue root, String pointer) {
        if (pointer.isEmpty()) return new Lookup(true, root);
        LiteralValue current = root;
        for (var raw : pointer.substring(1).split("/", -1)) {
            var token = raw.replace("~1", "/").replace("~0", "~");
            if (current instanceof LiteralValue.ObjectValue object) {
                if (!object.values().containsKey(token)) return Lookup.MISSING;
                current = object.values().get(token);
            } else if (current instanceof LiteralValue.ListValue list) {
                if (!canonicalArrayIndex(token)) return Lookup.MISSING;
                int index;
                try {
                    index = Integer.parseInt(token);
                } catch (NumberFormatException ignored) {
                    return Lookup.MISSING;
                }
                if (index < 0 || index >= list.values().size()) return Lookup.MISSING;
                current = list.values().get(index);
            } else {
                return Lookup.MISSING;
            }
        }
        return new Lookup(true, current);
    }

    private static boolean canonicalArrayIndex(String token) {
        if (token.isEmpty() || token.charAt(0) == '-' || token.charAt(0) == '+') return false;
        if (token.length() > 1 && token.charAt(0) == '0') return false;
        for (var index = 0; index < token.length(); index++) {
            if (token.charAt(index) < '0' || token.charAt(index) > '9') return false;
        }
        return true;
    }

    private static List<LiteralValue> list(LiteralValue value, String operator) {
        if (value instanceof LiteralValue.ListValue list) return list.values();
        throw error("EXPRESSION_SHAPE_INVALID", operator + " operand must be an array");
    }

    private static List<LiteralValue> exactList(LiteralValue value, String operator, int size) {
        var values = list(value, operator);
        if (values.size() != size) throw error("EXPRESSION_SHAPE_INVALID",
            operator + " operand must contain exactly " + size + " items");
        return values;
    }

    private static void validate(LiteralValue value, boolean condition, int depth) {
        depth(depth);
        if (value instanceof LiteralValue.ListValue list) {
            if (condition) throw error("CONDITION_NOT_BOOLEAN",
                "condition must evaluate to a boolean");
            for (var item : list.values()) validate(item, false, depth + 1);
            return;
        }
        if (!(value instanceof LiteralValue.ObjectValue object)) {
            if (condition && !(value instanceof LiteralValue.BooleanValue)) {
                throw error("CONDITION_NOT_BOOLEAN", "condition must evaluate to a boolean");
            }
            return;
        }
        var operator = operator(object);
        if (operator == null) {
            if (condition) throw error("CONDITION_NOT_BOOLEAN",
                "condition must evaluate to a boolean");
            object.values().values().forEach(item -> validate(item, false, depth + 1));
            return;
        }
        var operand = object.values().get(operator);
        switch (operator) {
            case "$ref", "$defined" -> pointer(operand);
            case "$eq" -> exactList(operand, operator, 2)
                .forEach(item -> validate(item, false, depth + 1));
            case "$not" -> validate(operand, true, depth + 1);
            case "$all", "$any" -> list(operand, operator)
                .forEach(item -> validate(item, true, depth + 1));
            case "$if" -> {
                var values = exactList(operand, operator, 3);
                validate(values.get(0), true, depth + 1);
                validate(values.get(1), condition, depth + 1);
                validate(values.get(2), condition, depth + 1);
            }
            case "$literal" -> {
                if (condition && !(operand instanceof LiteralValue.BooleanValue)) {
                    throw error("CONDITION_NOT_BOOLEAN",
                        "$literal condition operand must be a boolean");
                }
            }
            default -> throw new AssertionError(operator);
        }
    }

    private static void depth(int depth) {
        if (depth > MAX_DEPTH) throw error("EXPRESSION_DEPTH_EXCEEDED",
            "expression nesting exceeds " + MAX_DEPTH);
    }

    private static ConfigException error(String code, String message) {
        return new ConfigException(new ConfigDiagnostic(
            ConfigStage.COMPILE, code, message, null, null), null);
    }

    private record Lookup(boolean defined, LiteralValue value) {
        private static final Lookup MISSING = new Lookup(false, LiteralValue.NullValue.INSTANCE);
    }
}
