package com.sstlfsj.fibra;

@FunctionalInterface
public interface PluginFactory<C> {
    Plugin<C> create();
}
