package com.sstlfsj.fibra.engine;

import reactor.core.publisher.Mono;

public interface PreparedRuntimeGeneration extends PreparedChange {
    RuntimeGenerationSnapshot snapshot();

    PluginCatalog catalog();

    @Override
    default String name() {
        return "runtime:" + snapshot().runtimeId().value();
    }

    @Override
    Mono<Void> commit();

    @Override
    Mono<Void> rollback();

    @Override
    Mono<Void> retire();
}
