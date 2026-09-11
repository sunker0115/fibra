package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.engine.PluginInstanceSnapshot;

import java.util.Map;

public record RegistrySnapshot(String viewRevision,
                               Map<ArtifactId, ArtifactRecord> artifacts,
                               Map<String, DesiredInputEntry> desired,
                               Map<String, PluginInstanceSnapshot> observed) {
    public RegistrySnapshot {
        artifacts = Map.copyOf(artifacts);
        desired = Map.copyOf(desired);
        observed = Map.copyOf(observed);
    }
}
