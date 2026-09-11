package com.sstlfsj.fibra.plugins.storage;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Objects;

/** Notification emitted after one write is durable and visible in memory. */
public record ConfigChange(long revision, String key, ConfigChangeOperation operation,
                           LiteralValue value) {
    public ConfigChange {
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("config key must not be blank");
        }
        Objects.requireNonNull(operation, "operation");
        if (operation == ConfigChangeOperation.PUT && value == null) {
            throw new IllegalArgumentException("put change must carry a value");
        }
        if (operation == ConfigChangeOperation.REMOVED && value != null) {
            throw new IllegalArgumentException("removed change must not carry a value");
        }
    }
}
