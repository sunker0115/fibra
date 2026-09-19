package com.sstlfsj.fibra.engine;

import java.util.Map;
import java.util.Objects;

public record CurrentAttemptSnapshot(String attemptId, CurrentPhase phase,
                                     long targetRevision,
                                     String compiledFingerprint,
                                     Map<ExecutionUnitKey, ExecutionObservation> observations) {
    public CurrentAttemptSnapshot {
        if (attemptId == null || attemptId.isBlank()) {
            throw new IllegalArgumentException("attemptId must not be blank");
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
