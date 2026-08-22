package com.sstlfsj.fibra;

@FunctionalInterface
public interface PluginFactory<C, P> {
    P create(Context context, C config);
}
