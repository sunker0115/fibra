package com.sstlfsj.fibra.loader.pf4j;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class ActivePluginCatalog implements FibraPluginCatalog {
    private final FibraPluginLoader loader;
    private final List<FibraArtifactDescriptor> artifacts;
    private final Map<String, String> versions;
    private final Set<String> executableIds;

    ActivePluginCatalog(FibraPluginLoader loader, List<FibraArtifactDescriptor> artifacts,
                        Set<String> executableIds) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifacts = List.copyOf(artifacts);
        this.versions = this.artifacts.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
            FibraArtifactDescriptor::id, FibraArtifactDescriptor::version));
        this.executableIds = Set.copyOf(executableIds);
    }

    @Override
    public List<FibraArtifactDescriptor> artifacts() {
        return artifacts;
    }

    @Override
    public Optional<Class<?>> configType(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        var version = versions.get(pluginId);
        if (version == null) {
            throw new IllegalArgumentException("unknown plugin " + pluginId);
        }
        if (!Objects.equals(version, loader.currentPluginVersion(pluginId))) {
            throw new IllegalStateException("plugin catalog is no longer current");
        }
        return executableIds.contains(pluginId)
            ? Optional.of(loader.configType(pluginId)) : Optional.empty();
    }
}
