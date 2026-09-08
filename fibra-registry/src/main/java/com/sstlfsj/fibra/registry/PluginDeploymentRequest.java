package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.config.DesiredGraph;

import java.util.List;
import java.util.Objects;

public record PluginDeploymentRequest(List<PluginInstallRequest> artifacts,
                                      DesiredGraph graph) {
    public PluginDeploymentRequest {
        artifacts = List.copyOf(artifacts);
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException("deployment artifacts must not be empty");
        }
        Objects.requireNonNull(graph, "graph");
    }
}
