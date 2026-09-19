package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.engine.PluginSelection;

import java.util.List;
import java.util.Objects;

/** 完整 target replacement；动态 package 必须已发布到共享 package store。 */
public record PluginDeploymentRequest(List<PluginSelection> selections,
                                      DesiredInputGraph graph,
                                      ConfigContextSnapshot configContext) {
    public PluginDeploymentRequest {
        selections = List.copyOf(selections);
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(configContext, "configContext");
    }
}
