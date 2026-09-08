package com.sstlfsj.fibra.logging;

import com.sstlfsj.fibra.PluginInstance;

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.List;

public record LogMessage(
    long sequence,
    Instant timestamp,
    String name,
    LogLevel level,
    List<Object> arguments,
    WeakReference<PluginInstance<?>> pluginInstance
) {
    public LogMessage {
        arguments = List.copyOf(arguments);
    }
}
