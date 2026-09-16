package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.PluginId;

import java.util.Objects;

/** target 编译后指向精确 package revision 和物理 facet 的依赖。 */
public record ResolvedFacetDependency(PluginId pluginId, String packageRevision,
                                      FacetId facetId, ArtifactId artifactId) {
    public ResolvedFacetDependency {
        Objects.requireNonNull(pluginId, "pluginId");
        if (packageRevision == null || !packageRevision.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                "package revision must be a lowercase SHA-256 digest");
        }
        Objects.requireNonNull(facetId, "facetId");
        Objects.requireNonNull(artifactId, "artifactId");
    }
}
