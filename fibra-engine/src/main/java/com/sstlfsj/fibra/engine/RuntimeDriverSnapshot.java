package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.RuntimeId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record RuntimeDriverSnapshot(
    RuntimeId runtimeId,
    Map<ExecutionUnitKey, ExecutionObservation> units) {
    public RuntimeDriverSnapshot {
        Objects.requireNonNull(runtimeId, "runtimeId");
        var copy = new LinkedHashMap<ExecutionUnitKey, ExecutionObservation>();
        Objects.requireNonNull(units, "units").forEach((key, value) ->
            copy.put(Objects.requireNonNull(key, "unit key"),
                Objects.requireNonNull(value, "unit observation")));
        units = Collections.unmodifiableMap(copy);
    }
}
