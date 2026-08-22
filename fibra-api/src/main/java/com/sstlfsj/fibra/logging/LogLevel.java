package com.sstlfsj.fibra.logging;

public enum LogLevel {
    ERROR(0),
    INFO(1),
    WARN(2),
    DEBUG(3);

    private final int severity;

    LogLevel(int severity) {
        this.severity = severity;
    }

    public int severity() {
        return severity;
    }
}
