package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.config.DesiredInputGraph;

import java.util.List;
import java.util.Objects;

public final class ApplyDeployment implements EngineCommand {
    private final String expectedRevision;
    private final String expectedDesiredRevision;
    private final List<DeploymentArtifact> artifacts;
    private final DesiredInputGraph graph;

    private ApplyDeployment(Builder builder) {
        expectedRevision = builder.expectedRevision;
        if (builder.expectedDesiredRevision == null
            || builder.expectedDesiredRevision.isBlank()) {
            throw new IllegalArgumentException("expectedDesiredRevision must not be blank");
        }
        expectedDesiredRevision = builder.expectedDesiredRevision;
        artifacts = List.copyOf(builder.artifacts);
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException("deployment artifacts must not be empty");
        }
        graph = Objects.requireNonNull(builder.graph, "graph");
    }

    public static Builder builder(DesiredInputGraph graph) { return new Builder(graph); }
    @Override public String expectedRevision() { return expectedRevision; }
    public String expectedDesiredRevision() { return expectedDesiredRevision; }
    public List<DeploymentArtifact> artifacts() { return artifacts; }
    public DesiredInputGraph graph() { return graph; }

    public static final class Builder {
        private String expectedRevision;
        private String expectedDesiredRevision;
        private List<DeploymentArtifact> artifacts = List.of();
        private final DesiredInputGraph graph;

        private Builder(DesiredInputGraph graph) { this.graph = graph; }
        public Builder expectedRevision(String value) { expectedRevision = value; return this; }
        public Builder expectedDesiredRevision(String value) {
            expectedDesiredRevision = value; return this;
        }
        public Builder artifacts(List<DeploymentArtifact> value) {
            artifacts = Objects.requireNonNull(value, "artifacts"); return this;
        }
        public ApplyDeployment build() { return new ApplyDeployment(this); }
    }
}
