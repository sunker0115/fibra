package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.logging.FibraLogger;
import com.sstlfsj.fibra.logging.LogExporter;
import com.sstlfsj.fibra.logging.LogLevel;
import com.sstlfsj.fibra.logging.LogMessage;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

final class DefaultLoggerService {
    private final List<LogMessage> buffer = new ArrayList<>();
    private final Map<Long, LogExporter> exporters = new LinkedHashMap<>();
    private final AtomicLong messageSequence = new AtomicLong();
    private long exporterSequence;
    private volatile int bufferSize = 1000;

    DefaultLoggerService() {
        addPermanentExporter(LogExporter.to(this::appendToBuffer));
        addPermanentExporter(LogExporter.to(this::exportToSlf4j, LogLevel.DEBUG));
    }

    List<LogMessage> buffer() {
        return buffer;
    }

    int bufferSize() {
        return bufferSize;
    }

    void bufferSize(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("buffer size must not be negative");
        }
        bufferSize = size;
        synchronized (buffer) {
            trimBuffer();
        }
    }

    Disposable exporter(DefaultContext owner, LogExporter exporter) {
        Objects.requireNonNull(exporter, "exporter");
        var id = new long[1];
        return new OwnedResource(owner.owner(), () -> {
            synchronized (exporters) {
                id[0] = ++exporterSequence;
                exporters.put(id[0], exporter);
            }
            return () -> Mono.fromRunnable(() -> {
                synchronized (exporters) {
                    exporters.remove(id[0]);
                }
            });
        }, "logging.exporter");
    }

    FibraLogger logger(DefaultContext context, String explicitName,
                       String derivedName) {
        var intercept = context.loggerIntercept();
        var name = explicitName;
        if (name == null && intercept != null) {
            name = intercept.name();
        }
        if (name == null) {
            name = derivedName;
        }
        return new DefaultFibraLogger(this, context, name,
            intercept == null ? null : intercept.level());
    }

    void publish(DefaultContext context, String name, LogLevel loggerLevel,
                 LogLevel messageLevel, Object[] arguments) {
        var instance = context.plugins().current().orElse(null);
        var message = new LogMessage(messageSequence.incrementAndGet(), Instant.now(),
            name, messageLevel, Arrays.asList(arguments), new WeakReference<>(instance));
        List<LogExporter> snapshot;
        synchronized (exporters) {
            snapshot = List.copyOf(exporters.values());
        }
        for (var exporter : snapshot) {
            var threshold = exporter.levels().getOrDefault(name,
                exporter.levels().getOrDefault("default",
                    loggerLevel == null ? exporter.defaultLevel() : loggerLevel));
            if (threshold.severity() < messageLevel.severity()) {
                continue;
            }
            exporter.export(message);
        }
    }

    private void addPermanentExporter(LogExporter exporter) {
        synchronized (exporters) {
            exporters.put(++exporterSequence, exporter);
        }
    }

    private void appendToBuffer(LogMessage message) {
        synchronized (buffer) {
            buffer.add(message);
            trimBuffer();
        }
    }

    private void trimBuffer() {
        var overflow = buffer.size() - bufferSize;
        if (overflow > 0) {
            buffer.subList(0, overflow).clear();
        }
    }

    private void exportToSlf4j(LogMessage message) {
        var logger = LoggerFactory.getLogger(message.name());
        var text = message.arguments().isEmpty()
            ? "" : String.valueOf(message.arguments().getFirst());
        var rest = message.arguments().stream().skip(1).toArray();
        switch (message.level()) {
            case ERROR -> logger.error(text, rest);
            case INFO -> logger.info(text, rest);
            case WARN -> logger.warn(text, rest);
            case DEBUG -> logger.debug(text, rest);
        }
    }
}
