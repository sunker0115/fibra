package com.sstlfsj.fibra.engine;

import java.nio.file.Path;
import java.util.Objects;

public final class FibraDeploymentException extends RuntimeException {
    private final FibraDeploymentErrorStage stage;
    private final Path packagePath;

    public FibraDeploymentException(FibraDeploymentErrorStage stage, Path packagePath,
                                    String message, Throwable cause) {
        super(message, cause);
        this.stage = Objects.requireNonNull(stage, "stage");
        this.packagePath = packagePath == null ? null
            : packagePath.toAbsolutePath().normalize();
    }

    public FibraDeploymentErrorStage stage() {
        return stage;
    }

    public Path packagePath() {
        return packagePath;
    }
}
