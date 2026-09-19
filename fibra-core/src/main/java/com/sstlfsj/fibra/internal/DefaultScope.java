package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.FibraException;
import com.sstlfsj.fibra.Scope;
import com.sstlfsj.fibra.ScopeView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class DefaultScope implements Scope, ResourceOwner {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultScope.class);

    private enum State { OPEN, CLOSING, CLOSED }

    private final DefaultRuntimeDomain domain;
    private final DefaultScope parent;
    private final String name;
    private final long identity;
    private final ScopeView view;
    private final List<DefaultScope> children = new ArrayList<>();
    private final List<PluginInstanceImpl<?>> plugins = new ArrayList<>();
    private final IdentityList<OwnedEffect> resources = new IdentityList<>();
    private final DefaultContext context;
    private final Sinks.One<Void> closed = Sinks.one();
    private volatile State state = State.OPEN;
    private ResourceDrain drain;
    private boolean closeStarted;

    DefaultScope(DefaultRuntimeDomain domain, DefaultScope parent, String name) {
        this.domain = Objects.requireNonNull(domain, "domain");
        this.parent = parent;
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("scope name must not be blank");
        }
        this.name = name;
        identity = domain.runtime().nextSequence();
        view = new View(this);
        context = DefaultContext.root(this);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public long identity() { return identity; }

    @Override
    public Context context() {
        return context;
    }

    @Override
    public Scope openChild(String childName) {
        if (runtime().closeRequested() || domain.closeRequested()) {
            throw new FibraException(FibraException.RUNTIME_CLOSED, "runtime is closed");
        }
        return lifecycle().call(() -> {
            assertOpen();
            var child = new DefaultScope(domain, this, childName);
            children.add(child);
            return child;
        });
    }

    @Override
    public boolean sharesDomainWith(ScopeView other) {
        Objects.requireNonNull(other, "other");
        if (other instanceof DefaultScope candidate) {
            return domain == candidate.domain;
        }
        return other instanceof View candidate && domain == candidate.scope.domain;
    }

    @Override
    public boolean isClosed() {
        return state == State.CLOSED;
    }

    @Override
    public Mono<Void> closeAsync() {
        if (parent == null) {
            return domain.closeAsync();
        }
        return closeFromDomain();
    }

    Mono<Void> closeFromDomain() {
        if (state == State.CLOSED) {
            return closed.asMono();
        }
        lifecycle().call(() -> {
            if (!closeStarted) {
                beginClose();
            }
            return null;
        });
        return closed.asMono();
    }

    @Override
    public LifecycleDispatcher lifecycle() {
        return runtime().lifecycle();
    }

    @Override
    public boolean acceptsResources() {
        return state == State.OPEN && !runtime().closeRequested()
            && !domain.closeRequested();
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
        return domain.runtime();
    }

    ScopeView view() {
        return view;
    }

    @Override
    public DefaultRuntimeDomain domain() {
        return domain;
    }

    @Override
    public DefaultScope scope() {
        return this;
    }

    boolean isWithin(DefaultScope ancestor) {
        for (var current = this; current != null; current = current.parent) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
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
        closeStarted = true;
        var consumers = domain.services().consumerCleanup(this);
        if (drain == null) {
            var barrier = new ResourceDrain(lifecycle());
            freeze(barrier);
            barrier.start();
        }
        domain.services().ownerStateChanged(this);
        var childSnapshot = Cleanup.reversed(List.copyOf(children));
        var pluginSnapshot = Cleanup.reversed(List.copyOf(plugins));
        var resourceSnapshot = Cleanup.reversed(resources.snapshot());

        drain.completion().then(consumers).thenMany(Flux.fromIterable(childSnapshot)
            .concatMap(DefaultScope::closeAsync, 1)
            .thenMany(Flux.fromIterable(pluginSnapshot)
                .concatMap(PluginInstanceImpl::dispose, 1)))
            .then(Cleanup.allSettled(resourceSnapshot))
            .publishOn(lifecycle().scheduler())
            .subscribe(ignored -> { }, this::finishWithError, this::finish);
    }

    void freeze(ResourceDrain barrier) {
        if (drain != null) {
            if (drain != barrier) {
                barrier.await(drain.completion());
            }
            return;
        }
        drain = barrier;
        state = State.CLOSING;
        List.copyOf(children).forEach(child -> child.freeze(barrier));
        List.copyOf(plugins).forEach(plugin -> plugin.freeze(barrier));
        resources.snapshot().forEach(barrier::include);
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
        if (error instanceof ResourceDrain.Failure) {
            closed.tryEmitError(error);
            return;
        }
        LOGGER.warn("scope \"{}\" cleanup failed during close", name, error);
        finish();
    }

    private void assertOpen() {
        if (!acceptsResources()) {
            throw new FibraException(FibraException.SCOPE_CLOSED,
                "scope \"" + name + "\" is closed");
        }
    }

    private static final class View implements ScopeView {
        private final DefaultScope scope;

        private View(DefaultScope scope) {
            this.scope = scope;
        }

        @Override
        public String name() {
            return scope.name();
        }

        @Override
        public Context context() {
            return scope.context();
        }

        @Override
        public Scope openChild(String name) {
            return scope.openChild(name);
        }

        @Override
        public boolean sharesDomainWith(ScopeView other) {
            return scope.sharesDomainWith(other);
        }

        @Override
        public boolean isClosed() {
            return scope.isClosed();
        }
    }
}
