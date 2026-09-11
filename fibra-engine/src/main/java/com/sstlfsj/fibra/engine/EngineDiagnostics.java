package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.RuntimeId;
import java.util.Set;
import java.util.Map;

public record EngineDiagnostics(String targetRevision,
    String contextRevision,
    ChangePhase phase,
    Set<String> affectedInstances,
    Map<RuntimeId, RuntimeResourceSnapshot> resources,
    boolean targetSatisfied,
    boolean mutationGateOpen,
    String failure) {
    public EngineDiagnostics {
        affectedInstances = Set.copyOf(affectedInstances);
        resources = Map.copyOf(resources);
        java.util.Objects.requireNonNull(phase, "phase");
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String targetRevision;
        private String contextRevision;
        private ChangePhase phase;
        private Set<String> affectedInstances;
        private Map<RuntimeId, RuntimeResourceSnapshot> resources;
        private boolean targetSatisfied;
        private boolean mutationGateOpen;
        private String failure;
        public Builder targetRevision(String value) { targetRevision = value; return this; }
        public Builder contextRevision(String value) { contextRevision = value; return this; }
        public Builder phase(ChangePhase value) { phase = value; return this; }
        public Builder affectedInstances(Set<String> value) { affectedInstances = value; return this; }
        public Builder resources(Map<RuntimeId, RuntimeResourceSnapshot> value) { resources = value; return this; }
        public Builder targetSatisfied(boolean value) { targetSatisfied = value; return this; }
        public Builder mutationGateOpen(boolean value) { mutationGateOpen = value; return this; }
        public Builder failure(String value) { failure = value; return this; }
        public EngineDiagnostics build() { return new EngineDiagnostics(targetRevision, contextRevision, phase, affectedInstances, resources, targetSatisfied, mutationGateOpen, failure); }
    }
}
