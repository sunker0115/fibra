package com.sstlfsj.fibra.config;

import java.util.Optional;

@FunctionalInterface
public interface PluginDefinitionResolver {
    Optional<PluginContract> resolve(String definitionName);
}
