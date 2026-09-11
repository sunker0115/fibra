package com.sstlfsj.fibra;

import java.util.List;
import java.util.Optional;

public interface Plugins {
    Optional<PluginInstance<?>> current();

    <C> PluginInstance<C> mount(String instanceId, PluginDefinition.Prepared<C> prepared);

    Optional<PluginInstance<?>> find(String instanceId);

    List<PluginInstance<?>> instances();
}
