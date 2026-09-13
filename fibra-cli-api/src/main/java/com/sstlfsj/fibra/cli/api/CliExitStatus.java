package com.sstlfsj.fibra.cli.api;

public enum CliExitStatus {
    SUCCESS(0),
    USAGE_ERROR(2),
    STARTUP_ERROR(3),
    INVOCATION_ERROR(4),
    STALE_OR_REVOKED(5),
    CLOSING(6),
    CLOSE_ERROR(7),
    DRAIN_TIMEOUT(8),
    CANCELLED(130);

    private final int code;

    CliExitStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
