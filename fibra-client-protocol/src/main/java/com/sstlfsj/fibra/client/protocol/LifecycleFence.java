package com.sstlfsj.fibra.client.protocol;

import java.util.Objects;

/** 精确绑定一次生命周期命令或回复的 unit generation 和操作身份。 */
public record LifecycleFence(SessionFence session, long unitTargetRevision, String runtimeInstanceId,
                             String lifecycleOperationId) {
    public LifecycleFence {
        session = Objects.requireNonNull(session, "session");
        if (unitTargetRevision < 1) throw new IllegalArgumentException("unitTargetRevision must be positive");
        runtimeInstanceId = required(runtimeInstanceId, "runtimeInstanceId");
        lifecycleOperationId = required(lifecycleOperationId, "lifecycleOperationId");
    }

    private static String required(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
