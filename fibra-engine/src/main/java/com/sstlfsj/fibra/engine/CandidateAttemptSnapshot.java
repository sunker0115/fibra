package com.sstlfsj.fibra.engine;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record CandidateAttemptSnapshot(String attemptId, CandidatePhase phase,
                                       long targetRevision,
                                       Optional<String> compiledFingerprint,
                                       Set<ExecutionUnitKey> unitKeys) {
    public CandidateAttemptSnapshot {
        if (attemptId == null || attemptId.isBlank()) {
            throw new IllegalArgumentException("attemptId must not be blank");
        }
        Objects.requireNonNull(phase, "phase");
        if (targetRevision < 1) {
            throw new IllegalArgumentException("targetRevision must be positive");
        }
        compiledFingerprint = Objects.requireNonNull(compiledFingerprint,
            "compiledFingerprint");
        unitKeys = Set.copyOf(unitKeys);
    }
}
