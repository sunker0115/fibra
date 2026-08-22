package com.sstlfsj.fibra;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface Disposable {
    Mono<Void> dispose();
}
