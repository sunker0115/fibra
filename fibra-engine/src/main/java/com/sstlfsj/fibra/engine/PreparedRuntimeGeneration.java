package com.sstlfsj.fibra.engine;

import reactor.core.publisher.Mono;

import java.util.Map;

public interface PreparedRuntimeGeneration {
    Map<ExecutionUnitKey, RuntimeUnitGeneration> units();

    Mono<Void> abortAsync();

    Mono<Void> retireAsync();
}
