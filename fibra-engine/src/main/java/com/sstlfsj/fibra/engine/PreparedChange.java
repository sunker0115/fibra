package com.sstlfsj.fibra.engine;

import reactor.core.publisher.Mono;

public interface PreparedChange {
    String name();

    Mono<Void> commit();

    Mono<Void> rollback();

    Mono<Void> retire();
}
