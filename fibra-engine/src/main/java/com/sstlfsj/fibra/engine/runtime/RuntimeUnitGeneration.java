package com.sstlfsj.fibra.engine.runtime;

import com.sstlfsj.fibra.engine.execution.ExecutionUnitPlan;
import com.sstlfsj.fibra.engine.execution.RuntimeUnitFence;
import com.sstlfsj.fibra.engine.observation.ExecutionObservation;

import reactor.core.publisher.Mono;

import java.time.Instant;

public interface RuntimeUnitGeneration {
    ExecutionUnitPlan plan();

    RuntimeUnitFence fence();

    Mono<ExecutionObservation> reconcileAsync(String lifecycleOperationId);

    void closeAdmission();

    Mono<ExecutionObservation> drainAsync(String lifecycleOperationId,
                                          Instant deadline);

    Mono<ExecutionObservation> stopAsync(String lifecycleOperationId,
                                         Instant deadline);

    ExecutionObservation snapshot();
}
