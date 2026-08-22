package com.sstlfsj.fibra.logging;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

@FunctionalInterface
public interface LogExporter {
    void export(LogMessage message);

    default LogLevel defaultLevel() {
        return LogLevel.INFO;
    }

    default Map<String, LogLevel> levels() {
        return Map.of();
    }

    static LogExporter to(Consumer<LogMessage> consumer) {
        return to(consumer, LogLevel.INFO);
    }

    static LogExporter to(Consumer<LogMessage> consumer, LogLevel level) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(level, "level");
        return new LogExporter() {
            @Override
            public void export(LogMessage message) {
                consumer.accept(message);
            }

            @Override
            public LogLevel defaultLevel() {
                return level;
            }
        };
    }
}
