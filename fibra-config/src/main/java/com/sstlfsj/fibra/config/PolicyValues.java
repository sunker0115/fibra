package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Map;

final class PolicyValues {
    private PolicyValues() { }

    static Map<String, LiteralValue> realms(Map<String, LiteralValue> values) {
        values = Map.copyOf(values);
        values.forEach((key, value) -> {
            if (key.isBlank()) {
                throw new IllegalArgumentException("realm keys must not be blank");
            }
            switch (value) {
                case LiteralValue.BooleanValue ignored -> { }
                case LiteralValue.StringValue text when !text.value().isBlank() -> { }
                case LiteralValue.NullValue ignored -> { }
                default -> throw new IllegalArgumentException(
                    "realm values must be true, false, a non-blank string, or null");
            }
        });
        return values;
    }

    static Map<String, LiteralValue> intercepts(Map<String, LiteralValue> values) {
        values = Map.copyOf(values);
        if (values.keySet().stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("intercept keys must not be blank");
        }
        return values;
    }
}
