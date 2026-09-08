package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.logging.FibraLogger;
import com.sstlfsj.fibra.logging.LogLevel;

import java.util.Objects;

final class DefaultFibraLogger implements FibraLogger {
    private final DefaultLoggerService service;
    private final String name;

    DefaultFibraLogger(DefaultLoggerService service, String name) {
        this.service = Objects.requireNonNull(service, "service");
        this.name = Objects.requireNonNull(name, "name");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void error(Object... arguments) {
        service.publish(name, LogLevel.ERROR, arguments);
    }

    @Override
    public void info(Object... arguments) {
        service.publish(name, LogLevel.INFO, arguments);
    }

    @Override
    public void warn(Object... arguments) {
        service.publish(name, LogLevel.WARN, arguments);
    }

    @Override
    public void debug(Object... arguments) {
        service.publish(name, LogLevel.DEBUG, arguments);
    }
}
