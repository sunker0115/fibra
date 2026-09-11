package com.sstlfsj.fibra.plugins.tool;

import java.util.Objects;

public final class ToolException extends RuntimeException {
    private final ToolFailureCode code;

    public ToolException(ToolFailureCode code, String message) {
        this(code, message, null);
    }

    public ToolException(ToolFailureCode code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ToolFailureCode code() {
        return code;
    }
}
