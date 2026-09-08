package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.RuntimeId;

import java.util.Map;
import java.util.Objects;

public record RuntimeArtifactInspection(RuntimeId runtimeId, ArtifactId artifactId,
                                        Map<String, Object> metadata) {
    public RuntimeArtifactInspection {
        Objects.requireNonNull(runtimeId, "runtimeId");
        Objects.requireNonNull(artifactId, "artifactId");
        metadata = Map.copyOf(metadata);
    }
}
