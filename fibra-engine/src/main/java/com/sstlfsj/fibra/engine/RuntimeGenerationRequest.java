package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.RuntimeId;

import java.util.List;
import java.util.Objects;

public record RuntimeGenerationRequest(RuntimeId runtimeId, List<ArtifactRecord> artifacts) {
    public RuntimeGenerationRequest {
        Objects.requireNonNull(runtimeId, "runtimeId");
        artifacts = List.copyOf(artifacts);
    }
}
