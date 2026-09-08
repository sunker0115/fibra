package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.config.DesiredEntry;
import com.sstlfsj.fibra.engine.PluginInstanceSnapshot;

public record RegistryPluginState(String instanceId, DesiredEntry desired,
                                  PluginInstanceSnapshot observed) {
}
