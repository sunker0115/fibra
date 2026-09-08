package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Scope;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.List;

public final class DefaultFibraRuntime {
    private final LifecycleDispatcher lifecycle = new LifecycleDispatcher("fibra-lifecycle");
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong();
    private final Sinks.One<Void> closedSignal = Sinks.one();
    private final List<PluginInstanceImpl<?>> instances = new ArrayList<>();
    private final ServiceRegistry services = new ServiceRegistry(this);
    private final EventBus events = new EventBus(this);
    private final DefaultLoggerService logging = new DefaultLoggerService();
    private final DefaultScope rootScope = new DefaultScope(this, null, "root");

    public Scope rootScope() {
        return rootScope;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public Mono<Void> closeAsync() {
        if (closeRequested.compareAndSet(false, true)) {
            rootScope.closeFromRuntime()
                .subscribe(ignored -> { }, this::finishCloseWithError, this::finishClose);
        }
        return closedSignal.asMono();
    }

    LifecycleDispatcher lifecycle() {
        return lifecycle;
    }

    ServiceRegistry services() {
        return services;
    }

    EventBus events() {
        return events;
    }

    DefaultLoggerService logging() {
        return logging;
    }

    long nextSequence() {
        return sequence.incrementAndGet();
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

    private void finishClose() {
        closed.set(true);
        closedSignal.tryEmitEmpty();
        lifecycle.shutdown();
    }

    private void finishCloseWithError(Throwable error) {
        closed.set(true);
        closedSignal.tryEmitError(error);
        lifecycle.shutdown();
    }
}
