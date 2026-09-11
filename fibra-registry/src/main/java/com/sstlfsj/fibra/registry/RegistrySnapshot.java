package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.engine.PluginInstanceSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RegistrySnapshot(String viewRevision,
                               Map<ArtifactId, ArtifactRecord> artifacts,
                               DesiredInputGraph desiredGraph,
                               Map<String, PluginInstanceSnapshot> observed,
                               List<PluginAuditDeliveryFailure> auditFailures) {
    public RegistrySnapshot {
        artifacts = Map.copyOf(artifacts);
        desiredGraph = Objects.requireNonNull(desiredGraph, "desiredGraph");
        observed = Map.copyOf(observed);
        auditFailures = List.copyOf(auditFailures);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String viewRevision;
        private Map<ArtifactId, ArtifactRecord> artifacts;
        private DesiredInputGraph desiredGraph;
        private Map<String, PluginInstanceSnapshot> observed;
        private List<PluginAuditDeliveryFailure> auditFailures = List.of();

        private Builder() { }

        public Builder viewRevision(String value) { viewRevision = value; return this; }
        public Builder artifacts(Map<ArtifactId, ArtifactRecord> value) {
            artifacts = value; return this;
        }
        public Builder desiredGraph(DesiredInputGraph value) {
            desiredGraph = value; return this;
        }
        public Builder observed(Map<String, PluginInstanceSnapshot> value) {
            observed = value; return this;
        }
        public Builder auditFailures(List<PluginAuditDeliveryFailure> value) {
            auditFailures = value; return this;
        }
        public RegistrySnapshot build() {
            return new RegistrySnapshot(viewRevision, artifacts, desiredGraph, observed,
                auditFailures);
        }
    }
}
