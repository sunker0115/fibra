package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.value.LiteralValue;

public record PluginInstanceSnapshot(String instanceId, String definitionName,
                                     LiteralValue config, PluginInstanceState state,
                                     String failure) {
}
