package com.sstlfsj.fibra.client.protocol;

import java.util.Objects;

/** Host 分配后标识一次 client execution 会话的围栏。 */
public record SessionFence(String hostInstanceId, String clientExecutionId) {
    public SessionFence {
        hostInstanceId = required(hostInstanceId, "hostInstanceId");
        clientExecutionId = required(clientExecutionId, "clientExecutionId");
    }

    private static String required(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
