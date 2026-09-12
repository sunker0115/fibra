package com.sstlfsj.fibra.plugins.tool;

import java.util.List;
import java.util.Objects;

/** 工具调用的归一化终态；失败不携带成功产物。 */
public sealed interface ToolOutcome permits ToolOutcome.Success, ToolOutcome.Failure {
    record Success(ToolResult result) implements ToolOutcome {
        public Success {
            Objects.requireNonNull(result, "result");
        }
    }

    record Failure(ToolFailure error, List<ToolContent> content) implements ToolOutcome {
        public Failure {
            Objects.requireNonNull(error, "error");
            content = List.copyOf(content);
        }
    }
}
