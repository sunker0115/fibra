package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.engine.DeploymentTargetCompiler.CompiledFacet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 只消费 compiler 的精确 wiring；外部 runtime 依赖不参与 Java loader 图。 */
public final class JavaFacetGraph {
    private final Map<ArtifactId, CompiledFacet> facets;
    private final List<ArtifactId> dependencyFirst;

    private JavaFacetGraph(Map<ArtifactId, CompiledFacet> facets, List<ArtifactId> order) {
        this.facets = Map.copyOf(facets);
        dependencyFirst = List.copyOf(order);
    }

    public static JavaFacetGraph resolve(Collection<CompiledFacet> source) {
        var facets = new LinkedHashMap<ArtifactId, CompiledFacet>();
        source.stream().sorted(Comparator.comparing(value -> value.facet().artifactId().value())).forEach(value -> {
            var id = value.facet().artifactId();
            if (!JavaArtifactRuntime.RUNTIME_ID.equals(value.facet().facet().runtimeId())) {
                throw error(id, "facet runtime is not Java");
            }
            if (facets.putIfAbsent(id, value) != null) throw error(id, "duplicate Java facet");
        });
        facets.forEach((id, value) -> value.dependencies().forEach(dependency -> {
            var target = facets.get(dependency.artifactId());
            if (target != null && (!target.facet().pluginId().equals(dependency.pluginId())
                || !target.facet().packageRevision().equals(dependency.packageRevision())
                || !target.facet().facet().facetId().equals(dependency.facetId()))) {
                throw error(id, "resolved dependency does not match Java facet");
            }
        }));
        var order = new ArrayList<ArtifactId>();
        var visiting = new LinkedHashSet<ArtifactId>();
        var visited = new LinkedHashSet<ArtifactId>();
        facets.keySet().forEach(id -> visit(id, facets, visiting, visited, order));
        return new JavaFacetGraph(facets, order);
    }

    public List<ArtifactId> dependencyFirst() { return dependencyFirst; }
    public CompiledFacet facet(ArtifactId id) { return facets.get(id); }

    private static void visit(ArtifactId id, Map<ArtifactId, CompiledFacet> facets,
                               LinkedHashSet<ArtifactId> visiting, LinkedHashSet<ArtifactId> visited,
                               List<ArtifactId> order) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) throw error(id, "Java facet dependency cycle");
        facets.get(id).dependencies().stream().map(value -> value.artifactId()).filter(facets::containsKey)
            .forEach(dependency -> visit(dependency, facets, visiting, visited, order));
        visiting.remove(id);
        visited.add(id);
        order.add(id);
    }

    private static JavaRuntimeException error(ArtifactId id, String message) {
        return new JavaRuntimeException(JavaRuntimePhase.GRAPH, id, message, null);
    }
}
