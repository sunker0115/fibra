package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.RuntimeId;

import java.util.Map;

public record RuntimeGenerationSnapshot(RuntimeId runtimeId, String revision,
                                        Map<ArtifactId, ArtifactRecord> artifacts,
                                        java.util.Set<String> definitions) {
    public RuntimeGenerationSnapshot {
        artifacts = Map.copyOf(artifacts);
        definitions = java.util.Set.copyOf(definitions);
    }
}
