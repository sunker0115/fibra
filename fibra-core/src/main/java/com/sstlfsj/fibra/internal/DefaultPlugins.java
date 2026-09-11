package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstance;
import com.sstlfsj.fibra.Plugins;

import java.util.List;
import java.util.Optional;

final class DefaultPlugins implements Plugins {
    private final DefaultContext context;

    DefaultPlugins(DefaultContext context) {
        this.context = context;
    }

    @Override
    public Optional<PluginInstance<?>> current() {
        return Optional.ofNullable(context.owner().pluginInstance());
    }

    @Override
    public <C> PluginInstance<C> mount(String instanceId, PluginDefinition.Prepared<C> prepared) {
        return context.runtime().lifecycle().call(() -> {
            var instance = new PluginInstanceImpl<>(context, instanceId, prepared);
            context.scopeImpl().addPlugin(instance);
            instance.initialize();
            if (context.owner() instanceof PluginInstanceImpl<?>) {
                try {
                    context.effects().add(instance);
                } catch (RuntimeException | Error failure) {
                    instance.dispose().subscribe();
                    throw failure;
                }
            }
            return instance;
        });
    }

    @Override
    public Optional<PluginInstance<?>> find(String instanceId) {
        return context.runtime().lifecycle().call(() -> {
            for (var instance : context.scopeImpl().pluginsSnapshot()) {
                if (instance.id().equals(instanceId)) {
                    return Optional.<PluginInstance<?>>of(instance);
                }
            }
            return Optional.empty();
        });
    }

    @Override
    public List<PluginInstance<?>> instances() {
        return context.runtime().lifecycle().call(() -> List.copyOf(
            context.scopeImpl().pluginsSnapshot()));
    }
}
