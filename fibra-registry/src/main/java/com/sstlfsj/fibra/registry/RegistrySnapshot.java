package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.engine.CurrentAttemptSnapshot;
import com.sstlfsj.fibra.engine.DeploymentTarget;
import com.sstlfsj.fibra.engine.EngineDiagnostics;
import com.sstlfsj.fibra.engine.EngineSnapshot;
import com.sstlfsj.fibra.engine.ExecutionObservation;
import com.sstlfsj.fibra.engine.PluginSelection;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** 只投影 Engine 事实，不维护另一份 package gate、desired 或 attempt 状态。 */
public record RegistrySnapshot(String viewRevision, EngineSnapshot engine,
                               EngineDiagnostics engineDiagnostics,
                               List<PluginAuditDeliveryFailure> auditFailures) {
    public RegistrySnapshot {
        Objects.requireNonNull(viewRevision, "viewRevision");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(engineDiagnostics, "engineDiagnostics");
        auditFailures = List.copyOf(auditFailures);
    }

    public Optional<DeploymentTarget> target() { return engine.target(); }
    public Optional<CurrentAttemptSnapshot> current() { return engine.current(); }
    public Map<PluginId, PluginSelection> selections() {
        return target().map(DeploymentTarget::selections).orElse(Map.of());
    }
    public DesiredInputGraph desiredGraph() {
        return target().map(DeploymentTarget::desiredGraph).orElseGet(() -> new DesiredInputGraph(List.of()));
    }
    public Map<String, ExecutionObservation> observed() {
        return engine.current().map(CurrentAttemptSnapshot::observations).orElse(Map.of())
            .entrySet().stream().collect(Collectors.toUnmodifiableMap(
            entry -> entry.getKey().value(), Map.Entry::getValue));
    }

    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private String viewRevision;
        private EngineSnapshot engine;
        private EngineDiagnostics engineDiagnostics;
        private List<PluginAuditDeliveryFailure> auditFailures = List.of();
        private Builder() { }
        public Builder viewRevision(String value) { viewRevision = value; return this; }
        public Builder engine(EngineSnapshot value) { engine = value; return this; }
        public Builder engineDiagnostics(EngineDiagnostics value) { engineDiagnostics = value; return this; }
        public Builder auditFailures(List<PluginAuditDeliveryFailure> value) { auditFailures = value; return this; }
        public RegistrySnapshot build() { return new RegistrySnapshot(viewRevision, engine, engineDiagnostics, auditFailures); }
    }
}
