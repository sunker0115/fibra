package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.DesiredEvaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CompiledDeployment {
    private final DeploymentTarget target;
    private final String compiledFingerprint;
    private final DeploymentTargetCompiler.Compilation facetGraph;
    private final DesiredEvaluation desired;
    private final Map<RuntimeId, RuntimePlan> runtimePlans;
    private final List<ExecutionUnitKey> dependencyFirst;
    private final List<ExecutionUnitKey> reverseDependency;
    private final Set<ExecutionUnitKey> affectedUnits;
    private final Set<ExecutionUnitKey> retainedUnits;

    private CompiledDeployment(Builder builder) {
        target = Objects.requireNonNull(builder.target, "target");
        compiledFingerprint = Objects.requireNonNull(builder.compiledFingerprint,
            "compiledFingerprint");
        if (!compiledFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                "compiled fingerprint must be a lowercase SHA-256 digest");
        }
        facetGraph = Objects.requireNonNull(builder.facetGraph, "facetGraph");
        desired = Objects.requireNonNull(builder.desired, "desired");
        if (!target.equals(facetGraph.target())
            || !target.desiredGraph().equals(desired.graph())
            || !target.configContext().equals(desired.context())) {
            throw new IllegalArgumentException(
                "compiled deployment inputs do not describe the same target");
        }
        var plans = new LinkedHashMap<RuntimeId, RuntimePlan>();
        Objects.requireNonNull(builder.runtimePlans, "runtimePlans")
            .forEach((id, plan) -> {
                Objects.requireNonNull(id, "runtime id");
                Objects.requireNonNull(plan, "runtime plan");
                if (!id.equals(plan.runtimeId())) {
                    throw new IllegalArgumentException(
                        "runtime plan identity mismatch");
                }
                plans.put(id, plan);
            });
        runtimePlans = Collections.unmodifiableMap(plans);
        var allUnits = new LinkedHashSet<ExecutionUnitKey>();
        runtimePlans.values().forEach(plan -> plan.units().keySet().forEach(key -> {
            if (!allUnits.add(key)) {
                throw new IllegalArgumentException(
                    "execution unit belongs to multiple runtimes: " + key);
            }
        }));
        dependencyFirst = List.copyOf(Objects.requireNonNull(
            builder.dependencyFirst, "dependencyFirst"));
        if (dependencyFirst.size() != allUnits.size()
            || !new LinkedHashSet<>(dependencyFirst).equals(allUnits)) {
            throw new IllegalArgumentException(
                "global dependency order must cover every execution unit exactly");
        }
        reverseDependency = dependencyFirst.reversed();
        affectedUnits = Set.copyOf(Objects.requireNonNull(builder.affectedUnits,
            "affectedUnits"));
        retainedUnits = Set.copyOf(Objects.requireNonNull(builder.retainedUnits,
            "retainedUnits"));
        if (!Collections.disjoint(affectedUnits, retainedUnits)
            || !allUnits.equals(union(affectedUnits, retainedUnits))) {
            throw new IllegalArgumentException(
                "affected and retained units must partition the compiled deployment");
        }
    }

    public static Builder builder(DeploymentTarget target,
                                  String compiledFingerprint) {
        return new Builder(target, compiledFingerprint);
    }

    public DeploymentTarget target() { return target; }
    public String compiledFingerprint() { return compiledFingerprint; }
    public DeploymentTargetCompiler.Compilation facetGraph() { return facetGraph; }
    public DesiredEvaluation desired() { return desired; }
    public Map<RuntimeId, RuntimePlan> runtimePlans() { return runtimePlans; }
    public List<ExecutionUnitKey> dependencyFirst() { return dependencyFirst; }
    public List<ExecutionUnitKey> reverseDependency() { return reverseDependency; }
    public Set<ExecutionUnitKey> affectedUnits() { return affectedUnits; }
    public Set<ExecutionUnitKey> retainedUnits() { return retainedUnits; }

    private static Set<ExecutionUnitKey> union(Set<ExecutionUnitKey> first,
                                               Set<ExecutionUnitKey> second) {
        var result = new LinkedHashSet<>(first);
        result.addAll(second);
        return result;
    }

    public static final class Builder {
        private final DeploymentTarget target;
        private final String compiledFingerprint;
        private DeploymentTargetCompiler.Compilation facetGraph;
        private DesiredEvaluation desired;
        private Map<RuntimeId, RuntimePlan> runtimePlans = Map.of();
        private List<ExecutionUnitKey> dependencyFirst = List.of();
        private Set<ExecutionUnitKey> affectedUnits = Set.of();
        private Set<ExecutionUnitKey> retainedUnits = Set.of();

        private Builder(DeploymentTarget target, String compiledFingerprint) {
            this.target = Objects.requireNonNull(target, "target");
            this.compiledFingerprint = Objects.requireNonNull(compiledFingerprint,
                "compiledFingerprint");
        }

        public Builder facetGraph(DeploymentTargetCompiler.Compilation value) {
            facetGraph = Objects.requireNonNull(value, "facetGraph");
            return this;
        }

        public Builder desired(DesiredEvaluation value) {
            desired = Objects.requireNonNull(value, "desired");
            return this;
        }

        public Builder runtimePlans(Map<RuntimeId, RuntimePlan> value) {
            runtimePlans = Map.copyOf(Objects.requireNonNull(value,
                "runtimePlans"));
            return this;
        }

        public Builder dependencyFirst(List<ExecutionUnitKey> value) {
            dependencyFirst = List.copyOf(Objects.requireNonNull(value,
                "dependencyFirst"));
            return this;
        }

        public Builder affectedUnits(Set<ExecutionUnitKey> value) {
            affectedUnits = Set.copyOf(Objects.requireNonNull(value,
                "affectedUnits"));
            return this;
        }

        public Builder retainedUnits(Set<ExecutionUnitKey> value) {
            retainedUnits = Set.copyOf(Objects.requireNonNull(value,
                "retainedUnits"));
            return this;
        }

        public CompiledDeployment build() {
            return new CompiledDeployment(this);
        }
    }
}
