package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginInstanceState;

public record PluginInstanceSnapshot(String instanceId, String definitionName,
                                     Object config, PluginInstanceState state,
                                     String failure) {
}
