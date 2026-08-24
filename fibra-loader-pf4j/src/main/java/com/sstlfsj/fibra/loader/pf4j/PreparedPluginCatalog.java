package com.sstlfsj.fibra.loader.pf4j;

import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class PreparedPluginCatalog implements FibraPluginCatalog, AutoCloseable {
    private final FibraDirectoryPluginManager manager;
    private final List<FibraArtifactDescriptor> artifacts;
    private final Map<String, InspectedPluginPackage> packages;
    private boolean closed;

    private PreparedPluginCatalog(FibraDirectoryPluginManager manager,
                                  List<FibraArtifactDescriptor> artifacts,
                                  Map<String, InspectedPluginPackage> packages) {
        this.manager = manager;
        this.artifacts = artifacts;
        this.packages = packages;
    }

    static PreparedPluginCatalog open(List<InspectedPluginPackage> inspected) {
        Objects.requireNonNull(inspected, "inspected");
        var sorted = inspected.stream().sorted(java.util.Comparator.comparing(
            plugin -> plugin.descriptor().getPluginId())).toList();
        var manager = new FibraDirectoryPluginManager(Path.of("."));
        try {
            manager.loadPluginsStrict(sorted.stream()
                .map(InspectedPluginPackage::packageRoot).toList());
            manager.startPlugins();
            var packages = new LinkedHashMap<String, InspectedPluginPackage>();
            var artifacts = new java.util.ArrayList<FibraArtifactDescriptor>();
            for (var plugin : sorted) {
                var id = plugin.descriptor().getPluginId();
                packages.put(id, plugin);
                artifacts.add(new FibraArtifactDescriptor(id,
                    plugin.descriptor().getVersion(), plugin.digest()));
            }
            return new PreparedPluginCatalog(manager, List.copyOf(artifacts),
                java.util.Collections.unmodifiableMap(packages));
        } catch (RuntimeException failure) {
            try {
                manager.unloadPlugins();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Override
    public List<FibraArtifactDescriptor> artifacts() {
        requireOpen();
        return artifacts;
    }

    @Override
    public Optional<Class<?>> configType(String pluginId) {
        requireOpen();
        Objects.requireNonNull(pluginId, "pluginId");
        var plugin = packages.get(pluginId);
        if (plugin == null) {
            throw new IllegalArgumentException("unknown plugin " + pluginId);
        }
        if (plugin.entrypointClassNames().isEmpty()) {
            return Optional.empty();
        }
        var className = plugin.entrypointClassNames().getFirst();
        try {
            var type = Class.forName(className, true,
                manager.getPluginClassLoader(pluginId));
            var entrypoint = (FibraPluginEntrypoint<?>) type.getConstructor().newInstance();
            return Optional.of(Objects.requireNonNull(entrypoint.configType(),
                "entrypoint configType returned null"));
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException
                 | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("cannot read candidate config type for "
                + pluginId, exception);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        manager.unloadPlugins();
        closed = true;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("plugin catalog is closed");
        }
    }
}
