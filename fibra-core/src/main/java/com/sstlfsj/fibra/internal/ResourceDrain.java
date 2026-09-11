package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.DrainingDisposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;

/** 沿现有资源所有权列表传播的单次清理前排空屏障，不保存第二份所有权关系。 */
final class ResourceDrain {
    private final LifecycleDispatcher lifecycle;
    private final Sinks.One<Void> completion = Sinks.one();
    private final List<Mono<Void>> waiting = new ArrayList<>();
    private int pending = 1;
    private boolean started;
    private int freezing;
    private Failure failure;

    ResourceDrain(LifecycleDispatcher lifecycle) {
        this.lifecycle = lifecycle;
    }

    void include(Disposable resource) {
        freezing++;
        try {
            if (resource instanceof OwnedResource owned) {
                owned.freeze(this);
            } else if (resource instanceof PluginInstanceImpl<?> plugin) {
                plugin.freeze(this);
            } else if (resource instanceof DrainingDisposable draining) {
                await(Mono.defer(draining::drain));
            }
        } finally {
            freezing--;
            if (started && freezing == 0) {
                startWaiting();
            }
        }
    }

    void await(Mono<Void> drain) {
        pending++;
        if (!started || freezing != 0) {
            waiting.add(drain);
            return;
        }
        subscribe(drain);
    }

    void start() {
        started = true;
        startWaiting();
        release();
    }

    private void startWaiting() {
        var initial = List.copyOf(waiting);
        waiting.clear();
        initial.forEach(this::subscribe);
    }

    private void subscribe(Mono<Void> drain) {
        drain.subscribe(ignored -> { }, error -> lifecycle.call(() -> {
            if (failure == null) {
                failure = new Failure(error);
            } else if (error != failure) {
                failure.addSuppressed(error);
            }
            release();
            return null;
        }), () -> lifecycle.call(() -> {
            release();
            return null;
        }));
    }

    private void release() {
        if (--pending != 0) {
            return;
        }
        if (failure == null) {
            completion.tryEmitEmpty();
        } else {
            completion.tryEmitError(failure);
        }
    }

    Mono<Void> completion() {
        return completion.asMono();
    }

    static final class Failure extends IllegalStateException {
        Failure(Throwable cause) {
            super("owned resource drain failed; resources retained", cause);
        }
    }
}
