package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.logging.LogExporter;
import com.sstlfsj.fibra.logging.LogMessage;
import com.sstlfsj.fibra.logging.LoggerService;

import java.util.List;

final class BoundLoggerService implements LoggerService {
    private final DefaultLoggerService service;
    private final DefaultContext owner;

    BoundLoggerService(DefaultLoggerService service, DefaultContext owner) {
        this.service = service;
        this.owner = owner;
    }

    @Override
    public List<LogMessage> buffer() {
        return service.buffer();
    }

    @Override
    public int bufferSize() {
        return service.bufferSize();
    }

    @Override
    public void bufferSize(int size) {
        service.bufferSize(size);
    }

    @Override
    public Disposable exporter(LogExporter exporter) {
        return service.exporter(owner, exporter);
    }
}
