package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 一个 runtime 的定义及制品归属，不包含宿主内建定义。 */
public record RuntimeCatalog(PluginCatalog plugins, Map<String, ArtifactId> artifactByDefinition) {
    public RuntimeCatalog {
        Objects.requireNonNull(plugins, "plugins");
        artifactByDefinition = Map.copyOf(artifactByDefinition);
        var names = plugins.entries().stream().map(entry -> entry.definition().name()).collect(Collectors.toSet());
        if (!names.equals(artifactByDefinition.keySet())) {
            throw new IllegalArgumentException("every runtime definition must have exactly one artifact owner");
        }
    }

    public static RuntimeCatalog empty() { return new RuntimeCatalog(PluginCatalog.empty(), Map.of()); }
}
