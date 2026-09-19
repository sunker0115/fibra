package com.sstlfsj.fibra.engine;

import java.util.Set;

public record AttemptSnapshot(String attemptId, AttemptRole role, AttemptPhase phase,
                              long targetRevision, String compiledFingerprint,
                              Set<ExecutionUnitKey> unitKeys) {
    public AttemptSnapshot { unitKeys = Set.copyOf(unitKeys); }
}
