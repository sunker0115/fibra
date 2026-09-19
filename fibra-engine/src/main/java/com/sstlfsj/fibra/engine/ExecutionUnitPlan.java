package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ExecutionTarget;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.RuntimeId;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class ExecutionUnitPlan {
    private final ExecutionUnitKey key;
    private final RuntimeId runtimeId;
    private final ExecutionTarget executionTarget;
    private final ArtifactId artifactId;
    private final PluginId pluginId;
    private final FacetId facetId;
    private final String packageRevision;
    private final List<ExecutionUnitKey> dependencies;

    private ExecutionUnitPlan(Builder builder) {
        key = Objects.requireNonNull(builder.key, "key");
        runtimeId = Objects.requireNonNull(builder.runtimeId, "runtimeId");
        executionTarget = Objects.requireNonNull(builder.executionTarget,
            "executionTarget");
        artifactId = Objects.requireNonNull(builder.artifactId, "artifactId");
        pluginId = Objects.requireNonNull(builder.pluginId, "pluginId");
        facetId = Objects.requireNonNull(builder.facetId, "facetId");
        packageRevision = Objects.requireNonNull(builder.packageRevision,
            "packageRevision");
        if (!packageRevision.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                "package revision must be a lowercase SHA-256 digest");
        }
        var unique = new LinkedHashSet<>(Objects.requireNonNull(
            builder.dependencies, "dependencies"));
        if (unique.size() != builder.dependencies.size()) {
            throw new IllegalArgumentException("duplicate execution unit dependency");
        }
        if (unique.contains(key)) {
            throw new IllegalArgumentException("execution unit cannot depend on itself");
        }
        dependencies = List.copyOf(unique);
    }

    public static Builder builder(ExecutionUnitKey key, RuntimeId runtimeId,
                                  ExecutionTarget executionTarget) {
        return new Builder(key, runtimeId, executionTarget);
    }

    public ExecutionUnitKey key() { return key; }
    public RuntimeId runtimeId() { return runtimeId; }
    public ExecutionTarget executionTarget() { return executionTarget; }
    public ArtifactId artifactId() { return artifactId; }
    public PluginId pluginId() { return pluginId; }
    public FacetId facetId() { return facetId; }
    public String packageRevision() { return packageRevision; }
    public List<ExecutionUnitKey> dependencies() { return dependencies; }

    public static final class Builder {
        private final ExecutionUnitKey key;
        private final RuntimeId runtimeId;
        private final ExecutionTarget executionTarget;
        private ArtifactId artifactId;
        private PluginId pluginId;
        private FacetId facetId;
        private String packageRevision;
        private List<ExecutionUnitKey> dependencies = List.of();

        private Builder(ExecutionUnitKey key, RuntimeId runtimeId,
                        ExecutionTarget executionTarget) {
            this.key = Objects.requireNonNull(key, "key");
            this.runtimeId = Objects.requireNonNull(runtimeId, "runtimeId");
            this.executionTarget = Objects.requireNonNull(executionTarget,
                "executionTarget");
        }

        public Builder artifactIdentity(String value) {
            artifactId = new ArtifactId(value);
            return this;
        }

        public Builder artifactId(ArtifactId value) {
            artifactId = Objects.requireNonNull(value, "artifactId");
            return this;
        }

        public Builder provenance(String plugin, String facet,
                                  String revision) {
            pluginId = new PluginId(plugin);
            facetId = new FacetId(facet);
            packageRevision = revision;
            return this;
        }

        public Builder dependencies(List<ExecutionUnitKey> value) {
            dependencies = List.copyOf(Objects.requireNonNull(value,
                "dependencies"));
            return this;
        }

        public ExecutionUnitPlan build() {
            return new ExecutionUnitPlan(this);
        }
    }
}
