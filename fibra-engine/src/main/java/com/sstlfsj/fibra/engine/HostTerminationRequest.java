package com.sstlfsj.fibra.engine;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public record HostTerminationRequest(String hostInstanceId, String reason,
                                     FailureSubject subject,
                                     Optional<EngineOperationStage> operationStage,
                                     OptionalLong targetRevision) {
    public HostTerminationRequest {
        hostInstanceId = required(hostInstanceId, "host instance id");
        reason = required(reason, "termination reason");
        Objects.requireNonNull(subject, "subject");
        operationStage = Objects.requireNonNull(operationStage,
            "operationStage");
        Objects.requireNonNull(targetRevision, "targetRevision");
        if (targetRevision.isPresent() && targetRevision.getAsLong() < 1) {
            throw new IllegalArgumentException(
                "target revision must be positive when present");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
