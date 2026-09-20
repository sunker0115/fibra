package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.RuntimeId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 从 REGISTERED 起持有所有 runtime candidates；seal 后聚合所有可原子 promote 的所有权。 */
public final class DeploymentCandidate {
    private final DeploymentTarget target;
    private final Map<RuntimeId, RuntimeCandidate> candidates = new LinkedHashMap<>();
    private final Map<RuntimeId, PreparedRuntimeGeneration> generations = new LinkedHashMap<>();
    private Map<ExecutionUnitKey, RuntimeUnitGeneration> units = Map.of();
    private CompiledDeployment compiled;

    DeploymentCandidate(DeploymentTarget target) { this.target = Objects.requireNonNull(target, "target"); }
    void register(RuntimeId id, RuntimeCandidate value) {
        if (candidates.putIfAbsent(id, Objects.requireNonNull(value, "candidate")) != null) {
            throw new IllegalStateException("duplicate candidate " + id);
        }
    }
    void registerSealed(RuntimeId id, PreparedRuntimeGeneration value) {
        generations.put(id, Objects.requireNonNull(value, "prepared generation"));
    }
    void seal(CompiledDeployment value, Map<ExecutionUnitKey, RuntimeUnitGeneration> retained) {
        compiled = Objects.requireNonNull(value, "compiled");
        var all = new LinkedHashMap<>(retained);
        generations.forEach((id, generation) -> generation.units().forEach((key, unit) -> {
            var runtimePlan = compiled.runtimePlans().get(id);
            var expected = runtimePlan == null ? null : runtimePlan.units().get(key);
            if (!key.equals(unit.plan().key()) || !id.equals(unit.plan().runtimeId())
                || expected == null || !DeploymentPlanner.samePlan(expected, unit.plan())
                || all.putIfAbsent(key, unit) != null) throw new IllegalArgumentException("invalid sealed unit " + key);
        }));
        if (!all.keySet().equals(new java.util.HashSet<>(compiled.dependencyFirst()))) {
            throw new IllegalArgumentException("sealed units differ from validated plan");
        }
        units = Collections.unmodifiableMap(all);
    }
    public DeploymentTarget target() { return target; }
    public CompiledDeployment compiled() { return compiled; }
    public Map<RuntimeId, PreparedRuntimeGeneration> generations() { return Collections.unmodifiableMap(generations); }
    public Map<ExecutionUnitKey, RuntimeUnitGeneration> units() { return units; }
    java.util.Set<ExecutionUnitKey> ownedUnitKeys() {
        var keys = new java.util.LinkedHashSet<>(units.keySet());
        generations.values().forEach(value -> keys.addAll(value.units().keySet()));
        return java.util.Set.copyOf(keys);
    }
    Map<RuntimeId, RuntimeCandidate> candidates() { return Collections.unmodifiableMap(candidates); }
}
