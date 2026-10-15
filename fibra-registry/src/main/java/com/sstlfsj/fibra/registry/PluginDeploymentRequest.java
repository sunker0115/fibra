package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.engine.deployment.PluginSelection;

import java.util.List;
import java.util.Objects;

/** 完整 target replacement；expectedRevision 为调用者读取的非负版本，动态 package 必须已发布到共享 store。 */
public record PluginDeploymentRequest(long expectedRevision, List<PluginSelection> selections,
                                      DesiredInputGraph graph,
                                      ConfigContextSnapshot configContext) {
    public PluginDeploymentRequest {
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must not be negative");
        selections = List.copyOf(selections);
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(configContext, "configContext");
    }
}
