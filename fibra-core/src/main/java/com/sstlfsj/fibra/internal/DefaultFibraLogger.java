package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.logging.FibraLogger;
import com.sstlfsj.fibra.logging.LogLevel;

import java.util.Objects;

final class DefaultFibraLogger implements FibraLogger {
    private final DefaultLoggerService service;
    private final Context context;
    private final String name;
    private final LogLevel level;

    DefaultFibraLogger(DefaultLoggerService service, Context context, String name, LogLevel level) {
        this.service = Objects.requireNonNull(service, "service");
        this.context = Objects.requireNonNull(context, "context");
        this.name = Objects.requireNonNull(name, "name");
        this.level = level;
    }

    public String name() {
        return name;
    }

    public void error(Object... arguments) {
        service.publish(context, name, level, LogLevel.ERROR, arguments);
    }

    public void info(Object... arguments) {
        service.publish(context, name, level, LogLevel.INFO, arguments);
    }

    public void warn(Object... arguments) {
        service.publish(context, name, level, LogLevel.WARN, arguments);
    }

    public void debug(Object... arguments) {
        service.publish(context, name, level, LogLevel.DEBUG, arguments);
    }
}
