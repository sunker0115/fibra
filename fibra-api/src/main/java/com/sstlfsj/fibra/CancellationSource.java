package com.sstlfsj.fibra;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicBoolean;

/** Owner-side controller for one cooperative {@link CancellationToken}. */
public final class CancellationSource {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Sinks.Empty<Void> signal = Sinks.empty();
    private final CancellationToken token = new CancellationToken() {
        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public Mono<Void> cancelled() {
            return signal.asMono();
        }
    };

    public CancellationToken token() {
        return token;
    }

    /** Returns true only for the request that first transitions this source to cancelled. */
    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return false;
        }
        signal.tryEmitEmpty();
        return true;
    }
}
