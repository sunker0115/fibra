package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record JavaPluginManifest(ArtifactId artifactId, String version,
                                 Optional<String> entrypoint,
                                 List<JavaArtifactRequirement> requires) {
    public JavaPluginManifest {
        Objects.requireNonNull(artifactId, "artifactId");
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        Objects.requireNonNull(entrypoint, "entrypoint");
        entrypoint.ifPresent(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException("entrypoint must not be blank");
            }
        });
        requires = List.copyOf(requires);
    }
}
