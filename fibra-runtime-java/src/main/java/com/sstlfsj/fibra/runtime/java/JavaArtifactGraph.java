package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class JavaArtifactGraph {
    private final Map<ArtifactId, JavaPluginManifest> manifests;
    private final List<ArtifactId> dependencyFirst;

    private JavaArtifactGraph(Map<ArtifactId, JavaPluginManifest> manifests,
                              List<ArtifactId> dependencyFirst) {
        this.manifests = Map.copyOf(manifests);
        this.dependencyFirst = List.copyOf(dependencyFirst);
    }

    public static JavaArtifactGraph resolve(Collection<JavaPluginManifest> source) {
        var manifests = new LinkedHashMap<ArtifactId, JavaPluginManifest>();
        source.stream().sorted(java.util.Comparator.comparing(
                value -> value.artifactId().value()))
            .forEach(manifest -> {
                if (manifests.putIfAbsent(manifest.artifactId(), manifest) != null) {
                    throw error(manifest.artifactId(), "duplicate Java artifact");
                }
            });
        manifests.values().forEach(manifest -> validateRequirements(manifest, manifests));
        var result = new ArrayList<ArtifactId>();
        var visiting = new LinkedHashSet<ArtifactId>();
        var visited = new LinkedHashSet<ArtifactId>();
        manifests.keySet().forEach(id -> visit(id, manifests, visiting, visited, result));
        return new JavaArtifactGraph(manifests, result);
    }

    public List<ArtifactId> dependencyFirst() {
        return dependencyFirst;
    }

    public JavaPluginManifest manifest(ArtifactId id) {
        return manifests.get(id);
    }

    private static void validateRequirements(JavaPluginManifest manifest,
                                             Map<ArtifactId, JavaPluginManifest> manifests) {
        var ids = new LinkedHashSet<ArtifactId>();
        for (var requirement : manifest.requires()) {
            if (!ids.add(requirement.artifactId())) {
                throw error(manifest.artifactId(), "duplicate dependency "
                    + requirement.artifactId().value());
            }
            var dependency = manifests.get(requirement.artifactId());
            if (dependency == null) {
                throw error(manifest.artifactId(), "missing dependency "
                    + requirement.artifactId().value());
            }
            final VersionConstraint constraint;
            try {
                constraint = VersionConstraint.parse(requirement.versionConstraint());
            } catch (IllegalArgumentException exception) {
                throw new JavaRuntimeException(JavaRuntimePhase.GRAPH,
                    manifest.artifactId(), "invalid version constraint "
                    + requirement.versionConstraint(), exception);
            }
            if (!constraint.matches(dependency.version())) {
                throw error(manifest.artifactId(), "dependency "
                    + requirement.artifactId().value() + " version " + dependency.version()
                    + " does not match " + constraint);
            }
        }
    }

    private static void visit(ArtifactId id,
                              Map<ArtifactId, JavaPluginManifest> manifests,
                              LinkedHashSet<ArtifactId> visiting,
                              LinkedHashSet<ArtifactId> visited,
                              List<ArtifactId> result) {
        if (visited.contains(id)) {
            return;
        }
        if (!visiting.add(id)) {
            throw error(id, "Java artifact dependency cycle " + visiting + " -> " + id);
        }
        for (var requirement : manifests.get(id).requires()) {
            visit(requirement.artifactId(), manifests, visiting, visited, result);
        }
        visiting.remove(id);
        visited.add(id);
        result.add(id);
    }

    private static JavaRuntimeException error(ArtifactId id, String message) {
        return new JavaRuntimeException(JavaRuntimePhase.GRAPH, id, message, null);
    }
}
