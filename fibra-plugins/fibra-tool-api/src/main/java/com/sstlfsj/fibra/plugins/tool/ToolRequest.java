package com.sstlfsj.fibra.plugins.tool;

import com.sstlfsj.fibra.CancellationToken;
import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Map;
import java.util.Objects;

public record ToolRequest(LiteralValue.ObjectValue arguments, CancellationToken cancellation) {
    public ToolRequest {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(cancellation, "cancellation");
    }

    public static ToolRequest of(Map<String, ?> arguments) {
        return of(arguments, CancellationToken.never());
    }

    public static ToolRequest of(Map<String, ?> arguments, CancellationToken cancellation) {
        Objects.requireNonNull(arguments, "arguments");
        return new ToolRequest((LiteralValue.ObjectValue) LiteralValue.of(arguments), cancellation);
    }
}
