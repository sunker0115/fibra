package com.sstlfsj.fibra.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record DesiredCompilation(DesiredSourceSnapshot snapshot, DesiredInputGraph graph,
                                 List<ConfigDiagnostic> diagnostics,
                                 Map<String, Path> entrySources) {
    public DesiredCompilation {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(graph, "graph");
        diagnostics = List.copyOf(diagnostics);
        entrySources = Map.copyOf(entrySources);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private DesiredSourceSnapshot snapshot;
        private DesiredInputGraph graph;
        private List<ConfigDiagnostic> diagnostics = List.of();
        private Map<String, Path> entrySources = Map.of();

        private Builder() { }

        public Builder snapshot(DesiredSourceSnapshot value) { snapshot = value; return this; }
        public Builder graph(DesiredInputGraph value) { graph = value; return this; }
        public Builder diagnostics(List<ConfigDiagnostic> value) { diagnostics = value; return this; }
        public Builder entrySources(Map<String, Path> value) { entrySources = value; return this; }
        public DesiredCompilation build() {
            return new DesiredCompilation(snapshot, graph, diagnostics, entrySources);
        }
    }
}
