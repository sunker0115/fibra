package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.DesiredGraph;
import com.sstlfsj.fibra.config.DesiredSourceSnapshot;

import java.util.Map;
import java.util.Objects;

public record EngineSnapshot(String revision, EngineState state,
                             DesiredSourceSnapshot desiredSource,
                             DesiredGraph desiredGraph,
                             Map<String, PluginInstanceSnapshot> instances,
                             Map<ArtifactId, ArtifactRecord> artifacts,
                             Map<RuntimeId, RuntimeGenerationSnapshot> runtimes,
                             String failure) {
    public EngineSnapshot {
        if (revision == null || revision.isBlank()) {
            throw new IllegalArgumentException("revision must not be blank");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(desiredSource, "desiredSource");
        Objects.requireNonNull(desiredGraph, "desiredGraph");
        instances = Map.copyOf(instances);
        artifacts = Map.copyOf(artifacts);
        runtimes = Map.copyOf(runtimes);
    }
}
