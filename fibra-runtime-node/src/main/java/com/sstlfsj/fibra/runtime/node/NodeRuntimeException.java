package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.artifact.ArtifactId;

public final class NodeRuntimeException extends RuntimeException {
    private final ArtifactId artifactId;

    NodeRuntimeException(ArtifactId artifactId, String message, Throwable cause) {
        super(message, cause);
        this.artifactId = artifactId;
    }

    public ArtifactId artifactId() {
        return artifactId;
    }
}
