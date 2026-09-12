package com.sstlfsj.fibra.runtime.java;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;

final class PluginClassLoader extends URLClassLoader {
    static {
        registerAsParallelCapable();
    }

    private final List<String> parentPackages;
    private volatile List<PluginClassLoader> dependencies = List.of();

    PluginClassLoader(List<URL> jars, ClassLoader parent, List<String> parentPackages) {
        super(jars.toArray(URL[]::new), parent);
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
                try {
                    loaded = getParent().loadClass(name);
                } catch (ClassNotFoundException ignored) {
                    // Parent-first is a preference, not an export whitelist.
                }
            }
            if (loaded == null) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException ignored) {
                    for (var dependency : dependencies) {
                        try {
                            loaded = dependency.loadClass(name);
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

    @Override
    public URL getResource(String name) {
        var resource = findResource(name);
        if (resource != null) {
            return resource;
        }
        for (var dependency : dependencies) {
            resource = dependency.getResource(name);
            if (resource != null) {
                return resource;
            }
        }
        return getParent().getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        var resources = new LinkedHashSet<URL>();
        resources.addAll(Collections.list(findResources(name)));
        for (var dependency : dependencies) {
            resources.addAll(Collections.list(dependency.getResources(name)));
        }
        resources.addAll(Collections.list(getParent().getResources(name)));
        return Collections.enumeration(resources);
    }

    private boolean parentFirst(String name) {
        return parentPackages.stream().anyMatch(name::startsWith);
    }
}
