package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.RuntimeId;

import java.util.Objects;

/**
 * Runtime-neutral result of validating a managed artifact.
 * Runtime-private descriptors stay inside their owning driver.
 */
public record RuntimeArtifactInspection(RuntimeId runtimeId,
                                        ArtifactId artifactId) {
    public RuntimeArtifactInspection {
        Objects.requireNonNull(runtimeId, "runtimeId");
        Objects.requireNonNull(artifactId, "artifactId");
    }
}
