package com.sstlfsj.fibra.plugins.storage;

@FunctionalInterface
public interface ConfigChangeListener {
    void changed(ConfigChange change);
}
