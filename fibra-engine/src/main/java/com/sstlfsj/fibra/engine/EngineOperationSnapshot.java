package com.sstlfsj.fibra.engine;

import java.util.Objects;
import java.util.Optional;

public record EngineOperationSnapshot(String operationId,
                                      EngineOperationKind kind,
                                      EngineOperationStage stage,
                                      EngineOperationOutcome outcome,
                                      long targetRevision,
                                      TargetSaveState targetSaveState,
                                      Optional<String> attemptId) {
    public EngineOperationSnapshot {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(outcome, "outcome");
        if (targetRevision < 0) {
            throw new IllegalArgumentException("targetRevision must not be negative");
        }
        Objects.requireNonNull(targetSaveState, "targetSaveState");
        attemptId = Objects.requireNonNull(attemptId, "attemptId");
    }
}
