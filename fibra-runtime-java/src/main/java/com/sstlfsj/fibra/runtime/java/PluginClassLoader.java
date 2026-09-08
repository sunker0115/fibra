package com.sstlfsj.fibra.runtime.java;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Set;

final class PluginClassLoader extends URLClassLoader {
    static {
        registerAsParallelCapable();
    }

    private final List<String> parentPackages;
    private volatile List<PluginClassLoader> dependencies = List.of();

    PluginClassLoader(URL jar, ClassLoader parent, List<String> parentPackages) {
        super(new URL[] {jar}, parent);
        this.parentPackages = List.copyOf(parentPackages);
    }

    void dependencies(List<PluginClassLoader> values) {
        if (!dependencies.isEmpty()) {
            throw new IllegalStateException("plugin dependencies are already set");
        }
        dependencies = List.copyOf(values);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            var loaded = findLoadedClass(name);
            if (loaded == null && parentFirst(name)) {
                loaded = getParent().loadClass(name);
            }
            if (loaded == null) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException ignored) {
                    for (var dependency : dependencies) {
                        try {
                            loaded = dependency.loadOwnClass(name);
                            break;
                        } catch (ClassNotFoundException absent) {
                            // Try the next declared direct dependency.
                        }
                    }
                }
            }
            if (loaded == null) {
                throw new ClassNotFoundException(name);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private Class<?> loadOwnClass(String name) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            var loaded = findLoadedClass(name);
            return loaded == null ? findClass(name) : loaded;
        }
    }

    private boolean parentFirst(String name) {
        return parentPackages.stream().anyMatch(name::startsWith);
    }
}
