package com.sstlfsj.fibra.internal;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.Objects;
import java.util.concurrent.Callable;

final class LifecycleDispatcher {
    private final Scheduler scheduler;
    private volatile Thread lifecycleThread;

    public LifecycleDispatcher(String name) {
        this.scheduler = Schedulers.newSingle(name);
        Mono.fromRunnable(() -> lifecycleThread = Thread.currentThread())
            .subscribeOn(scheduler)
            .block();
    }

    public <T> T call(Callable<T> action) {
        Objects.requireNonNull(action, "action");
        if (Thread.currentThread() == lifecycleThread) {
            return LifecycleCallback.capture(action).get();
        }
        return Mono.fromCallable(() -> LifecycleCallback.capture(action))
            .subscribeOn(scheduler)
            .block().get();
    }

    public <T> Mono<T> mono(Callable<T> action) {
        Objects.requireNonNull(action, "action");
        return Mono.defer(() -> {
            if (Thread.currentThread() == lifecycleThread) {
                return LifecycleCallback.capture(action).mono();
            }
            return Mono.fromCallable(() -> LifecycleCallback.capture(action))
                .subscribeOn(scheduler).flatMap(LifecycleCallback.Outcome::mono);
        });
    }

    public Mono<Void> run(Runnable action) {
        Objects.requireNonNull(action, "action");
        return mono(() -> {
            action.run();
            return null;
        }).then();
    }

    public Mono<Void> tick() {
        return Mono.<Void>fromRunnable(() -> {
        }).subscribeOn(scheduler);
    }

    public Scheduler scheduler() {
        return scheduler;
    }

    public void shutdown() {
        scheduler.dispose();
    }

}
