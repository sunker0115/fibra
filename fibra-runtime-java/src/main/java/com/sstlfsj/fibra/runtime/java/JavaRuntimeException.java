package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;

public final class JavaRuntimeException extends RuntimeException {
    private final JavaRuntimePhase phase;
    private final ArtifactId artifactId;

    JavaRuntimeException(JavaRuntimePhase phase, ArtifactId artifactId,
                         String message, Throwable cause) {
        super(message, cause);
        this.phase = phase;
        this.artifactId = artifactId;
    }

    public JavaRuntimePhase phase() {
        return phase;
    }

    public ArtifactId artifactId() {
        return artifactId;
    }
}
