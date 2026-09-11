package com.sstlfsj.fibra;

import reactor.core.publisher.Mono;

import java.util.Objects;

public interface Scope extends AutoCloseable {
    String name();

    Context context();

    Scope openChild(String name);

    /** 判断另一个 Scope 是否属于同一 RuntimeDomain。 */
    default boolean sharesDomainWith(Scope other) {
        return this == Objects.requireNonNull(other, "other");
    }

    boolean isClosed();

    Mono<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().block();
    }
}
