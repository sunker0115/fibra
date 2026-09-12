package com.sstlfsj.fibra.plugins.tool;

import java.util.Objects;

/** 可跨运行时传递的工具失败，不携带异常堆栈或宿主诊断。 */
public record ToolFailure(ToolFailureCode code, String message) {
    public ToolFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
