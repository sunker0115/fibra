package com.sstlfsj.fibra;

import org.reactivestreams.Publisher;

@FunctionalInterface
public interface PluginInitializer<P> {
    Publisher<?> initialize(P plugin);
}
