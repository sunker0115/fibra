package com.sstlfsj.fibra.engine.runtime;

import com.sstlfsj.fibra.engine.execution.CompiledRuntimeSlice;
import com.sstlfsj.fibra.engine.execution.RuntimePlan;

import reactor.core.publisher.Mono;

public interface RuntimeCandidate {
    Mono<Void> prepareAsync();

    RuntimePlan preparedPlan();

    PreparedRuntimeGeneration seal(CompiledRuntimeSlice slice);

    Mono<Void> closeAsync();
}
