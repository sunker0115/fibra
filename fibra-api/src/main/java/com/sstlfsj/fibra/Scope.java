package com.sstlfsj.fibra;

import reactor.core.publisher.Mono;

public interface Scope extends AutoCloseable {
    String name();

    Context context();

    Scope openChild(String name);

    boolean isClosed();

    Mono<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().block();
    }
}
