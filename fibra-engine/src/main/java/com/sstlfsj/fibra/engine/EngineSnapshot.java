package com.sstlfsj.fibra.engine;

import java.util.Optional;

public record EngineSnapshot(EngineState state, String hostInstanceId,
                             DurableTargetState durableState,
                             TargetConvergence targetConvergence,
                             Optional<DeploymentTarget> target,
                             Optional<CandidateAttemptSnapshot> candidate,
                             Optional<CurrentAttemptSnapshot> current,
                             Optional<RetirementBatchSnapshot> retirementBatch) {
    public EngineSnapshot {
        java.util.Objects.requireNonNull(state, "state");
        if (hostInstanceId == null || hostInstanceId.isBlank()) {
            throw new IllegalArgumentException("hostInstanceId must not be blank");
        }
        java.util.Objects.requireNonNull(durableState, "durableState");
        java.util.Objects.requireNonNull(targetConvergence, "targetConvergence");
        target = java.util.Objects.requireNonNull(target, "target");
        candidate = java.util.Objects.requireNonNull(candidate, "candidate");
        current = java.util.Objects.requireNonNull(current, "current");
        retirementBatch = java.util.Objects.requireNonNull(retirementBatch,
            "retirementBatch");
    }
}
