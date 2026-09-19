package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** 一次完整 selections、raw desired 和 configContext 的原子 replacement。 */
public final class ApplyDeployment implements EngineCommand {
    private final long expectedRevision;
    private final List<PluginSelection> selections;
    private final DesiredInputGraph graph;
    private final ConfigContextSnapshot configContext;

    private ApplyDeployment(Builder builder) {
        if (builder.expectedRevision < 0) throw new IllegalArgumentException("expected revision must not be negative");
        expectedRevision = builder.expectedRevision;
        selections = List.copyOf(builder.selections);
        graph = Objects.requireNonNull(builder.graph, "graph");
        configContext = Objects.requireNonNull(builder.configContext, "configContext");
    }
    public static Builder builder(DesiredInputGraph graph) { return new Builder(graph); }
    public long expectedRevision() { return expectedRevision; }
    public List<PluginSelection> selections() { return selections; }
    public DesiredInputGraph graph() { return graph; }
    public ConfigContextSnapshot configContext() { return configContext; }

    public static final class Builder {
        private long expectedRevision;
        private List<PluginSelection> selections = List.of();
        private final DesiredInputGraph graph;
        private ConfigContextSnapshot configContext;
        private Builder(DesiredInputGraph graph) { this.graph = graph; }
        public Builder expectedRevision(long value) { expectedRevision = value; return this; }
        public Builder selections(Collection<PluginSelection> value) { selections = List.copyOf(value); return this; }
        public Builder configContext(ConfigContextSnapshot value) { configContext = value; return this; }
        public ApplyDeployment build() { return new ApplyDeployment(this); }
    }
}
