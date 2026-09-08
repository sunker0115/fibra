package com.sstlfsj.fibra.config;

import java.util.List;
import java.util.Objects;

public record DesiredCompilation(DesiredSourceSnapshot snapshot, DesiredGraph graph,
                                 List<ConfigDiagnostic> diagnostics) {
    public DesiredCompilation {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(graph, "graph");
        diagnostics = List.copyOf(diagnostics);
    }
}
