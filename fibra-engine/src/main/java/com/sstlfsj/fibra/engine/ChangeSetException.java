package com.sstlfsj.fibra.engine;

public final class ChangeSetException extends RuntimeException {
    private final String transactionId;

    ChangeSetException(String transactionId, String message, Throwable cause) {
        super(message, cause);
        this.transactionId = transactionId;
    }

    public String transactionId() {
        return transactionId;
    }
}
