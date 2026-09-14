package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.RuntimeId;
import java.util.List;
import java.util.Set;
import java.util.Map;

public record EngineDiagnostics(String targetRevision,
    String contextRevision,
    ChangePhase phase,
    ChangePhase failedPhase,
    TargetSaveState targetSaveState,
    Set<String> affectedInstances,
    Map<RuntimeId, RuntimeResourceSnapshot> resources,
    List<String> cleanupFailures,
    boolean targetSatisfied,
    boolean mutationGateOpen,
    String failure) {
    public EngineDiagnostics {
        affectedInstances = Set.copyOf(affectedInstances);
        resources = Map.copyOf(resources);
        cleanupFailures = List.copyOf(cleanupFailures);
        java.util.Objects.requireNonNull(phase, "phase");
        java.util.Objects.requireNonNull(targetSaveState, "targetSaveState");
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String targetRevision;
        private String contextRevision;
        private ChangePhase phase;
        private ChangePhase failedPhase;
        private TargetSaveState targetSaveState = TargetSaveState.NOT_APPLICABLE;
        private Set<String> affectedInstances;
        private Map<RuntimeId, RuntimeResourceSnapshot> resources;
        private List<String> cleanupFailures = List.of();
        private boolean targetSatisfied;
        private boolean mutationGateOpen;
        private String failure;
        public Builder targetRevision(String value) { targetRevision = value; return this; }
        public Builder contextRevision(String value) { contextRevision = value; return this; }
        public Builder phase(ChangePhase value) { phase = value; return this; }
        public Builder failedPhase(ChangePhase value) { failedPhase = value; return this; }
        public Builder targetSaveState(TargetSaveState value) { targetSaveState = value; return this; }
        public Builder affectedInstances(Set<String> value) { affectedInstances = value; return this; }
        public Builder resources(Map<RuntimeId, RuntimeResourceSnapshot> value) { resources = value; return this; }
        public Builder cleanupFailures(List<String> value) { cleanupFailures = value; return this; }
        public Builder targetSatisfied(boolean value) { targetSatisfied = value; return this; }
        public Builder mutationGateOpen(boolean value) { mutationGateOpen = value; return this; }
        public Builder failure(String value) { failure = value; return this; }
        public EngineDiagnostics build() { return new EngineDiagnostics(targetRevision, contextRevision,
            phase, failedPhase, targetSaveState, affectedInstances, resources, cleanupFailures,
            targetSatisfied, mutationGateOpen, failure); }
    }
}
