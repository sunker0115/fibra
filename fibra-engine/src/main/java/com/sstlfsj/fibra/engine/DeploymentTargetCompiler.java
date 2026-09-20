package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.*;
import java.util.*;

/** 动态与内建 facet 使用同一 package gate、exact revision 和全局 dependency resolver。 */
public final class DeploymentTargetCompiler {
    public Compilation compile(DeploymentTarget target, Collection<ManagedPluginPackage> installed,
                               Collection<BuiltInPluginPackage> builtIns) {
        Objects.requireNonNull(target, "target");
        var packages = new LinkedHashMap<PackageKey, ManagedPluginPackage>();
        var builtin = new LinkedHashMap<PluginId, BuiltInPluginPackage>();
        for (var value : builtIns) {
            if (builtin.putIfAbsent(value.pluginId(), value) != null) throw new IllegalArgumentException("duplicate built-in plugin package " + value.pluginId());
        }
        var artifacts = new HashSet<ArtifactId>();
        var all = new LinkedHashMap<FacetKey, Source>();
        for (var value : installed) {
            if (builtin.containsKey(value.pluginId())) throw new IllegalArgumentException("dynamic package conflicts with built-in plugin " + value.pluginId());
            if (packages.putIfAbsent(new PackageKey(value.pluginId(), value.packageRevision()), value) != null) {
                throw new IllegalArgumentException("duplicate managed package " + value.pluginId());
            }
            for (var facet : value.facets()) {
                var source = Source.dynamic(facet);
                put(all, artifacts, source);
            }
        }
        for (var value : builtIns) for (var facet : value.facets()) put(all, artifacts, Source.builtin(value, facet));
        var selected = new LinkedHashMap<ArtifactId, Source>();
        for (var selection : target.selections().values()) {
            var declared = builtin.get(selection.pluginId());
            if (declared != null) {
                if (!declared.packageDigest().equals(selection.packageRevision())) throw new IllegalArgumentException(
                    "selected built-in package revision is missing: " + selection.pluginId());
            } else if (!packages.containsKey(new PackageKey(selection.pluginId(), selection.packageRevision()))) {
                throw new IllegalArgumentException("selected package revision is missing: " + selection.pluginId());
            }
            if (selection.enabled()) all.values().stream().filter(source -> source.pluginId.equals(selection.pluginId())
                    && source.revision.equals(selection.packageRevision()))
                .sorted(Comparator.comparing(source -> source.artifact.value()))
                .forEach(source -> selected.put(source.artifact, source));
        }
        var dependencies = new LinkedHashMap<ArtifactId, List<ResolvedFacetDependency>>();
        for (var source : selected.values()) {
            var resolved = new ArrayList<ResolvedFacetDependency>();
            var unique = new HashSet<FacetDependency>();
            for (var dependency : source.dependencies) {
                if (!unique.add(dependency)) throw new IllegalArgumentException("duplicate facet dependency from " + source.key());
                var gate = target.selections().get(dependency.pluginId());
                if (gate == null || !gate.enabled()) throw new IllegalArgumentException("dependency plugin is not selected/enabled: " + dependency.pluginId());
                var targetFacet = all.get(new FacetKey(dependency.pluginId(), gate.packageRevision(), dependency.facetId()));
                if (targetFacet == null || !selected.containsKey(targetFacet.artifact)) {
                    throw new IllegalArgumentException("dependency facet is missing: " + dependency);
                }
                resolved.add(new ResolvedFacetDependency(dependency.pluginId(), gate.packageRevision(),
                    dependency.facetId(), targetFacet.artifact));
            }
            dependencies.put(source.artifact, resolved.stream().sorted(Comparator.comparing(value -> value.artifactId().value())).toList());
        }
        var order = new ArrayList<ArtifactId>();
        var visiting = new HashSet<ArtifactId>();
        var visited = new HashSet<ArtifactId>();
        for (var key : selected.keySet()) visit(key, dependencies, visiting, visited, order);
        var dynamic = new LinkedHashMap<ArtifactId, CompiledFacet>();
        var fixed = new LinkedHashMap<ArtifactId, CompiledBuiltInFacet>();
        selected.forEach((id, source) -> {
            if (source.managed != null) dynamic.put(id, new CompiledFacet(source.managed, dependencies.get(id)));
            else fixed.put(id, new CompiledBuiltInFacet(source.builtIn, source.fixedFacet, dependencies.get(id)));
        });
        return new Compilation(target, dynamic, fixed, order);
    }

