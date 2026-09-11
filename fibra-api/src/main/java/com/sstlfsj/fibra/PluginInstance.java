package com.sstlfsj.fibra;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;

public interface PluginInstance<C> extends Disposable {
    String id();

    long identity();

    PluginDefinition<C> definition();

    Context context();

    PluginInstanceState state();

    Flux<PluginInstanceState> states();

    C config();

    Optional<Throwable> failure();

    Mono<PluginInstance<C>> settled();

    Mono<PluginInstance<C>> update(C config);

    Mono<PluginInstance<C>> updatePrepared(PluginDefinition.Prepared<C> prepared);
}
