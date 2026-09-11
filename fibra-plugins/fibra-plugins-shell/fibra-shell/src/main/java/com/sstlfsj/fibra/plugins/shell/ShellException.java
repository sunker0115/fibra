package com.sstlfsj.fibra.plugins.shell;

import java.util.Objects;

public final class ShellException extends RuntimeException {
    private final ShellErrorCode code;

    public ShellException(ShellErrorCode code, String message) {
        this(code, message, null);
    }

    public ShellException(ShellErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ShellErrorCode code() {
        return code;
    }
}
