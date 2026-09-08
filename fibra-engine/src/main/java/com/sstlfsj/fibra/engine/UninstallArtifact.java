package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;

import java.util.Objects;

public record UninstallArtifact(String expectedRevision,
                                ArtifactId artifactId) implements EngineCommand {
    public UninstallArtifact {
        Objects.requireNonNull(artifactId, "artifactId");
    }
}
