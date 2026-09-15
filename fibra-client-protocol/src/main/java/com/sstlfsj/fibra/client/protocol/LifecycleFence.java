package com.sstlfsj.fibra.client.protocol;

import java.util.Objects;

/** 精确绑定一次生命周期命令或回复的部署和操作身份。 */
public record LifecycleFence(SessionFence session, long targetRevision, String runtimeInstanceId,
                             String lifecycleOperationId) {
    public LifecycleFence {
        session = Objects.requireNonNull(session, "session");
        if (targetRevision < 1) throw new IllegalArgumentException("targetRevision must be positive");
        runtimeInstanceId = required(runtimeInstanceId, "runtimeInstanceId");
        lifecycleOperationId = required(lifecycleOperationId, "lifecycleOperationId");
    }

    private static String required(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
