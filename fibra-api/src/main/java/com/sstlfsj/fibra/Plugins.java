package com.sstlfsj.fibra;

import java.util.List;
import java.util.Optional;

public interface Plugins {
    Optional<PluginInstance<?>> current();

    <C> PluginInstance<C> mount(String instanceId, PluginDefinition<C> definition, C config);

    Optional<PluginInstance<?>> find(String instanceId);

    List<PluginInstance<?>> instances();
}
