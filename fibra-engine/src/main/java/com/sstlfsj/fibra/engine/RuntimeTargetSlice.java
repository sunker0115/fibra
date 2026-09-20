package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.DesiredEvaluation;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;

public final class RuntimeTargetSlice {
    private final RuntimeId runtimeId;
    private final DeploymentTarget target;
    private final DesiredEvaluation desired;
    private final HostCapabilitySnapshot capabilities;
    private final List<PluginFacetSource> facets;
    private final List<BuiltInPluginPackage> builtInPackages;
    private final Set<String> affectedEntryIds;
    private final Map<ExecutionUnitKey, List<ExecutionUnitKey>> unitDependencies;

    private RuntimeTargetSlice(Builder builder) {
        runtimeId = Objects.requireNonNull(builder.runtimeId, "runtimeId");
        target = Objects.requireNonNull(builder.target, "target");
        desired = Objects.requireNonNull(builder.desired, "desired");
        capabilities = Objects.requireNonNull(builder.capabilities,
            "capabilities");
        affectedEntryIds = Set.copyOf(Objects.requireNonNull(builder.affectedEntryIds, "affectedEntryIds"));
        var dependencies = new LinkedHashMap<ExecutionUnitKey, List<ExecutionUnitKey>>();
        Objects.requireNonNull(builder.unitDependencies, "unitDependencies")
            .forEach((key, value) -> dependencies.put(key, List.copyOf(value)));
        if (!dependencies.keySet().equals(affectedEntryIds.stream().map(ExecutionUnitKey::new)
            .collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalArgumentException("unit dependencies must cover exactly affected entries");
        }
        unitDependencies = Collections.unmodifiableMap(dependencies);
        builtInPackages = List.copyOf(builder.builtInPackages);
        if (builtInPackages.stream().flatMap(value -> value.facets().stream())
            .anyMatch(value -> !runtimeId.equals(value.runtimeId()))) {
            throw new IllegalArgumentException("built-in package belongs to another runtime");
        }
        if (affectedEntryIds.stream().anyMatch(id -> !desired.entries().containsKey(id))) {
            throw new IllegalArgumentException("unknown affected desired entry");
        }
        facets = Objects.requireNonNull(builder.facets, "facets").stream()
            .map(value -> Objects.requireNonNull(value, "facet"))
            .sorted(Comparator.comparing(value ->
                value.facet().artifactId().value()))
            .toList();
        if (facets.stream().anyMatch(value -> !runtimeId.equals(
            value.facet().facet().runtimeId()))) {
            throw new IllegalArgumentException(
                "runtime target slice contains a facet for another runtime");
        }
        if (!desired.graph().equals(target.desiredGraph())
            || !desired.context().equals(target.configContext())) {
            throw new IllegalArgumentException(
                "runtime target slice desired inputs differ from target");
        }
    }

    public static Builder builder(RuntimeId runtimeId, DeploymentTarget target) {
        return new Builder(runtimeId, target);
    }

    public RuntimeId runtimeId() { return runtimeId; }
    public DeploymentTarget target() { return target; }
    public DesiredEvaluation desired() { return desired; }
    public HostCapabilitySnapshot capabilities() { return capabilities; }
    public List<PluginFacetSource> facets() { return facets; }
    public List<BuiltInPluginPackage> builtInPackages() { return builtInPackages; }
    public Set<String> affectedEntryIds() { return affectedEntryIds; }
    public Map<ExecutionUnitKey, List<ExecutionUnitKey>> unitDependencies() { return unitDependencies; }

    public static final class Builder {
        private final RuntimeId runtimeId;
        private final DeploymentTarget target;
        private DesiredEvaluation desired;
        private HostCapabilitySnapshot capabilities;
        private List<PluginFacetSource> facets = List.of();
        private List<BuiltInPluginPackage> builtInPackages = List.of();
        private Set<String> affectedEntryIds;
        private Map<ExecutionUnitKey, List<ExecutionUnitKey>> unitDependencies;

        private Builder(RuntimeId runtimeId, DeploymentTarget target) {
            this.runtimeId = Objects.requireNonNull(runtimeId, "runtimeId");
            this.target = Objects.requireNonNull(target, "target");
        }

        public Builder desired(DesiredEvaluation value) {
            desired = Objects.requireNonNull(value, "desired");
            return this;
        }

        public Builder capabilities(HostCapabilitySnapshot value) {
            capabilities = Objects.requireNonNull(value, "capabilities");
            return this;
        }

        public Builder facets(List<PluginFacetSource> value) {
            facets = List.copyOf(Objects.requireNonNull(value, "facets"));
            return this;
        }

        public RuntimeTargetSlice build() {
            return new RuntimeTargetSlice(this);
        }

        public Builder builtInPackages(List<BuiltInPluginPackage> value) {
            builtInPackages = List.copyOf(value); return this;
        }

        public Builder affectedEntryIds(Set<String> value) {
            affectedEntryIds = Set.copyOf(value); return this;
        }

        public Builder unitDependencies(Map<ExecutionUnitKey, List<ExecutionUnitKey>> value) {
            unitDependencies = Map.copyOf(value); return this;
        }
    }
}
