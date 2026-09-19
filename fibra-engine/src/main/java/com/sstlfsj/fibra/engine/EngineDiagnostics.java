package com.sstlfsj.fibra.engine;

import java.util.List;
import java.util.Optional;

public record EngineDiagnostics(AttemptPhase phase, TargetSaveState targetSaveState,
                                boolean targetSatisfied, boolean mutationGateOpen,
                                boolean contributionAdmissionOpen, List<String> cleanupFailures,
                                Optional<HostTerminationRequest> terminationRequest, String failure) {
    public EngineDiagnostics { cleanupFailures = List.copyOf(cleanupFailures); }
}
