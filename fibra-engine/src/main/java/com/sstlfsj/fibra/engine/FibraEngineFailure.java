package com.sstlfsj.fibra.engine;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record FibraEngineFailure(FibraEngineFailureStage stage,
                                 Optional<String> revision,
                                 String message,
                                 Instant occurredAt) {
    public FibraEngineFailure {
        Objects.requireNonNull(stage, "stage");
        revision = Objects.requireNonNull(revision, "revision");
        message = Objects.requireNonNull(message, "message");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
