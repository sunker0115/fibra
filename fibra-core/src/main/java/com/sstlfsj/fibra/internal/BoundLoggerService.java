package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.logging.LogExporter;
import com.sstlfsj.fibra.logging.LogMessage;
import com.sstlfsj.fibra.logging.LoggerService;

import java.util.List;
import java.util.Objects;

final class BoundLoggerService implements LoggerService {
    private final DefaultLoggerService service;
    private final DefaultContext owner;

    public BoundLoggerService(DefaultLoggerService service, DefaultContext owner) {
        this.service = Objects.requireNonNull(service, "service");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    public List<LogMessage> buffer() {
        return service.buffer();
    }

    public int bufferSize() {
        return service.bufferSize();
    }

    public void bufferSize(int size) {
        service.bufferSize(size);
    }

    public Disposable exporter(LogExporter exporter) {
        return service.exporter(owner, exporter);
    }
}
