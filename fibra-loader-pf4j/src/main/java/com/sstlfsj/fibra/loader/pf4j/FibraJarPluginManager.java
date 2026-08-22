package com.sstlfsj.fibra.loader.pf4j;

import org.pf4j.JarPluginManager;
import org.pf4j.JarPluginRepository;
import org.pf4j.Plugin;
import org.pf4j.PluginFactory;
import org.pf4j.PluginLoader;
import org.pf4j.PluginRepository;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class FibraJarPluginManager extends JarPluginManager {
    FibraJarPluginManager(Path pluginsRoot) {
        super(pluginsRoot);
    }

    @Override
    protected PluginLoader createPluginLoader() {
        return new FibraJarPluginLoader(this);
    }

    @Override
    protected PluginRepository createPluginRepository() {
        return new JarPluginRepository(getPluginsRoots());
    }

    @Override
    protected PluginFactory createPluginFactory() {
        return wrapper -> new Plugin();
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
            for (int index = pluginIds.size() - 1; index >= 0; index--) {
                try {
                    unloadPlugin(pluginIds.get(index), false, false);
                } catch (RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            try {
                resolvePlugins();
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
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
}
