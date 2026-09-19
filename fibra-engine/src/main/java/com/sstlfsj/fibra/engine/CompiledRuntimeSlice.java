package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.RuntimeId;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class CompiledRuntimeSlice {
    private final RuntimeId runtimeId;
    private final RuntimePlan plan;
    private final List<ExecutionUnitKey> dependencyFirst;
    private final List<ExecutionUnitKey> reverseDependency;

    private CompiledRuntimeSlice(RuntimeId runtimeId, RuntimePlan plan,
                                 List<ExecutionUnitKey> dependencyFirst) {
        this.runtimeId = Objects.requireNonNull(runtimeId, "runtimeId");
        this.plan = Objects.requireNonNull(plan, "plan");
        if (!runtimeId.equals(plan.runtimeId())) {
            throw new IllegalArgumentException(
                "compiled runtime slice identity mismatch");
        }
        this.dependencyFirst = List.copyOf(Objects.requireNonNull(
            dependencyFirst, "dependencyFirst"));
        if (!new LinkedHashSet<>(this.dependencyFirst)
            .equals(plan.units().keySet())
            || this.dependencyFirst.size() != plan.units().size()) {
            throw new IllegalArgumentException(
                "runtime dependency order must cover every unit exactly once");
        }
        reverseDependency = this.dependencyFirst.reversed();
    }

    public static CompiledRuntimeSlice of(RuntimePlan plan,
                                          List<ExecutionUnitKey> dependencyFirst) {
        Objects.requireNonNull(plan, "plan");
        return new CompiledRuntimeSlice(plan.runtimeId(), plan, dependencyFirst);
    }

    public RuntimeId runtimeId() { return runtimeId; }
    public RuntimePlan plan() { return plan; }
    public List<ExecutionUnitKey> dependencyFirst() { return dependencyFirst; }
    public List<ExecutionUnitKey> reverseDependency() { return reverseDependency; }
    public Set<ExecutionUnitKey> unitKeys() { return plan.units().keySet(); }
}
