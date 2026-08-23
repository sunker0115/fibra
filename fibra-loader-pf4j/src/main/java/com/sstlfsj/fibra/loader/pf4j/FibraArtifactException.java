package com.sstlfsj.fibra.loader.pf4j;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** 带稳定失败阶段和制品身份的插件装载异常。 */
public final class FibraArtifactException extends RuntimeException {
    private final FibraArtifactErrorStage stage;
    private final List<Path> packages;
    private final List<String> artifactIds;

    FibraArtifactException(FibraArtifactErrorStage stage, List<Path> packages,
                           List<String> artifactIds, String message, Throwable cause) {
        super(message, cause);
        this.stage = Objects.requireNonNull(stage, "stage");
        this.packages = List.copyOf(packages);
        this.artifactIds = List.copyOf(artifactIds);
    }

    public FibraArtifactErrorStage stage() {
        return stage;
    }

    public List<Path> packages() {
        return packages;
    }

    public List<String> artifactIds() {
        return artifactIds;
    }
}
