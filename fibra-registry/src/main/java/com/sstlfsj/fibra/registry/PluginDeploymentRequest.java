package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.config.DesiredInputGraph;

import java.util.List;
import java.util.Objects;

/**
 * Describes a complete deployment target rather than an incremental artifact update.
 * An empty artifact list clears every dynamic artifact from the target deployment.
 */
public record PluginDeploymentRequest(List<PluginInstallRequest> artifacts,
                                      DesiredInputGraph graph) {
    public PluginDeploymentRequest {
        artifacts = List.copyOf(artifacts);
        Objects.requireNonNull(graph, "graph");
    }
}
