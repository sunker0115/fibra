package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.FibraException;
import com.sstlfsj.fibra.Scope;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class DefaultScope implements Scope, ResourceOwner {
    private enum State { OPEN, CLOSING, CLOSED }

    private final DefaultFibraRuntime runtime;
    private final DefaultScope parent;
    private final String name;
    private final List<DefaultScope> children = new ArrayList<>();
    private final List<PluginInstanceImpl<?>> plugins = new ArrayList<>();
    private final IdentityList<OwnedEffect> resources = new IdentityList<>();
    private final DefaultContext context;
    private final Sinks.One<Void> closed = Sinks.one();
    private volatile State state = State.OPEN;

    DefaultScope(DefaultFibraRuntime runtime, DefaultScope parent, String name) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.parent = parent;
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("scope name must not be blank");
        }
        this.name = name;
        context = DefaultContext.root(this);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Context context() {
        return context;
    }

    @Override
    public Scope openChild(String childName) {
        if (runtime.closeRequested()) {
            throw new FibraException(FibraException.RUNTIME_CLOSED, "runtime is closed");
        }
        return lifecycle().call(() -> {
            assertOpen();
            var child = new DefaultScope(runtime, this, childName);
            children.add(child);
            return child;
        });
    }

    @Override
    public boolean isClosed() {
        return state == State.CLOSED;
    }

    @Override
    public Mono<Void> closeAsync() {
        if (parent == null && !runtime.closeRequested()) {
            return runtime.closeAsync();
        }
        return closeFromRuntime();
    }

    Mono<Void> closeFromRuntime() {
        if (state == State.CLOSED) {
            return closed.asMono();
        }
        lifecycle().call(() -> {
            if (state == State.OPEN) {
                beginClose();
            }
            return null;
        });
        return closed.asMono();
    }

    @Override
    public LifecycleDispatcher lifecycle() {
        return runtime.lifecycle();
    }

    @Override
    public boolean acceptsResources() {
        return state == State.OPEN && (!runtime.closeRequested() || parent == null);
    }

    @Override
    public String ownerName() {
        return "scope:" + name;
    }

    @Override
    public void addResource(OwnedEffect resource) {
        assertOpen();
        resources.add(resource);
    }

    @Override
    public void removeResource(OwnedEffect resource) {
        resources.remove(resource);
    }

    @Override
    public void resourceFailed(Throwable failure) {
        throw new IllegalStateException("scope resources cannot be supervised", failure);
    }

    DefaultFibraRuntime runtime() {
        return runtime;
    }

    void addPlugin(PluginInstanceImpl<?> plugin) {
        assertOpen();
        if (plugins.stream().anyMatch(candidate -> candidate.id().equals(plugin.id()))) {
            throw new FibraException(FibraException.PLUGIN_DUPLICATE,
                "plugin instance \"" + plugin.id() + "\" already exists in scope \"" + name + "\"");
        }
        plugins.add(plugin);
    }

    void removePlugin(PluginInstanceImpl<?> plugin) {
        plugins.remove(plugin);
    }

    List<PluginInstanceImpl<?>> pluginsSnapshot() {
        return List.copyOf(plugins);
    }

    private void beginClose() {
        state = State.CLOSING;
        runtime.services().ownerStateChanged(this);
        var childSnapshot = Cleanup.reversed(List.copyOf(children));
        var pluginSnapshot = Cleanup.reversed(List.copyOf(plugins));
        var resourceSnapshot = Cleanup.reversed(resources.snapshot());

        Flux.fromIterable(childSnapshot)
            .concatMap(DefaultScope::closeAsync, 1)
            .thenMany(Flux.fromIterable(pluginSnapshot)
                .concatMap(PluginInstanceImpl::dispose, 1))
            .then(Cleanup.allSettled(resourceSnapshot))
            .publishOn(lifecycle().scheduler())
            .subscribe(ignored -> { }, this::finishWithError, this::finish);
    }

    private void finish() {
        state = State.CLOSED;
        children.clear();
        plugins.clear();
        if (parent != null) {
            parent.children.remove(this);
        }
        closed.tryEmitEmpty();
    }

    private void finishWithError(Throwable error) {
        state = State.CLOSED;
        children.clear();
        plugins.clear();
        if (parent != null) {
            parent.children.remove(this);
        }
        closed.tryEmitError(error);
    }

    private void assertOpen() {
        if (!acceptsResources()) {
            throw new FibraException(FibraException.SCOPE_CLOSED,
                "scope \"" + name + "\" is closed");
        }
    }
}
