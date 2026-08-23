package com.sstlfsj.fibra.loader.config;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** 一次 watcher 刷新失败的结构化通知。 */
public record FibraConfigReloadFailure(Path path, FibraConfigException exception,
                                       Instant occurredAt) {
    public FibraConfigReloadFailure {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(exception, "exception");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
