package com.sstlfsj.fibra.loader.config;

import java.nio.file.Path;
import java.util.Objects;

/** 携带稳定阶段和定位信息的配置装载异常。 */
public final class FibraConfigException extends IllegalStateException {
    private final FibraConfigErrorStage stage;
    private final Path path;
    private final String entryId;
    private final String pluginId;

    FibraConfigException(FibraConfigErrorStage stage, String message, Path path,
                         String entryId, String pluginId, Throwable cause) {
        super(message, cause);
        this.stage = Objects.requireNonNull(stage, "stage");
        this.path = path;
        this.entryId = entryId;
        this.pluginId = pluginId;
    }

    static FibraConfigException at(FibraConfigErrorStage stage, Path path,
                                   String message, Throwable cause) {
        return new FibraConfigException(stage, message, path, null, null, cause);
    }

    public FibraConfigErrorStage stage() {
        return stage;
    }

    public Path path() {
        return path;
    }

    public String entryId() {
        return entryId;
    }

    public String pluginId() {
        return pluginId;
    }
}
