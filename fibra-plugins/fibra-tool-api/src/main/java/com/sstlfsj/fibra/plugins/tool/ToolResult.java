package com.sstlfsj.fibra.plugins.tool;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Objects;
import java.util.List;
import java.util.Optional;

/** 成功产物；结构值缺省与显式 JSON null 不等价。 */
public record ToolResult(List<ToolContent> content, Optional<LiteralValue> structuredContent) {
    public ToolResult {
        content = List.copyOf(content);
        Objects.requireNonNull(structuredContent, "structuredContent");
    }

    public static ToolResult text(String text) {
        return new ToolResult(List.of(ToolContent.text(text)), Optional.empty());
    }

    public static ToolResult structured(LiteralValue value) {
        Objects.requireNonNull(value, "value");
        return textAndStructured(value.canonicalJson(), value);
    }

    public static ToolResult textAndStructured(String text, LiteralValue value) {
        return new ToolResult(List.of(ToolContent.text(text)), Optional.of(value));
    }
}
