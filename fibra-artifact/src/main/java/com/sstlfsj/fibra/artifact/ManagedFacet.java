package com.sstlfsj.fibra.artifact;

import java.util.Objects;

/** package store 赋予物理身份后的不可变 facet；不携带部署期依赖解析状态。 */
public record ManagedFacet(ArtifactId artifactId, PluginId pluginId,
                           String packageRevision, PluginFacet facet) {
    public ManagedFacet {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(pluginId, "pluginId");
        if (packageRevision == null || !packageRevision.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                "package revision must be a lowercase SHA-256 digest");
        }
        Objects.requireNonNull(facet, "facet");
    }
}
