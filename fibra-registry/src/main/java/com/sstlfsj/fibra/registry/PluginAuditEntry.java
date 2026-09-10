package com.sstlfsj.fibra.registry;

import java.time.Instant;
import java.util.Objects;

public record PluginAuditEntry(long sequence, Instant timestamp, String operation,
                               String target, boolean succeeded,
                               String viewRevision, String detail) {
    public PluginAuditEntry {
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(timestamp, "timestamp");
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
    }
}
