package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.FacetDependency;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.ManagedPluginPackage;
import com.sstlfsj.fibra.artifact.PluginId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 把唯一 target 的逻辑依赖解析到已安装的精确 package revision。 */
public final class DeploymentTargetCompiler {
    public Compilation compile(DeploymentTarget target,
                               Collection<ManagedPluginPackage> installedPackages,
                               Collection<BuiltInPluginPackage> builtInPackages) {
        Objects.requireNonNull(target, "target");
        var packages = indexPackages(installedPackages);
        var facets = indexFacets(packages.values());
        var builtIns = indexBuiltIns(builtInPackages);
        rejectDynamicBuiltInConflicts(facets.values(), builtIns);
        rejectBuiltInArtifactConflicts(facets.keySet(), builtIns.values());

        var selected = new LinkedHashMap<ArtifactId, ManagedFacet>();
        for (var selection : target.selections().values()) {
            var builtIn = builtIns.get(selection.pluginId());
            if (builtIn != null) {
                if (!builtIn.packageDigest().equals(selection.packageRevision())) {
                    throw new IllegalArgumentException("selected built-in package revision is missing: "
                        + selection.pluginId() + '@' + selection.packageRevision());
                }
                continue;
            }
            var installed = packages.get(new PackageKey(selection.pluginId(),
                selection.packageRevision()));
            if (installed == null) {
                throw new IllegalArgumentException("selected package revision is missing: "
                    + selection.pluginId() + '@' + selection.packageRevision());
            }
            if (selection.enabled()) {
                installed.facets().forEach(facet ->
                    selected.put(facet.artifactId(), facet));
            }
        }

        var resolved = new LinkedHashMap<ArtifactId, List<ResolvedFacetDependency>>();
        for (var source : selected.values()) {
            var dependencies = new ArrayList<ResolvedFacetDependency>();
            var unique = new LinkedHashSet<FacetDependency>();
            for (var dependency : source.facet().dependencies()) {
                if (!unique.add(dependency)) {
                    throw new IllegalArgumentException("duplicate facet dependency from "
                        + logical(source) + " to " + dependency.pluginId() + '/'
                        + dependency.facetId());
                }
                var selection = target.selections().get(dependency.pluginId());
                if (selection == null) {
                    throw new IllegalArgumentException("dependency plugin is not selected: "
                        + dependency.pluginId());
                }
                if (!selection.enabled()) {
                    throw new IllegalArgumentException("dependency plugin is disabled: "
                        + dependency.pluginId());
                }
                var builtInDependency = builtIns.get(dependency.pluginId());
                ArtifactId targetArtifactId;
                if (builtInDependency != null) {
                    if (!builtInDependency.packageDigest().equals(
                        selection.packageRevision())
                        || !BuiltInPluginPackage.HOST_FACET.equals(
                            dependency.facetId())) {
                        throw new IllegalArgumentException(
                            "dependency facet is missing: " + dependency.pluginId()
                                + '@' + selection.packageRevision() + '/'
                                + dependency.facetId());
                    }
                    targetArtifactId = builtInDependency.artifactId();
                } else {
                    targetArtifactId = requireFacet(selected.values(),
                        dependency.pluginId(), selection.packageRevision(),
                        dependency.facetId()).artifactId();
                }
                dependencies.add(new ResolvedFacetDependency(dependency.pluginId(),
                    selection.packageRevision(), dependency.facetId(),
                    targetArtifactId));
            }
            dependencies.sort(Comparator.comparing(value -> value.artifactId().value()));
            resolved.put(source.artifactId(), List.copyOf(dependencies));
        }

        var dependencyFirst = dependencyFirst(selected, resolved);
        var compiled = new LinkedHashMap<ArtifactId, CompiledFacet>();
        selected.forEach((artifactId, facet) -> compiled.put(artifactId,
            new CompiledFacet(facet, resolved.getOrDefault(artifactId, List.of()))));
        return new Compilation(target, compiled, dependencyFirst);
    }

    private static Map<PackageKey, ManagedPluginPackage> indexPackages(
        Collection<ManagedPluginPackage> values) {
        Objects.requireNonNull(values, "installedPackages");
        var packages = new LinkedHashMap<PackageKey, ManagedPluginPackage>();
        values.stream().map(value -> Objects.requireNonNull(value, "managedPackage"))
            .sorted(Comparator.comparing((ManagedPluginPackage value) ->
                    value.pluginId().value())
                .thenComparing(ManagedPluginPackage::packageRevision))
            .forEach(value -> {
                var key = new PackageKey(value.pluginId(), value.packageRevision());
                if (packages.putIfAbsent(key, value) != null) {
                    throw new IllegalArgumentException("duplicate managed package "
                        + value.pluginId() + '@' + value.packageRevision());
                }
            });
        return packages;
    }

    private static Map<ArtifactId, ManagedFacet> indexFacets(
        Collection<ManagedPluginPackage> values) {
        var artifacts = new LinkedHashMap<ArtifactId, ManagedFacet>();
        var logical = new LinkedHashSet<FacetKey>();
        values.stream().flatMap(value -> value.facets().stream())
            .sorted(Comparator.comparing(value -> value.artifactId().value()))
            .forEach(facet -> {
                if (artifacts.putIfAbsent(facet.artifactId(), facet) != null) {
                    throw new IllegalArgumentException(
                        "duplicate artifact id " + facet.artifactId());
                }
                var key = new FacetKey(facet.pluginId(), facet.packageRevision(),
                    facet.facet().facetId());
                if (!logical.add(key)) {
                    throw new IllegalArgumentException("duplicate managed facet "
                        + facet.pluginId() + '@' + facet.packageRevision() + '/'
                        + facet.facet().facetId());
                }
            });
        return artifacts;
    }

