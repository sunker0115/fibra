package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Fibra;
import com.sstlfsj.fibra.PluginRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class DefaultPluginRegistry implements PluginRegistry {
    private final PluginStore delegate;
    private final LifecycleDispatcher lifecycle;

    DefaultPluginRegistry(PluginStore delegate, LifecycleDispatcher lifecycle) {
        this.delegate = delegate;
        this.lifecycle = lifecycle;
    }

    public int size() {
        return lifecycle.call(delegate::size);
    }

    public boolean has(Object pluginIdentity) {
        Objects.requireNonNull(pluginIdentity, "pluginIdentity");
        return lifecycle.call(() -> delegate.has(pluginIdentity));
    }

    @Override
    public List<Object> keys() {
        return lifecycle.call(delegate::keys);
    }

    @Override
    public List<List<Fibra>> values() {
        return lifecycle.call(delegate::values);
    }

    @Override
    public Map<Object, List<Fibra>> entries() {
        return lifecycle.call(() -> {
            var snapshot = new IdentityHashMap<Object, List<Fibra>>();
            delegate.entries().forEach(snapshot::put);
            return snapshot;
        });
    }

    @Override
    public List<Fibra> fibras() {
        return lifecycle.call(delegate::fibras);
    }

    public List<Fibra> fibras(Object pluginIdentity) {
        Objects.requireNonNull(pluginIdentity, "pluginIdentity");
        return lifecycle.call(() -> delegate.fibras(pluginIdentity));
    }

    public Mono<Void> remove(Object pluginIdentity) {
        Objects.requireNonNull(pluginIdentity, "pluginIdentity");
        return lifecycle.mono(() -> delegate.fibras(pluginIdentity))
            .flatMapMany(Flux::fromIterable)
            .flatMap(Fibra::dispose)
            .then();
    }
}
