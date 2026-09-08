package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;

import java.util.Objects;

public record JavaArtifactRequirement(ArtifactId artifactId, String versionConstraint) {
    public JavaArtifactRequirement {
        Objects.requireNonNull(artifactId, "artifactId");
        if (versionConstraint == null || versionConstraint.isBlank()) {
            throw new IllegalArgumentException("versionConstraint must not be blank");
        }
    }
}
