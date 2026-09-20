package com.sstlfsj.fibra.engine;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public record FailureFact(String reason, FailureSubject subject,
                          FailureStage stage,
                          Optional<EngineOperationStage> operationStage,
                          OptionalLong targetRevision, String message) {
    public FailureFact {
        reason = required(reason, "reason");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(stage, "stage");
        operationStage = Objects.requireNonNull(operationStage,
            "operationStage");
        targetRevision = Objects.requireNonNull(targetRevision,
            "targetRevision");
        if (targetRevision.isPresent() && targetRevision.getAsLong() < 1) {
            throw new IllegalArgumentException(
                "targetRevision must be positive when present");
        }
        message = required(message, "message");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
