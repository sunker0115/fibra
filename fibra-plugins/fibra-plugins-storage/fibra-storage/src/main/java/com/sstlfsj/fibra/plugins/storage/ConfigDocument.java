package com.sstlfsj.fibra.plugins.storage;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** One immutable, JSON-compatible configuration snapshot. */
public record ConfigDocument(long revision, Map<String, LiteralValue> values) {
    public ConfigDocument {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        Objects.requireNonNull(values, "values");
        var copy = new TreeMap<String, LiteralValue>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("config key must not be blank");
            }
            if (value == null) {
                throw new IllegalArgumentException("config value must not be null");
            }
            copy.put(key, value);
        });
        values = Collections.unmodifiableMap(copy);
    }

    public static ConfigDocument empty() {
        return new ConfigDocument(0, Map.of());
    }

    public static ConfigDocument of(long revision, Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        var frozen = new TreeMap<String, LiteralValue>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("config key must not be blank");
            }
            frozen.put(key, LiteralValue.of(value));
        });
        return new ConfigDocument(revision, frozen);
    }

    public Optional<LiteralValue> find(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("config key must not be blank");
        }
        return Optional.ofNullable(values.get(key));
    }
}
