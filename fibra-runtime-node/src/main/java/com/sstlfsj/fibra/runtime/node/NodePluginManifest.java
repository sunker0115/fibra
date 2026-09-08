package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.artifact.ArtifactId;

import java.util.List;
import java.util.Objects;

public record NodePluginManifest(ArtifactId artifactId, String version, int protocol,
                                 String entrypoint,
                                 List<NodeEndpointManifest> contributions) {
    public NodePluginManifest {
        Objects.requireNonNull(artifactId, "artifactId");
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (protocol != 1) {
            throw new IllegalArgumentException("unsupported Node protocol " + protocol);
        }
        if (entrypoint == null || entrypoint.isBlank()) {
            throw new IllegalArgumentException("entrypoint must not be blank");
        }
        contributions = List.copyOf(contributions);
    }
}
