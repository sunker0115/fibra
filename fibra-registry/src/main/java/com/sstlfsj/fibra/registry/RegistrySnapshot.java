package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.config.DesiredEntry;
import com.sstlfsj.fibra.engine.PluginInstanceSnapshot;

import java.util.Map;

public record RegistrySnapshot(String viewRevision,
                               Map<ArtifactId, ArtifactRecord> artifacts,
                               Map<String, DesiredEntry> desired,
                               Map<String, PluginInstanceSnapshot> observed) {
    public RegistrySnapshot {
        artifacts = Map.copyOf(artifacts);
        desired = Map.copyOf(desired);
        observed = Map.copyOf(observed);
    }
}
