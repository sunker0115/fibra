package com.sstlfsj.fibra;

import reactor.core.publisher.Mono;

public interface Scope extends ScopeView, AutoCloseable {
    Mono<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().block();
    }
}
