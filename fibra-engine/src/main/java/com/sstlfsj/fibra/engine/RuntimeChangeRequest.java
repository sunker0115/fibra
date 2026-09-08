package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.RuntimeId;

import java.util.List;
import java.util.Objects;

public record RuntimeChangeRequest(RuntimeId runtimeId, List<ArtifactRecord> artifacts,
                                   RuntimeGenerationSnapshot current) {
    public RuntimeChangeRequest {
        Objects.requireNonNull(runtimeId, "runtimeId");
        artifacts = List.copyOf(artifacts);
    }
}
