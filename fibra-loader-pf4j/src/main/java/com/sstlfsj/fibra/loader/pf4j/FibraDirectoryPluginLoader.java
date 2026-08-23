package com.sstlfsj.fibra.loader.pf4j;

import org.pf4j.BasePluginLoader;
import org.pf4j.DefaultPluginClasspath;
import org.pf4j.PluginClassLoader;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginManager;
import org.pf4j.PluginRuntimeException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

final class FibraDirectoryPluginLoader extends BasePluginLoader {
    FibraDirectoryPluginLoader(PluginManager pluginManager) {
        super(pluginManager, new DefaultPluginClasspath());
    }

    @Override
    public boolean isApplicable(Path pluginPath) {
        return Files.isDirectory(pluginPath);
    }

    @Override
    protected PluginClassLoader createPluginClassLoader(Path pluginPath,
                                                         PluginDescriptor descriptor) {
        return new FibraPluginClassLoader(pluginManager, descriptor,
            FibraDirectoryPluginLoader.class.getClassLoader());
    }

    @Override
    protected void loadClasses(Path pluginPath, PluginClassLoader classLoader) {
        // 标准包只有 lib/*.jar，不能启用 PF4J 开发态 classes/ 旁路。
    }

    @Override
    protected void loadJars(Path pluginPath, PluginClassLoader classLoader) {
        var lib = pluginPath.resolve(DefaultPluginClasspath.LIB_DIR);
        try (var files = Files.list(lib)) {
            files.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .forEach(path -> classLoader.addFile(path.toFile()));
        } catch (IOException exception) {
            throw new PluginRuntimeException(exception, "Cannot load plugin classpath '{}'", lib);
        }
    }
}
