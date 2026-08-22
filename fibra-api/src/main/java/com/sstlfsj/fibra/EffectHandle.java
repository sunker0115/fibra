package com.sstlfsj.fibra;

import reactor.core.publisher.Mono;

public interface EffectHandle extends Disposable {
    Mono<EffectHandle> ready();

    boolean isDisposed();

    EffectMetadata metadata();
}
