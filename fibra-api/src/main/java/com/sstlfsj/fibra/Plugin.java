package com.sstlfsj.fibra;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface Plugin<C> {
    Mono<Void> start(Context context, C config);
}
