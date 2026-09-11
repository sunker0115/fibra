package com.sstlfsj.fibra;

import reactor.core.publisher.Mono;

/** Read-only cooperative cancellation observed by plugins and services. */
public interface CancellationToken {
    boolean isCancelled();

    /** Completes once cancellation is requested; never completes for {@link #never()}. */
    Mono<Void> cancelled();

    static CancellationToken never() {
        return NeverCancellationToken.INSTANCE;
    }
}

final class NeverCancellationToken implements CancellationToken {
    static final CancellationToken INSTANCE = new NeverCancellationToken();
    private final Mono<Void> signal = Mono.never();

    private NeverCancellationToken() {
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public Mono<Void> cancelled() {
        return signal;
    }
}
