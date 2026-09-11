package com.sstlfsj.fibra.value;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** 可跨运行代传递的封闭数据值；不持有插件对象或可变容器。 */
public sealed interface LiteralValue {
    static LiteralValue of(Object value) {
        return freeze(value, new IdentityHashMap<>());
    }

    /** 返回只包含 JDK 数据类型的递归不可变投影，数字统一使用 BigDecimal。 */
    default Object toJava() {
        return switch (this) {
            case NullValue ignored -> null;
            case BooleanValue scalar -> scalar.value();
            case StringValue scalar -> scalar.value();
            case NumberValue scalar -> scalar.value();
            case ListValue list -> list.values().stream().map(LiteralValue::toJava).toList();
            case ObjectValue object -> {
                var result = new LinkedHashMap<String, Object>();
                object.values().forEach((key, value) -> result.put(key, value.toJava()));
                yield Collections.unmodifiableMap(result);
            }
        };
    }

    /** 对象键按字符串自然序排列，列表保序，等值数字只有一种表示。 */
    default String canonicalJson() {
        var output = new StringBuilder();
        append(this, output);
        return output.toString();
    }

    enum NullValue implements LiteralValue { INSTANCE }

    record BooleanValue(boolean value) implements LiteralValue { }

    record StringValue(String value) implements LiteralValue {
        public StringValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record NumberValue(BigDecimal value) implements LiteralValue {
        public NumberValue {
            value = new BigDecimal(Objects.requireNonNull(value, "value").toString())
                .stripTrailingZeros();
        }
    }

    record ListValue(List<LiteralValue> values) implements LiteralValue {
        public ListValue {
            values = List.copyOf(values);
        }
    }

    record ObjectValue(Map<String, LiteralValue> values) implements LiteralValue {
        public ObjectValue {
            var sorted = new TreeMap<String, LiteralValue>();
            values.forEach((key, value) -> sorted.put(Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(value, "value")));
            values = Collections.unmodifiableMap(sorted);
        }
    }

    private static LiteralValue freeze(Object value, IdentityHashMap<Object, Boolean> visiting) {
        if (value == null) return NullValue.INSTANCE;
        if (value instanceof LiteralValue literal) return literal;
        if (value instanceof String text) return new StringValue(text);
        if (value instanceof Boolean bool) return new BooleanValue(bool);
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
            || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) {
            return new NumberValue(new BigDecimal(value.toString()));
        }
        if (value instanceof Float number && Float.isFinite(number)) {
            return new NumberValue(new BigDecimal(number.toString()));
        }
        if (value instanceof Double number && Double.isFinite(number)) {
            return new NumberValue(new BigDecimal(number.toString()));
        }
        if (!(value instanceof Map<?, ?>) && !(value instanceof List<?>)) {
            throw new IllegalArgumentException("unsupported literal value type "
                + value.getClass().getName());
        }
        if (visiting.put(value, true) != null) {
            throw new IllegalArgumentException("literal values must not contain cycles");
        }
        try {
            if (value instanceof Map<?, ?> map) {
                var result = new TreeMap<String, LiteralValue>();
                map.forEach((key, nested) -> {
                    if (!(key instanceof String name)) {
                        throw new IllegalArgumentException("literal object keys must be strings");
                    }
                    result.put(name, freeze(nested, visiting));
                });
                return new ObjectValue(result);
            }
            var result = new ArrayList<LiteralValue>();
            ((List<?>) value).forEach(nested -> result.add(freeze(nested, visiting)));
            return new ListValue(result);
        } finally {
            visiting.remove(value);
        }
    }

    private static void append(LiteralValue value, StringBuilder output) {
        switch (value) {
            case NullValue ignored -> output.append("null");
            case BooleanValue scalar -> output.append(scalar.value());
            case StringValue scalar -> quote(scalar.value(), output);
            case NumberValue scalar -> output.append(scalar.value());
            case ListValue list -> {
                output.append('[');
                var separator = "";
                for (var entry : list.values()) {
                    output.append(separator);
                    append(entry, output);
                    separator = ",";
                }
                output.append(']');
            }
            case ObjectValue object -> {
                output.append('{');
                var separator = "";
                for (var entry : object.values().entrySet()) {
                    output.append(separator);
                    quote(entry.getKey(), output);
                    output.append(':');
                    append(entry.getValue(), output);
                    separator = ",";
                }
                output.append('}');
            }
        }
    }

    private static void quote(String text, StringBuilder output) {
        output.append('"');
        for (var index = 0; index < text.length(); index++) {
            var character = text.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                default -> {
                    if (character < 0x20 || Character.isSurrogate(character)) {
                        output.append("\\u");
                        for (var shift = 12; shift >= 0; shift -= 4) {
                            output.append(Character.forDigit((character >> shift) & 15, 16));
                        }
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }
}
