package com.sstlfsj.fibra.loader.pf4j;

import org.pf4j.JarPluginLoader;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginManager;

import java.nio.file.Path;

final class FibraJarPluginLoader extends JarPluginLoader {
    FibraJarPluginLoader(PluginManager pluginManager) {
        super(pluginManager);
    }

    @Override
    public ClassLoader loadPlugin(Path pluginPath, PluginDescriptor pluginDescriptor) {
        var classLoader = new FibraPluginClassLoader(pluginManager, pluginDescriptor,
            FibraJarPluginLoader.class.getClassLoader());
        classLoader.addFile(pluginPath.toFile());
        return classLoader;
    }
}
