package com.sstlfsj.fibra.plugins.tool;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Objects;

public record ToolDescriptor(String displayName, String description,
                             LiteralValue.ObjectValue inputSchema,
                             LiteralValue.ObjectValue outputSchema) {
    public ToolDescriptor {
        displayName = text(displayName, "displayName");
        description = text(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
        Objects.requireNonNull(outputSchema, "outputSchema");
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
