package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.RuntimeId;

import java.util.Objects;

/** 精确标识一个 runtime unit 的当前执行代次。 */
public record RuntimeUnitFence(RuntimeId runtimeId,
                               ExecutionUnitKey unitKey,
                               long unitTargetRevision,
                               String runtimeInstanceId) {
    public RuntimeUnitFence {
        Objects.requireNonNull(runtimeId, "runtimeId");
        Objects.requireNonNull(unitKey, "unitKey");
        if (unitTargetRevision < 1) {
            throw new IllegalArgumentException(
                "unit target revision must be positive");
        }
        runtimeInstanceId = required(runtimeInstanceId,
            "runtime instance id");
    }

    public static Builder builder(RuntimeId runtimeId,
                                  ExecutionUnitKey unitKey) {
        return new Builder(runtimeId, unitKey);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static final class Builder {
        private final RuntimeId runtimeId;
        private final ExecutionUnitKey unitKey;
        private long unitTargetRevision;
        private String runtimeInstanceId;

        private Builder(RuntimeId runtimeId, ExecutionUnitKey unitKey) {
            this.runtimeId = Objects.requireNonNull(runtimeId, "runtimeId");
            this.unitKey = Objects.requireNonNull(unitKey, "unitKey");
        }

        public Builder unitTargetRevision(long value) {
            unitTargetRevision = value;
            return this;
        }

        public Builder runtimeInstanceId(String value) {
            runtimeInstanceId = value;
            return this;
        }

        public RuntimeUnitFence build() {
            return new RuntimeUnitFence(runtimeId, unitKey,
                unitTargetRevision, runtimeInstanceId);
        }
    }
}
