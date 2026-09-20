package com.sstlfsj.fibra.engine;

import java.util.Map;
import java.util.Objects;

public record RetirementBatchSnapshot(String batchId, String sourceAttemptId,
                                      RetirementPhase phase,
                                      long targetRevision,
                                      String compiledFingerprint,
                                      Map<ExecutionUnitKey, ExecutionObservation> observations) {
    public RetirementBatchSnapshot {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("batchId must not be blank");
        }
        if (sourceAttemptId == null || sourceAttemptId.isBlank()) {
            throw new IllegalArgumentException("sourceAttemptId must not be blank");
        }
        Objects.requireNonNull(phase, "phase");
        if (targetRevision < 1) {
            throw new IllegalArgumentException("targetRevision must be positive");
        }
        if (compiledFingerprint == null || compiledFingerprint.isBlank()) {
            throw new IllegalArgumentException("compiledFingerprint must not be blank");
        }
        observations = Map.copyOf(observations);
    }
}
