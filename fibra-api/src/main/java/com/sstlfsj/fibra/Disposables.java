package com.sstlfsj.fibra;

import reactor.core.publisher.Mono;

import java.util.Objects;

public final class Disposables {
    private static final Disposable NOOP = Mono::empty;

    private Disposables() {
    }

    public static Disposable noop() {
        return NOOP;
    }

    public static Disposable from(Runnable action) {
        Objects.requireNonNull(action, "action");
        return () -> Mono.fromRunnable(action);
    }
}
