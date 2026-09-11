package com.sstlfsj.fibra.plugins.storage;

import java.util.Objects;

public final class StorageException extends RuntimeException {
    private final StorageErrorCode code;

    public StorageException(StorageErrorCode code, String message) {
        this(code, message, null);
    }

    public StorageException(StorageErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public StorageErrorCode code() {
        return code;
    }
}
