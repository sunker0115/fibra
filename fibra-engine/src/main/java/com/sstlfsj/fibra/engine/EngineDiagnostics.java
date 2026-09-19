package com.sstlfsj.fibra.engine;

import java.util.List;
import java.util.Optional;

public record EngineDiagnostics(Optional<EngineOperationSnapshot> operation,
                                boolean mutationGateOpen,
                                boolean contributionAdmissionOpen, List<String> cleanupFailures,
                                Optional<HostTerminationRequest> terminationRequest,
                                Optional<FailureFact> failure) {
    public EngineDiagnostics {
        operation = java.util.Objects.requireNonNull(operation, "operation");
        cleanupFailures = List.copyOf(cleanupFailures);
        terminationRequest = java.util.Objects.requireNonNull(terminationRequest,
            "terminationRequest");
        failure = java.util.Objects.requireNonNull(failure, "failure");
    }
}
