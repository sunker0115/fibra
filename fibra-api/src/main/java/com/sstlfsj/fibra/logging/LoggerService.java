package com.sstlfsj.fibra.logging;

import com.sstlfsj.fibra.Disposable;

import java.util.List;

public interface LoggerService {
    List<LogMessage> buffer();

    int bufferSize();

    void bufferSize(int size);

    Disposable exporter(LogExporter exporter);
}
