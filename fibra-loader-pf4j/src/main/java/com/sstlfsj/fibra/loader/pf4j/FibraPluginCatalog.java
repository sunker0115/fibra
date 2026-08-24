package com.sstlfsj.fibra.loader.pf4j;

import java.util.List;
import java.util.Optional;

/** 当前或候选插件图的只读类型目录。 */
public interface FibraPluginCatalog {
    List<FibraArtifactDescriptor> artifacts();

    Optional<Class<?>> configType(String pluginId);
}
