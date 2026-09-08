package com.sstlfsj.fibra.engine;

import java.nio.file.Path;

public final class TransactionJournalException extends RuntimeException {
    private final Path path;

    public TransactionJournalException(String message, Path path, Throwable cause) {
        super(message, cause);
        this.path = path;
    }

    public Path path() {
        return path;
    }
}
