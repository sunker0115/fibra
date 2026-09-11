package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.engine.PluginInstanceSnapshot;

public record RegistryPluginState(String instanceId, DesiredInputEntry desired,
                                  PluginInstanceSnapshot observed) {
}
