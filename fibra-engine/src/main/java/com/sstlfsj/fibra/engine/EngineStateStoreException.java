package com.sstlfsj.fibra.engine;

import java.nio.file.Path;

/** 持久目标或提交事实无法可靠写入、读取或验证。 */
public final class EngineStateStoreException extends RuntimeException {
    private final Path path;

    public EngineStateStoreException(String message, Path path, Throwable cause) {
        super(message, cause);
        this.path = path;
    }

    public Path path() { return path; }
}
