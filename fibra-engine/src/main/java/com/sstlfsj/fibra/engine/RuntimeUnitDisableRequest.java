package com.sstlfsj.fibra.engine;

import java.util.Objects;

/** Runtime unit 请求停用自身时携带的精确代次围栏与原因。 */
public record RuntimeUnitDisableRequest(RuntimeUnitFence fence,
                                        String reason) {
    public RuntimeUnitDisableRequest {
        Objects.requireNonNull(fence, "fence");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("disable reason must not be blank");
        }
    }

    public static RuntimeUnitDisableRequest of(RuntimeUnitFence fence,
                                               String reason) {
        return new RuntimeUnitDisableRequest(fence, reason);
    }
}
