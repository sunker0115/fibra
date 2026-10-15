package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.engine.deployment.DeploymentTarget;
import com.sstlfsj.fibra.engine.observation.EngineState;
import com.sstlfsj.fibra.engine.observation.DurableTargetState;
import com.sstlfsj.fibra.engine.observation.TargetConvergence;
import com.sstlfsj.fibra.engine.observation.ExecutionObservation;
import com.sstlfsj.fibra.engine.deployment.PluginSelection;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 只投影 Engine 事实，不维护另一份 package gate、desired 或 attempt 状态。 */
public record RegistrySnapshot(String viewRevision, EngineState state,
                               DurableTargetState durableState, TargetConvergence targetConvergence,
                               boolean mutationGateOpen, boolean contributionAdmissionOpen,
                               Optional<DeploymentTarget> target, Map<String, ExecutionObservation> observed,
                               List<PluginAuditDeliveryFailure> auditFailures) {
    public RegistrySnapshot {
        Objects.requireNonNull(viewRevision, "viewRevision");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(durableState, "durableState");
        Objects.requireNonNull(targetConvergence, "targetConvergence");
        Objects.requireNonNull(target, "target");
        observed = Map.copyOf(observed);
        auditFailures = List.copyOf(auditFailures);
    }

    public Map<PluginId, PluginSelection> selections() {
        return target().map(DeploymentTarget::selections).orElse(Map.of());
    }
    public DesiredInputGraph desiredGraph() {
        return target().map(DeploymentTarget::desiredGraph).orElseGet(() -> new DesiredInputGraph(List.of()));
    }

    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private String viewRevision;
        private EngineState state;
        private DurableTargetState durableState;
        private TargetConvergence targetConvergence;
        private boolean mutationGateOpen;
        private boolean contributionAdmissionOpen;
        private Optional<DeploymentTarget> target = Optional.empty();
        private Map<String, ExecutionObservation> observed = Map.of();
        private List<PluginAuditDeliveryFailure> auditFailures = List.of();
        private Builder() { }
        public Builder viewRevision(String value) { viewRevision = value; return this; }
        public Builder state(EngineState value) { state = value; return this; }
        public Builder durableState(DurableTargetState value) { durableState = value; return this; }
        public Builder targetConvergence(TargetConvergence value) { targetConvergence = value; return this; }
        public Builder mutationGateOpen(boolean value) { mutationGateOpen = value; return this; }
        public Builder contributionAdmissionOpen(boolean value) { contributionAdmissionOpen = value; return this; }
        public Builder target(Optional<DeploymentTarget> value) { target = value; return this; }
        public Builder observed(Map<String, ExecutionObservation> value) { observed = value; return this; }
        public Builder auditFailures(List<PluginAuditDeliveryFailure> value) { auditFailures = value; return this; }
        public RegistrySnapshot build() {
            return new RegistrySnapshot(viewRevision, state, durableState, targetConvergence,
                mutationGateOpen, contributionAdmissionOpen, target, observed, auditFailures);
        }
    }
}
