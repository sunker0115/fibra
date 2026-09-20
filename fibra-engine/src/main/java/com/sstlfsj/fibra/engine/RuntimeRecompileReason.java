package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.RuntimeId;

import java.util.Objects;

public record RuntimeRecompileReason(RuntimeId sourceRuntimeId, String code,
                                     String detail) {
    public RuntimeRecompileReason {
        Objects.requireNonNull(sourceRuntimeId, "sourceRuntimeId");
        code = required(code, "recompile reason code");
        detail = required(detail, "recompile reason detail");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
