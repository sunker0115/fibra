package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.RuntimeId;

import java.nio.file.Path;
import java.util.Objects;

public final class InstallArtifact implements EngineCommand {
    private final String expectedRevision;
    private final ArtifactId artifactId;
    private final RuntimeId runtimeId;
    private final String version;
    private final Path source;

    private InstallArtifact(Builder builder) {
        expectedRevision = builder.expectedRevision;
        artifactId = Objects.requireNonNull(builder.artifactId, "artifactId");
        runtimeId = Objects.requireNonNull(builder.runtimeId, "runtimeId");
        if (builder.version == null || builder.version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        version = builder.version;
        source = Objects.requireNonNull(builder.source, "source");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String expectedRevision() {
        return expectedRevision;
    }

    public ArtifactId artifactId() {
        return artifactId;
    }

    public RuntimeId runtimeId() {
        return runtimeId;
    }

    public String version() {
        return version;
    }

    public Path source() {
        return source;
    }

    public static final class Builder {
        private String expectedRevision;
        private ArtifactId artifactId;
        private RuntimeId runtimeId;
        private String version;
        private Path source;

        public Builder expectedRevision(String value) {
            expectedRevision = value;
            return this;
        }

        public Builder artifactId(ArtifactId value) {
            artifactId = value;
            return this;
        }

        public Builder runtimeId(RuntimeId value) {
            runtimeId = value;
            return this;
        }

        public Builder version(String value) {
            version = value;
            return this;
        }

        public Builder source(Path value) {
            source = value;
            return this;
        }

        public InstallArtifact build() {
            return new InstallArtifact(this);
        }
    }
}
