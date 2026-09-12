package com.sstlfsj.fibra.plugins.tool;

import java.util.Objects;

public final class ToolException extends RuntimeException {
    private final ToolFailure failure;

    public ToolException(ToolFailureCode code, String message) {
        this(code, message, null);
    }

    public ToolException(ToolFailureCode code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        failure = new ToolFailure(code, message);
    }

    public ToolFailure failure() {
        return failure;
    }
}
