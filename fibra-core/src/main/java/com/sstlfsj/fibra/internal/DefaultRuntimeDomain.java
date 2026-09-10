package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Scope;
import com.sstlfsj.fibra.runtime.RuntimeDomainSnapshot;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DefaultRuntimeDomain {
    private final DefaultFibraRuntime runtime;
    private final String name;
    private final boolean rootDomain;
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Sinks.One<Void> closedSignal = Sinks.one();
    private final List<PluginInstanceImpl<?>> instances = new ArrayList<>();
    private final ServiceRegistry services = new ServiceRegistry(this);
    private final EventBus events = new EventBus(this);
    private final PropertyRegistry properties = new PropertyRegistry(this);
    private final DefaultScope rootScope;

    DefaultRuntimeDomain(DefaultFibraRuntime runtime, String name, boolean rootDomain) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("runtime domain name must not be blank");
        }
        this.name = name;
        this.rootDomain = rootDomain;
        rootScope = new DefaultScope(this, null, name);
    }

    public String name() {
        return name;
    }

    public Scope rootScope() {
        return rootScope;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public RuntimeDomainSnapshot snapshot() {
        return runtime.lifecycle().call(() -> {
            var observed = instances.stream()
                .map(PluginInstanceImpl::diagnosticSnapshot).toList();
            return new RuntimeDomainSnapshot(name, observed,
                services.diagnosticSnapshot(), events.diagnosticSnapshot());
        });
    }

    static RuntimeDomainSnapshot.OwnerIdentity ownerIdentity(ResourceOwner owner) {
        var name = owner.ownerName();
        var separator = name.indexOf(':');
        return separator < 0
            ? new RuntimeDomainSnapshot.OwnerIdentity("resource", name)
            : new RuntimeDomainSnapshot.OwnerIdentity(
                name.substring(0, separator), name.substring(separator + 1));
    }

    public Mono<Void> closeAsync() {
        if (rootDomain && !runtime.closeRequested()) {
            return runtime.closeAsync();
        }
        return closeFromRuntime();
    }

    Mono<Void> closeFromRuntime() {
        if (closeRequested.compareAndSet(false, true)) {
            rootScope.closeFromDomain()
                .subscribe(ignored -> { }, this::finishWithError, this::finish);
        }
        return closedSignal.asMono();
    }

    DefaultFibraRuntime runtime() {
        return runtime;
    }

    ServiceRegistry services() {
        return services;
    }

    EventBus events() {
        return events;
    }

    PropertyRegistry properties() {
        return properties;
    }

    boolean closeRequested() {
        return closeRequested.get();
    }

    void addInstance(PluginInstanceImpl<?> instance) {
        instances.add(instance);
    }

    void removeInstance(PluginInstanceImpl<?> instance) {
        instances.remove(instance);
    }

    List<PluginInstanceImpl<?>> instancesSnapshot() {
        return List.copyOf(instances);
    }

    private void finish() {
        closed.set(true);
        runtime.removeDomain(this);
        closedSignal.tryEmitEmpty();
    }

    private void finishWithError(Throwable error) {
        closed.set(true);
        runtime.removeDomain(this);
        closedSignal.tryEmitError(error);
    }
}
