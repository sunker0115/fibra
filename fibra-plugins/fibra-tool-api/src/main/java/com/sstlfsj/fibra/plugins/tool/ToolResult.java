package com.sstlfsj.fibra.plugins.tool;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Objects;

public record ToolResult(String text, LiteralValue data) {
    public ToolResult {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(data, "data");
    }

    public static ToolResult text(String text) {
        return new ToolResult(text, LiteralValue.of(null));
    }

    public static ToolResult structured(LiteralValue data) {
        return new ToolResult("", data);
    }
}
