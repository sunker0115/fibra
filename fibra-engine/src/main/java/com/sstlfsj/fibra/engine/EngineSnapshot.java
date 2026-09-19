package com.sstlfsj.fibra.engine;

import java.util.Map;
import java.util.Optional;

public record EngineSnapshot(EngineState state, String hostInstanceId,
                             DurableTargetState durableState, Optional<DeploymentTarget> target,
                             Optional<AttemptSnapshot> candidate, Optional<AttemptSnapshot> current,
                             Map<ExecutionUnitKey, ExecutionObservation> retiring,
                             Map<ExecutionUnitKey, ExecutionObservation> units, String failure) {
    public EngineSnapshot {
        retiring = Map.copyOf(retiring);
        units = Map.copyOf(units);
    }
}
