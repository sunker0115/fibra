package com.sstlfsj.fibra.engine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record FibraEngineStatus(FibraEngineState state,
                                Optional<String> desiredRevision,
                                Optional<String> appliedRevision,
                                List<FibraEngineFailure> failures) {
    public FibraEngineStatus {
        Objects.requireNonNull(state, "state");
        desiredRevision = Objects.requireNonNull(desiredRevision, "desiredRevision");
        appliedRevision = Objects.requireNonNull(appliedRevision, "appliedRevision");
        failures = List.copyOf(failures);
    }
}
