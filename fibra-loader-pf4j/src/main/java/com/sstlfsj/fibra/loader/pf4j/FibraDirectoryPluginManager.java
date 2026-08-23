package com.sstlfsj.fibra.loader.pf4j;

import org.pf4j.BasePluginRepository;
import org.pf4j.DefaultPluginManager;
import org.pf4j.Plugin;
import org.pf4j.PluginDescriptorFinder;
import org.pf4j.PluginFactory;
import org.pf4j.PluginLoader;
import org.pf4j.PluginRepository;
import org.pf4j.PropertiesPluginDescriptorFinder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

class FibraDirectoryPluginManager extends DefaultPluginManager {
    private final Path pluginsRoot;

    FibraDirectoryPluginManager(Path pluginsRoot) {
        super(pluginsRoot);
        this.pluginsRoot = pluginsRoot;
    }

    @Override
    protected PluginDescriptorFinder createPluginDescriptorFinder() {
        return new PropertiesPluginDescriptorFinder();
    }

    @Override
    protected PluginLoader createPluginLoader() {
        return new FibraDirectoryPluginLoader(this);
    }

    @Override
    protected PluginRepository createPluginRepository() {
        var repository = new BasePluginRepository(getPluginsRoots(),
            file -> file.isDirectory() && !file.isHidden()
                && !file.getName().startsWith("."));
        repository.setComparator(Comparator.comparing(File::getName));
        return repository;
    }

    @Override
    protected PluginFactory createPluginFactory() {
        return wrapper -> new Plugin();
    }

    List<String> loadPluginsStrict() {
        try (var paths = Files.list(pluginsRoot)) {
            return loadPluginsStrict(paths.filter(Files::isDirectory)
                .filter(path -> !path.getFileName().toString().startsWith("."))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("cannot scan plugin root " + pluginsRoot, exception);
        }
    }

    List<String> loadPluginsStrict(List<Path> pluginPaths) {
        var pluginIds = new ArrayList<String>(pluginPaths.size());
        try {
            for (var pluginPath : pluginPaths) {
                pluginIds.add(loadPluginFromPath(pluginPath).getPluginId());
            }
            resolvePlugins();
            return List.copyOf(pluginIds);
        } catch (RuntimeException exception) {
            rollbackLoads(pluginIds, exception);
            throw exception;
        }
    }

    void unloadPluginsStrict(List<String> pluginIds) {
        for (var pluginId : pluginIds) {
            if (getPlugin(pluginId) != null && !unloadPlugin(pluginId, false, false)) {
                throw new IllegalStateException("failed to unload PF4J plugin " + pluginId);
            }
        }
        resolvePlugins();
    }

    private void rollbackLoads(List<String> pluginIds, RuntimeException failure) {
        for (int index = pluginIds.size() - 1; index >= 0; index--) {
            try {
                unloadPlugin(pluginIds.get(index), false, false);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        try {
            resolvePlugins();
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
