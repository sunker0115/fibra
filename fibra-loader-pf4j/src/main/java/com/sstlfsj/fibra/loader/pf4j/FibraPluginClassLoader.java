package com.sstlfsj.fibra.loader.pf4j;

import org.pf4j.ClassLoadingStrategy;
import org.pf4j.PluginClassLoader;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginManager;

final class FibraPluginClassLoader extends PluginClassLoader {
    private static final String[] SHARED_PACKAGE_PREFIXES = {
        "com.sstlfsj.fibra.",
        "org.reactivestreams.",
        "reactor.",
        "org.slf4j."
    };

    FibraPluginClassLoader(PluginManager pluginManager, PluginDescriptor descriptor,
                           ClassLoader parent) {
        super(pluginManager, descriptor, parent, ClassLoadingStrategy.PDA);
    }

    @Override
    protected boolean shouldDelegateToParent(String className) {
        if (super.shouldDelegateToParent(className)) {
            return true;
        }
        for (var prefix : SHARED_PACKAGE_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
