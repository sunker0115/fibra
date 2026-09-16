package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ExecutionTarget;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.PluginFacet;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.RuntimeId;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 按逻辑插件、facet 和具体 execution 分层的 observed 事实。 */
public final class ExecutionObservation {
    private final PluginId pluginId;
    private final FacetId facetId;
    private final RuntimeId runtimeId;
    private final ExecutionTarget executionTarget;
    private final State aggregateState;
    private final List<Detail> executions;

    private ExecutionObservation(PluginId pluginId, FacetId facetId,
                                 RuntimeId runtimeId, ExecutionTarget executionTarget,
                                 List<Detail> executions) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.facetId = Objects.requireNonNull(facetId, "facetId");
        this.runtimeId = Objects.requireNonNull(runtimeId, "runtimeId");
        this.executionTarget = Objects.requireNonNull(executionTarget,
            "executionTarget");
        this.executions = executions.stream()
            .map(value -> Objects.requireNonNull(value, "execution"))
            .sorted(Comparator.comparing(Detail::executionId))
            .toList();
        if (this.executions.stream().map(Detail::executionId).distinct().count()
            != this.executions.size()) {
            throw new IllegalArgumentException("duplicate execution observation");
        }
        aggregateState = aggregate(this.executions);
    }

    public static ExecutionObservation of(PluginId pluginId, FacetId facetId,
                                          RuntimeId runtimeId,
                                          ExecutionTarget executionTarget,
                                          List<Detail> executions) {
        return new ExecutionObservation(pluginId, facetId, runtimeId,
            executionTarget, Objects.requireNonNull(executions, "executions"));
    }

    /** 按 execution target 与 capability 覆盖关系筛选后聚合。 */
    public static ExecutionObservation matching(PluginId pluginId, PluginFacet facet,
                                                List<ExecutionCandidate> candidates) {
        Objects.requireNonNull(facet, "facet");
        var matching = Objects.requireNonNull(candidates, "candidates").stream()
            .map(value -> Objects.requireNonNull(value, "candidate"))
            .filter(value -> value.executionTarget().equals(facet.executionTarget()))
            .map(ExecutionCandidate::observation)
            .filter(value -> value.capabilities().containsAll(
                facet.requiredCapabilities()))
            .toList();
        return of(pluginId, facet.facetId(), facet.runtimeId(),
            facet.executionTarget(), matching);
    }

    public PluginId pluginId() { return pluginId; }
    public FacetId facetId() { return facetId; }
    public RuntimeId runtimeId() { return runtimeId; }
    public ExecutionTarget executionTarget() { return executionTarget; }
    public State aggregateState() { return aggregateState; }
    public List<Detail> executions() { return executions; }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) return true;
        if (!(candidate instanceof ExecutionObservation other)) return false;
        return pluginId.equals(other.pluginId) && facetId.equals(other.facetId)
            && runtimeId.equals(other.runtimeId)
            && executionTarget.equals(other.executionTarget)
            && aggregateState == other.aggregateState
            && executions.equals(other.executions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pluginId, facetId, runtimeId, executionTarget,
            aggregateState, executions);
    }

    private static State aggregate(List<Detail> executions) {
        if (executions.stream().anyMatch(detail -> detail.state() == State.FAILED)) {
            return State.FAILED;
        }
        if (!executions.isEmpty()
            && executions.stream().allMatch(detail -> detail.state() == State.ACTIVE)) {
            return State.ACTIVE;
        }
        return State.PENDING;
    }

    public enum State { PENDING, ACTIVE, FAILED }

    public static final class Detail {
        private final long targetRevision;
        private final String executionId;
        private final String runtimeInstanceId;
        private final String lifecycleOperationId;
        private final Set<String> capabilities;
        private final State state;
        private final Failure failure;

        private Detail(Builder builder) {
            if (builder.targetRevision < 1) {
                throw new IllegalArgumentException("target revision must be positive");
            }
            targetRevision = builder.targetRevision;
            executionId = required(builder.executionId, "execution id");
            runtimeInstanceId = required(builder.runtimeInstanceId,
                "runtime instance id");
            lifecycleOperationId = required(builder.lifecycleOperationId,
                "lifecycle operation id");
            var frozenCapabilities = new LinkedHashSet<String>();
            for (var capability : Objects.requireNonNull(builder.capabilities,
                "capabilities")) {
                frozenCapabilities.add(required(capability, "capability"));
            }
            capabilities = Set.copyOf(frozenCapabilities);
            state = Objects.requireNonNull(builder.state, "state");
            if ((state == State.FAILED) != (builder.failure != null)) {
                throw new IllegalArgumentException(
                    "only FAILED execution observations carry failure");
            }
            failure = builder.failure;
        }

        public static Builder builder() { return new Builder(); }
        public long targetRevision() { return targetRevision; }
        public String executionId() { return executionId; }
        public String runtimeInstanceId() { return runtimeInstanceId; }
        public String lifecycleOperationId() { return lifecycleOperationId; }
        public Set<String> capabilities() { return capabilities; }
        public State state() { return state; }
        public Failure failure() { return failure; }

        @Override
        public boolean equals(Object candidate) {
            if (this == candidate) return true;
            if (!(candidate instanceof Detail other)) return false;
            return targetRevision == other.targetRevision
                && executionId.equals(other.executionId)
                && runtimeInstanceId.equals(other.runtimeInstanceId)
                && lifecycleOperationId.equals(other.lifecycleOperationId)
                && capabilities.equals(other.capabilities) && state == other.state
                && Objects.equals(failure, other.failure);
        }

        @Override
        public int hashCode() {
            return Objects.hash(targetRevision, executionId, runtimeInstanceId,
                lifecycleOperationId, capabilities, state, failure);
        }

        public static final class Builder {
            private long targetRevision;
            private String executionId;
            private String runtimeInstanceId;
            private String lifecycleOperationId;
            private Set<String> capabilities = Set.of();
            private State state;
            private Failure failure;

            private Builder() { }
            public Builder targetRevision(long value) {
                targetRevision = value; return this;
            }
            public Builder executionId(String value) {
                executionId = value; return this;
            }
            public Builder runtimeInstanceId(String value) {
                runtimeInstanceId = value; return this;
            }
            public Builder lifecycleOperationId(String value) {
                lifecycleOperationId = value; return this;
            }
            public Builder capabilities(Set<String> value) {
                capabilities = value; return this;
            }
            public Builder state(State value) { state = value; return this; }
            public Builder failure(Failure value) { failure = value; return this; }
            public Detail build() { return new Detail(this); }
        }
    }

    public record ExecutionCandidate(ExecutionTarget executionTarget,
                                     Detail observation) {
        public ExecutionCandidate {
            Objects.requireNonNull(executionTarget, "executionTarget");
            Objects.requireNonNull(observation, "observation");
        }
    }

    public record Failure(String code, String message,
                          Map<String, String> diagnostics) {
        public Failure {
            code = required(code, "failure code");
            message = required(message, "failure message");
            diagnostics = Map.copyOf(Objects.requireNonNull(diagnostics,
                "diagnostics"));
            if (diagnostics.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue().isBlank())) {
                throw new IllegalArgumentException(
                    "failure diagnostics must contain non-blank entries");
            }
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
