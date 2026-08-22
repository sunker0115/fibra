package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.logging.*;
import org.slf4j.LoggerFactory;

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

    public DefaultLoggerService(Context root) {
        Objects.requireNonNull(root, "root");
        addPermanentExporter(LogExporter.to(this::appendToBuffer));
        addPermanentExporter(LogExporter.to(this::exportToSlf4j, LogLevel.DEBUG));
    }

    public List<LogMessage> buffer() {
        return buffer;
    }

    public int bufferSize() {
        return bufferSize;
    }

    public void bufferSize(int bufferSize) {
        if (bufferSize < 0) {
            throw new IllegalArgumentException("buffer size must not be negative");
        }
        this.bufferSize = bufferSize;
    }

    Disposable exporter(DefaultContext owner, LogExporter exporter) {
        Objects.requireNonNull(exporter, "exporter");
        return owner.effect(() -> {
            long id;
            synchronized (exporters) {
                id = ++exporterSequence;
                exporters.put(id, exporter);
            }
            return () -> owner.lifecycle().run(() -> {
                synchronized (exporters) {
                    exporters.remove(id);
                }
            });
        }, "ctx.logger.exporter()");
    }

    public FibraLogger logger(DefaultContext context, String explicitName, String derivedName) {
        LoggerIntercept resolved = null;
        for (var value : context.interceptValues("logger")) {
            if (value instanceof LoggerIntercept intercept) {
                resolved = new LoggerIntercept(
                    intercept.name() == null ? resolved == null ? null : resolved.name() : intercept.name(),
                    intercept.level() == null ? resolved == null ? null : resolved.level() : intercept.level()
                );
            }
        }
        var name = explicitName;
        if (name == null && resolved != null) {
            name = resolved.name();
        }
        if (name == null) {
            name = derivedName == null ? context.fibra().name() : derivedName;
        }
        return new DefaultFibraLogger(this, context, name, resolved == null ? null : resolved.level());
    }

    void publish(Context context, String name, LogLevel loggerLevel,
                 LogLevel messageLevel, Object[] arguments) {
        var message = new LogMessage(
            messageSequence.incrementAndGet(),
            Instant.now(),
            name,
            messageLevel,
            Arrays.asList(arguments),
            new WeakReference<>(context.fibra())
        );
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
            int overflow = buffer.size() - bufferSize;
            if (overflow > 0) {
                buffer.subList(0, overflow).clear();
            }
        }
    }

    private void exportToSlf4j(LogMessage message) {
        var logger = LoggerFactory.getLogger(message.name());
        var text = message.arguments().isEmpty()
            ? ""
            : String.valueOf(message.arguments().getFirst());
        var rest = message.arguments().stream().skip(1).toArray();
        switch (message.level()) {
            case ERROR -> logger.error(text, rest);
            case INFO -> logger.info(text, rest);
            case WARN -> logger.warn(text, rest);
            case DEBUG -> logger.debug(text, rest);
        }
    }
}
