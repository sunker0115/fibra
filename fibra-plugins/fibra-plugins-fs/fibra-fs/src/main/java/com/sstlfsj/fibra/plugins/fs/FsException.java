package com.sstlfsj.fibra.plugins.fs;

import java.util.Objects;

public final class FsException extends RuntimeException {
    private final FsErrorCode code;

    public FsException(FsErrorCode code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
    }

    public FsException(FsErrorCode code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public FsErrorCode code() {
        return code;
    }
}
