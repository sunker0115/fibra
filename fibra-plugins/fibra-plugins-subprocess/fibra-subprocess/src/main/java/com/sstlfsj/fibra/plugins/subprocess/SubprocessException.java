package com.sstlfsj.fibra.plugins.subprocess;

import java.util.Objects;

public final class SubprocessException extends RuntimeException {
    private final SubprocessErrorCode code;

    public SubprocessException(SubprocessErrorCode code, String message) {
        this(code, message, null);
    }

    public SubprocessException(SubprocessErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public SubprocessErrorCode code() {
        return code;
    }
}
