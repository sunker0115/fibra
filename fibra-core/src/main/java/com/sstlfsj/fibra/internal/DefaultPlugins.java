package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.FibraException;
import com.sstlfsj.fibra.ManagedPluginControl;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstance;
import com.sstlfsj.fibra.PluginInstanceState;
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
    public void requestDisable() {
        context.runtime().lifecycle().call(() -> {
            var instance = context.owner().pluginInstance();
            if (instance == null) {
                throw new FibraException(FibraException.PLUGIN_DISABLE_UNAVAILABLE,
                    "plugin disable requires a plugin-owned context");
            }
            var state = instance.stateUnsafe();
            if (state == PluginInstanceState.STOPPING || state == PluginInstanceState.FAILED
                || state == PluginInstanceState.DISPOSED) {
                return null;
            }
            if (state != PluginInstanceState.STARTING && state != PluginInstanceState.ACTIVE) {
                throw new FibraException(FibraException.PLUGIN_DISABLE_UNAVAILABLE,
                    "plugin instance \"" + instance.id() + "\" is not active");
            }
            if (!instance.acceptsResources()) {
                return null;
            }
            var control = context.services().find(ManagedPluginControl.KEY).orElseThrow(() ->
                new FibraException(FibraException.PLUGIN_DISABLE_UNAVAILABLE,
                    "managed plugin control is unavailable"));
            control.requestDisable(instance);
            return null;
        });
    }

    @Override
    public <C> PluginInstance<C> mount(String instanceId, PluginDefinition.Prepared<C> prepared) {
        return context.runtime().lifecycle().call(() -> {
            if (!context.owner().acceptsResources()) {
                throw new com.sstlfsj.fibra.FibraException(
                    com.sstlfsj.fibra.FibraException.EFFECT_INACTIVE,
                    "plugin owner does not accept resources");
            }
            var instance = new PluginInstanceImpl<>(context, instanceId, prepared);
            context.scopeImpl().addPlugin(instance);
            if (context.owner() instanceof PluginInstanceImpl<?>) {
                try {
                    context.effects().add(instance);
                } catch (RuntimeException | Error failure) {
                    context.scopeImpl().removePlugin(instance);
                    throw failure;
                }
            }
            instance.initialize();
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
