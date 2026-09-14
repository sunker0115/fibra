package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.engine.TargetSaveState;

import java.time.Instant;
import java.util.Objects;

public record PluginAuditEntry(long sequence, Instant timestamp, String operation,
                               String target, boolean succeeded,
                               TargetSaveState targetSaveState,
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
        Objects.requireNonNull(targetSaveState, "targetSaveState");
        if (succeeded && targetSaveState != TargetSaveState.SAVED) {
            throw new IllegalArgumentException("successful audit entry must have a saved target");
        }
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private long sequence;
        private Instant timestamp;
        private String operation;
        private String target;
        private boolean succeeded;
        private TargetSaveState targetSaveState;
        private String viewRevision;
        private String detail;

        private Builder() { }

        public Builder sequence(long value) { sequence = value; return this; }
        public Builder timestamp(Instant value) { timestamp = value; return this; }
        public Builder operation(String value) { operation = value; return this; }
        public Builder target(String value) { target = value; return this; }
        public Builder succeeded(boolean value) { succeeded = value; return this; }
        public Builder targetSaveState(TargetSaveState value) {
            targetSaveState = value; return this;
        }
        public Builder viewRevision(String value) { viewRevision = value; return this; }
        public Builder detail(String value) { detail = value; return this; }
        public PluginAuditEntry build() {
            return new PluginAuditEntry(sequence, timestamp, operation, target, succeeded,
                targetSaveState, viewRevision, detail);
        }
    }
}
