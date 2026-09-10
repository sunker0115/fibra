package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.logging.FibraLogger;
import com.sstlfsj.fibra.logging.LogLevel;

import java.util.Objects;

final class DefaultFibraLogger implements FibraLogger {
    private final DefaultLoggerService service;
    private final DefaultContext context;
    private final String name;
    private final LogLevel level;

    DefaultFibraLogger(DefaultLoggerService service, DefaultContext context,
                       String name, LogLevel level) {
        this.service = Objects.requireNonNull(service, "service");
        this.context = Objects.requireNonNull(context, "context");
        this.name = Objects.requireNonNull(name, "name");
        this.level = level;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void error(Object... arguments) {
        service.publish(context, name, level, LogLevel.ERROR, arguments);
    }

    @Override
    public void info(Object... arguments) {
        service.publish(context, name, level, LogLevel.INFO, arguments);
    }

    @Override
    public void warn(Object... arguments) {
        service.publish(context, name, level, LogLevel.WARN, arguments);
    }

    @Override
    public void debug(Object... arguments) {
        service.publish(context, name, level, LogLevel.DEBUG, arguments);
    }
}
