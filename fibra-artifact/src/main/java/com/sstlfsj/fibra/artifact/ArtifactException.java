package com.sstlfsj.fibra.artifact;

import java.nio.file.Path;

public final class ArtifactException extends RuntimeException {
    private final ArtifactPhase phase;
    private final Path path;

    ArtifactException(ArtifactPhase phase, String message, Path path, Throwable cause) {
        super(message, cause);
        this.phase = phase;
        this.path = path;
    }

    public ArtifactPhase phase() {
        return phase;
    }

    public Path path() {
        return path;
    }
}
