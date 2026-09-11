package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.DesiredSourceSnapshot;

import java.util.Map;
import java.util.Objects;

public record EngineSnapshot(EngineState state, DesiredSourceSnapshot desiredSource,
                             DesiredInputGraph desiredGraph,
                             Map<String, PluginInstanceSnapshot> instances,
                             Map<ArtifactId, ArtifactRecord> artifacts,
                             Map<RuntimeId, RuntimeGenerationSnapshot> runtimes,
                             String failure) {
    public EngineSnapshot {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(desiredSource, "desiredSource");
        Objects.requireNonNull(desiredGraph, "desiredGraph");
        instances = Map.copyOf(instances);
        artifacts = Map.copyOf(artifacts);
        runtimes = Map.copyOf(runtimes);
    }
}
