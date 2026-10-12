package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只负责 profile 语言准入和执行图计费，实例断言由标准库执行。 */
final class SchemaProfile {
    static final String PROFILE = "fibra-config-1";
    static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
    static final Set<String> ASSERTIONS = Set.of("type", "const", "enum", "properties", "required",
        "additionalProperties", "minProperties", "maxProperties", "items", "minItems", "maxItems",
        "minLength", "maxLength", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
        "allOf", "anyOf", "oneOf", "false");
    private static final Set<String> TYPES = Set.of("null", "string", "boolean", "integer", "number", "object", "array");
    private static final Set<String> COMBINATIONS = Set.of("allOf", "anyOf", "oneOf");
    private static final Set<String> COMMON = Set.of("type", "const", "enum", "title", "description");
    private static final Map<String, Set<String>> TYPED = Map.of(
        "object", Set.of("properties", "required", "additionalProperties", "minProperties", "maxProperties"),
        "array", Set.of("items", "minItems", "maxItems"),
        "string", Set.of("minLength", "maxLength"),
        "number", Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum"),
        "integer", Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum"));

    private final Map<String, Node> definitions = new LinkedHashMap<>();
    private int maximumCombinations;

    Admission admit(ConfigSchemaSource source) {
        if (!source.profile().equals(PROFILE)) {
            throw new ConfigSchemaException(ConfigSchemaException.Code.UNSUPPORTED_PROFILE);
        }
        var stats = SchemaBudget.schema(source.schema());
        var schema = source.schema().values();
        if (!DIALECT.equals(string(schema.get("$schema")))) throw invalid();
        if (schema.containsKey("$defs")) {
            for (var entry : object(schema.get("$defs")).entrySet()) {
                if (!entry.getKey().matches("[A-Za-z_][A-Za-z0-9_-]{0,63}")) throw invalid();
                definitions.put(entry.getKey(), node(entry.getValue(), false, false));
            }
        }
        var root = node(source.schema(), true, false);
        long expanded = expand(root, 1, 0, 0, new IdentityHashMap<>());
        for (var definition : definitions.values()) {
            expanded = SchemaBudget.add(expanded, expand(definition, 1, 0, 0, new IdentityHashMap<>()), SchemaBudget.EXPANDED);
            if (expanded > SchemaBudget.EXPANDED) throw SchemaBudget.exceeded();
        }
        return new Admission(stats.bytes(), expanded, maximumCombinations, root, Map.copyOf(definitions));
    }

    private Node node(LiteralValue value, boolean root, boolean allowBoolean) {
        if (allowBoolean && value instanceof LiteralValue.BooleanValue) return new Node(1, null, false, List.of(), Map.of(), null);
        var map = object(value);
        var allowed = new HashSet<String>(Set.of("title", "description"));
        if (root) allowed.addAll(Set.of("$schema", "$defs"));
        var children = new ArrayList<Node>();
        var properties = new LinkedHashMap<String, Node>();
        Node items = null;
        String reference = null;
        boolean combination = false;
        long weight = 1;
        if (map.containsKey("$ref")) {
            allowed.add("$ref");
            var ref = string(map.get("$ref"));
            if (!ref.matches("#/[$]defs/[A-Za-z_][A-Za-z0-9_-]{0,63}")) throw invalid();
            reference = ref.substring(8);
        } else if (map.keySet().stream().anyMatch(COMBINATIONS::contains)) {
            var keys = map.keySet().stream().filter(COMBINATIONS::contains).toList();
            if (keys.size() != 1) throw invalid();
            var key = keys.getFirst();
            allowed.add(key);
            var branches = list(map.get(key));
            if (branches.size() < 2 || branches.size() > 8) throw invalid();
            for (var branch : branches) children.add(node(branch, false, false));
            combination = true;
        } else {
            var type = type(map.get("type"));
            allowed.addAll(COMMON);
            allowed.addAll(TYPED.getOrDefault(type, Set.of()));
            if (type.equals("object")) {
                if (!map.containsKey("additionalProperties")) throw invalid();
                children.add(node(map.get("additionalProperties"), false, true));
                if (map.containsKey("properties")) {
                    for (var entry : object(map.get("properties")).entrySet()) {
                        if (entry.getKey().length() > 128) throw SchemaBudget.exceeded();
                        var child = node(entry.getValue(), false, true);
                        children.add(child);
                        properties.put(entry.getKey(), child);
                    }
                }
                if (map.containsKey("required")) {
                    var names = new HashSet<String>();
                    for (var member : list(map.get("required"))) {
                        var name = string(member);
                        if (!properties.containsKey(name) || !names.add(name)) throw invalid();
                    }
                }
            } else if (type.equals("array")) {
                if (!map.containsKey("items")) throw invalid();
                items = node(map.get("items"), false, true);
                children.add(items);
            }
            if (map.containsKey("const")) scalar(map.get("const"));
            if (map.containsKey("enum")) {
                var values = list(map.get("enum"));
                if (values.isEmpty() || values.size() > 64) throw invalid();
                var distinct = new HashSet<LiteralValue>();
                for (var member : values) {
                    scalar(member);
                    if (!distinct.add(member)) throw invalid();
                }
            }
        }
        if (!allowed.containsAll(map.keySet())) throw invalid();
        for (var entry : map.entrySet()) {
            var key = entry.getKey();
            var child = entry.getValue();
            weight += textWeight(key);
            switch (key) {
                case "$defs" -> {
                    weight++;
                    for (var name : object(child).keySet()) weight += textWeight(name);
                }
                case "properties" -> {
                    weight++;
                    for (var name : object(child).keySet()) weight += textWeight(name);
                }
                case "items", "additionalProperties" -> { }
                case "allOf", "anyOf", "oneOf" -> weight++;
                case "title", "description" -> {
                    var text = string(child);
                    if (text.length() > (key.equals("title") ? 256 : 4096)) throw SchemaBudget.exceeded();
                    weight += textWeight(text);
                }
                case "minLength", "maxLength", "minItems", "maxItems", "minProperties", "maxProperties" -> {
                    count(child, key.endsWith("Length") ? 65536 : 10000);
                    weight++;
                }
                case "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum" -> {
                    if (!(child instanceof LiteralValue.NumberValue)) throw invalid();
                    weight++;
                }
                default -> weight += literalWeight(child);
            }
        }
        return new Node(weight, reference, combination, List.copyOf(children), Map.copyOf(properties), items);
    }

    private long expand(Node node, int depth, int references, int combinations, IdentityHashMap<Node, Boolean> visiting) {
        if (visiting.put(node, true) != null) throw invalid();
        try {
            if (node.combination()) combinations++;
            if (depth > 64 || references > 16 || combinations > 4) throw SchemaBudget.exceeded();
            maximumCombinations = Math.max(maximumCombinations, combinations);
            long total = node.weight();
            if (node.reference() != null) {
                var target = definitions.get(node.reference());
                if (target == null) throw invalid();
                total = SchemaBudget.add(total, expand(target, depth + 1, references + 1, combinations, visiting), SchemaBudget.EXPANDED);
            }
            for (var child : node.children()) {
                total = SchemaBudget.add(total, expand(child, depth + 1, references, combinations, visiting), SchemaBudget.EXPANDED);
                if (total > SchemaBudget.EXPANDED) throw SchemaBudget.exceeded();
            }
            if (total > SchemaBudget.EXPANDED) throw SchemaBudget.exceeded();
            return total;
        } finally {
            visiting.remove(node);
        }
    }

    private static String type(LiteralValue value) {
        if (value instanceof LiteralValue.StringValue text && TYPES.contains(text.value())) return text.value();
        if (value instanceof LiteralValue.ListValue list && list.values().size() == 2) {
            var left = string(list.values().get(0));
            var right = string(list.values().get(1));
            if (TYPES.contains(left) && TYPES.contains(right) && !left.equals(right)
                && (left.equals("null") || right.equals("null"))) return left.equals("null") ? right : left;
        }
        throw invalid();
    }

    private static void count(LiteralValue value, int maximum) {
        if (!(value instanceof LiteralValue.NumberValue number) || number.value().signum() < 0
            || number.value().scale() > 0) throw invalid();
        if (number.value().compareTo(java.math.BigDecimal.valueOf(maximum)) > 0) throw SchemaBudget.exceeded();
    }

    private static void scalar(LiteralValue value) {
        if (value instanceof LiteralValue.ObjectValue || value instanceof LiteralValue.ListValue) throw invalid();
    }

    private static long literalWeight(LiteralValue value) {
        if (value instanceof LiteralValue.StringValue text) return textWeight(text.value());
        if (value instanceof LiteralValue.ListValue list) return 1 + list.values().stream().mapToLong(SchemaProfile::literalWeight).sum();
        return 1;
    }

    private static long textWeight(String text) {
        return 1 + SchemaBudget.units(text.length());
    }

    private static Map<String, LiteralValue> object(LiteralValue value) {
        if (!(value instanceof LiteralValue.ObjectValue object)) throw invalid();
        if (object.values().size() > 128) throw SchemaBudget.exceeded();
        return object.values();
    }

    private static List<LiteralValue> list(LiteralValue value) {
        if (value instanceof LiteralValue.ListValue list) return list.values();
        throw invalid();
    }

    private static String string(LiteralValue value) {
        if (value instanceof LiteralValue.StringValue text) return text.value();
        throw invalid();
    }

    private static ConfigSchemaException invalid() {
        return SchemaDocumentReader.invalid();
    }

    record Admission(int bytes, long expanded, int combinations, Node root, Map<String, Node> definitions) { }
    record Node(long weight, String reference, boolean combination, List<Node> children,
                Map<String, Node> properties, Node items) { }
}
