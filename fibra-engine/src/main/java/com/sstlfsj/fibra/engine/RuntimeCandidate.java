package com.sstlfsj.fibra.engine;

import reactor.core.publisher.Mono;

public interface RuntimeCandidate {
    Mono<Void> prepareAsync();

    RuntimePlan preparedPlan();

    PreparedRuntimeGeneration seal(CompiledRuntimeSlice slice);

    Mono<Void> closeAsync();
}
