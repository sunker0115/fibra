package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Scope;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Flux;

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
    private final DefaultLoggerService logging = new DefaultLoggerService();
    private final List<DefaultRuntimeDomain> domains = new ArrayList<>();
    private final DefaultRuntimeDomain rootDomain;

    public DefaultFibraRuntime() {
        rootDomain = new DefaultRuntimeDomain(this, "root", true);
        domains.add(rootDomain);
    }

    public Scope rootScope() {
        return rootDomain.rootScope();
    }

    public DefaultRuntimeDomain openDomain(String name) {
        if (closeRequested()) {
            throw new IllegalStateException("runtime is closed");
        }
        return lifecycle.call(() -> {
            if (closeRequested()) {
                throw new IllegalStateException("runtime is closed");
            }
            var domain = new DefaultRuntimeDomain(this, name, false);
            domains.add(domain);
            return domain;
        });
    }

    public boolean isClosed() {
        return closed.get();
    }

    public Mono<Void> closeAsync() {
        if (closeRequested.compareAndSet(false, true)) {
            var snapshot = new ArrayList<>(domains);
            java.util.Collections.reverse(snapshot);
            Flux.fromIterable(snapshot)
                .concatMap(DefaultRuntimeDomain::closeFromRuntime, 1)
                .then()
                .subscribe(ignored -> { }, this::finishCloseWithError, this::finishClose);
        }
        return closedSignal.asMono();
    }

    LifecycleDispatcher lifecycle() {
        return lifecycle;
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

    void removeDomain(DefaultRuntimeDomain domain) {
        domains.remove(domain);
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
