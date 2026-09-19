package com.sstlfsj.fibra.engine;

import java.nio.file.Path;

public final class DeploymentTargetStoreException extends RuntimeException {
    private final Path path;
    public DeploymentTargetStoreException(String message, Path path, Throwable cause) {
        super(message, cause);
        this.path = path;
    }
    public Path path() { return path; }
}
