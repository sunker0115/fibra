package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.*;
import java.util.*;

public final class BuiltInFacet {
    private final FacetId facetId;
    private final RuntimeId runtimeId;
    private final ExecutionTarget executionTarget;
    private final List<FacetDependency> dependencies;
    private final Set<String> requiredCapabilities;
    private final Set<String> definitionIds;
    private BuiltInFacet(Builder builder) {
        facetId = Objects.requireNonNull(builder.facetId, "facetId");
        runtimeId = Objects.requireNonNull(builder.runtimeId, "runtimeId");
        executionTarget = Objects.requireNonNull(builder.executionTarget, "executionTarget");
        dependencies = List.copyOf(builder.dependencies);
        requiredCapabilities = Set.copyOf(builder.requiredCapabilities);
        definitionIds = Set.copyOf(builder.definitionIds);
        if (new HashSet<>(dependencies).size() != dependencies.size()) throw new IllegalArgumentException("duplicate built-in dependency");
        if (definitionIds.stream().anyMatch(String::isBlank) || requiredCapabilities.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("built-in metadata contains a blank identity");
        }
    }
    public static Builder builder(FacetId facet, RuntimeId runtime, ExecutionTarget target) {
        return new Builder(facet, runtime, target);
    }
    public FacetId facetId() { return facetId; }
    public RuntimeId runtimeId() { return runtimeId; }
    public ExecutionTarget executionTarget() { return executionTarget; }
    public List<FacetDependency> dependencies() { return dependencies; }
    public Set<String> requiredCapabilities() { return requiredCapabilities; }
    public Set<String> definitionIds() { return definitionIds; }
    public static final class Builder {
        private final FacetId facetId;
        private final RuntimeId runtimeId;
        private final ExecutionTarget executionTarget;
        private List<FacetDependency> dependencies = List.of();
        private Set<String> requiredCapabilities = Set.of();
        private Set<String> definitionIds = Set.of();
        private Builder(FacetId facet, RuntimeId runtime, ExecutionTarget target) {
            facetId = facet; runtimeId = runtime; executionTarget = target;
        }
        public Builder dependencies(List<FacetDependency> value) { dependencies = List.copyOf(value); return this; }
        public Builder requiredCapabilities(Set<String> value) { requiredCapabilities = Set.copyOf(value); return this; }
        public Builder definitionIds(Set<String> value) { definitionIds = Set.copyOf(value); return this; }
        public BuiltInFacet build() { return new BuiltInFacet(this); }
    }
}