    private static Map<PluginId, BuiltInPluginPackage> indexBuiltIns(
        Collection<BuiltInPluginPackage> values) {
        Objects.requireNonNull(values, "builtInPackages");
        var result = new LinkedHashMap<PluginId, BuiltInPluginPackage>();
        values.stream().map(value -> Objects.requireNonNull(value, "builtInPackage"))
            .sorted(Comparator.comparing(value -> value.pluginId().value()))
            .forEach(value -> {
                if (result.putIfAbsent(value.pluginId(), value) != null) {
                    throw new IllegalArgumentException(
                        "duplicate built-in plugin package " + value.pluginId());
                }
            });
        return result;
    }

    private static void rejectDynamicBuiltInConflicts(Collection<ManagedFacet> facets,
                                                       Map<PluginId, BuiltInPluginPackage> builtIns) {
        facets.stream().map(ManagedFacet::pluginId).distinct()
            .filter(builtIns::containsKey).findFirst().ifPresent(pluginId -> {
                throw new IllegalArgumentException(
                    "dynamic package conflicts with built-in plugin " + pluginId);
            });
    }

    private static void rejectBuiltInArtifactConflicts(
        Collection<ArtifactId> dynamicArtifacts,
        Collection<BuiltInPluginPackage> builtIns) {
        builtIns.stream().map(BuiltInPluginPackage::artifactId)
            .filter(dynamicArtifacts::contains).findFirst().ifPresent(artifactId -> {
                throw new IllegalArgumentException(
                    "dynamic artifact conflicts with built-in facet " + artifactId);
            });
    }

    private static ManagedFacet requireFacet(Collection<ManagedFacet> selected,
                                             PluginId pluginId,
                                             String packageRevision,
                                             FacetId facetId) {
        return selected.stream().filter(facet -> facet.pluginId().equals(pluginId)
                && facet.packageRevision().equals(packageRevision)
                && facet.facet().facetId().equals(facetId))
            .findFirst().orElseThrow(() -> new IllegalArgumentException(
                "dependency facet is missing: " + pluginId + '@' + packageRevision
                    + '/' + facetId));
    }

    private static List<ArtifactId> dependencyFirst(
        Map<ArtifactId, ManagedFacet> facets,
        Map<ArtifactId, List<ResolvedFacetDependency>> dependencies) {
        var states = new HashMap<ArtifactId, Visit>();
        var result = new ArrayList<ArtifactId>();
        for (var artifactId : facets.keySet()) {
            visit(artifactId, facets, dependencies, states, result);
        }
        return List.copyOf(result);
    }

    private static void visit(ArtifactId artifactId,
                              Map<ArtifactId, ManagedFacet> facets,
                              Map<ArtifactId, List<ResolvedFacetDependency>> dependencies,
                              Map<ArtifactId, Visit> states,
                              List<ArtifactId> result) {
        var state = states.get(artifactId);
        if (state == Visit.VISITED) return;
        if (state == Visit.VISITING) {
            throw new IllegalArgumentException(
                "facet dependency cycle at " + logical(facets.get(artifactId)));
        }
        if (!facets.containsKey(artifactId)) return;
        states.put(artifactId, Visit.VISITING);
        for (var dependency : dependencies.getOrDefault(artifactId, List.of())) {
            visit(dependency.artifactId(), facets, dependencies, states, result);
        }
        states.put(artifactId, Visit.VISITED);
        result.add(artifactId);
    }

    private static String logical(ManagedFacet facet) {
        return facet.pluginId() + "/" + facet.facet().facetId();
    }

    public static final class Compilation {
        private final DeploymentTarget target;
        private final Map<ArtifactId, CompiledFacet> facets;
        private final List<ArtifactId> dependencyFirst;

        private Compilation(DeploymentTarget target,
                            Map<ArtifactId, CompiledFacet> facets,
                            List<ArtifactId> dependencyFirst) {
            this.target = Objects.requireNonNull(target, "target");
            this.facets = immutableMap(facets);
            this.dependencyFirst = List.copyOf(dependencyFirst);
            if (!this.facets.keySet().equals(
                new LinkedHashSet<>(this.dependencyFirst))) {
                throw new IllegalArgumentException(
                    "dependency order must cover every compiled facet exactly");
            }
        }

        public DeploymentTarget target() { return target; }
        public Map<ArtifactId, CompiledFacet> facets() { return facets; }
        public List<ArtifactId> dependencyFirst() { return dependencyFirst; }
    }

    /** 只能由 compiler 产生的权威 managed facet 与精确 wiring。 */
    public static final class CompiledFacet {
        private final ManagedFacet facet;
        private final List<ResolvedFacetDependency> dependencies;

        private CompiledFacet(ManagedFacet facet,
                              List<ResolvedFacetDependency> dependencies) {
            this.facet = Objects.requireNonNull(facet, "facet");
            this.dependencies = List.copyOf(dependencies);
        }

        public ManagedFacet facet() { return facet; }
        public List<ResolvedFacetDependency> dependencies() { return dependencies; }
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private enum Visit { VISITING, VISITED }
    private record PackageKey(PluginId pluginId, String packageRevision) { }
    private record FacetKey(PluginId pluginId, String packageRevision, FacetId facetId) { }
}