    private static void put(Map<FacetKey, Source> all, Set<ArtifactId> artifacts, Source source) {
        if (!artifacts.add(source.artifact)) throw new IllegalArgumentException("duplicate artifact id " + source.artifact);
        if (all.putIfAbsent(source.key(), source) != null) throw new IllegalArgumentException("duplicate managed facet " + source.key());
    }
    private static void visit(ArtifactId key, Map<ArtifactId, List<ResolvedFacetDependency>> dependencies,
                              Set<ArtifactId> visiting, Set<ArtifactId> visited, List<ArtifactId> result) {
        if (visited.contains(key)) return;
        if (!visiting.add(key)) throw new IllegalArgumentException("facet dependency cycle at " + key);
        for (var dependency : dependencies.get(key)) visit(dependency.artifactId(), dependencies, visiting, visited, result);
        visiting.remove(key);
        visited.add(key);
        result.add(key);
    }
    private record PackageKey(PluginId pluginId, String revision) { }
    private record FacetKey(PluginId pluginId, String revision, FacetId facetId) { }
    private record Source(PluginId pluginId, String revision, FacetId facetId, ArtifactId artifact,
                          List<FacetDependency> dependencies, ManagedFacet managed,
                          BuiltInPluginPackage builtIn, BuiltInFacet fixedFacet) {
        static Source dynamic(ManagedFacet facet) {
            return new Source(facet.pluginId(), facet.packageRevision(), facet.facet().facetId(), facet.artifactId(),
                facet.facet().dependencies(), facet, null, null);
        }
        static Source builtin(BuiltInPluginPackage value, BuiltInFacet facet) {
            return new Source(value.pluginId(), value.packageDigest(), facet.facetId(), value.artifactId(facet.facetId()),
                facet.dependencies(), null, value, facet);
        }
        FacetKey key() { return new FacetKey(pluginId, revision, facetId); }
    }
    public static final class Compilation {
        private final DeploymentTarget target;
        private final Map<ArtifactId, CompiledFacet> facets;
        private final Map<ArtifactId, CompiledBuiltInFacet> builtInFacets;
        private final List<ArtifactId> dependencyFirst;
        private Compilation(DeploymentTarget target, Map<ArtifactId, CompiledFacet> facets,
                            Map<ArtifactId, CompiledBuiltInFacet> fixed, List<ArtifactId> order) {
            this.target = target;
            this.facets = Collections.unmodifiableMap(new LinkedHashMap<>(facets));
            builtInFacets = Collections.unmodifiableMap(new LinkedHashMap<>(fixed));
            dependencyFirst = List.copyOf(order);
        }
        public DeploymentTarget target() { return target; }
        public Map<ArtifactId, CompiledFacet> facets() { return facets; }
        public Map<ArtifactId, CompiledBuiltInFacet> builtInFacets() { return builtInFacets; }
        public List<ArtifactId> dependencyFirst() { return dependencyFirst; }
    }
    public static final class CompiledFacet {
        private final ManagedFacet facet;
        private final List<ResolvedFacetDependency> dependencies;
        private CompiledFacet(ManagedFacet facet, List<ResolvedFacetDependency> dependencies) {
            this.facet = facet;
            this.dependencies = List.copyOf(dependencies);
        }
        public ManagedFacet facet() { return facet; }
        public List<ResolvedFacetDependency> dependencies() { return dependencies; }
    }
    public static final class CompiledBuiltInFacet {
        private final BuiltInPluginPackage pluginPackage;
        private final BuiltInFacet facet;
        private final List<ResolvedFacetDependency> dependencies;
        private CompiledBuiltInFacet(BuiltInPluginPackage pluginPackage, BuiltInFacet facet,
                                     List<ResolvedFacetDependency> dependencies) {
            this.pluginPackage = pluginPackage;
            this.facet = facet;
            this.dependencies = List.copyOf(dependencies);
        }
        public BuiltInPluginPackage pluginPackage() { return pluginPackage; }
        public BuiltInFacet facet() { return facet; }
        public ArtifactId artifactId() { return pluginPackage.artifactId(facet.facetId()); }
        public List<ResolvedFacetDependency> dependencies() { return dependencies; }
    }
}
