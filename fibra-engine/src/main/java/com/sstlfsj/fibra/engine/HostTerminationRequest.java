package com.sstlfsj.fibra.engine;

import java.util.Objects;
import java.util.OptionalLong;

public record HostTerminationRequest(String hostInstanceId, String reason,
                                     String phase,
                                     OptionalLong targetRevision) {
    public HostTerminationRequest {
        hostInstanceId = required(hostInstanceId, "host instance id");
        reason = required(reason, "termination reason");
        phase = required(phase, "termination phase");
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
