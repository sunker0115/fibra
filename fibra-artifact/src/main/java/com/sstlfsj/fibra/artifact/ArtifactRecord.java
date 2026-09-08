package com.sstlfsj.fibra.artifact;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public final class ArtifactRecord {
    private final ArtifactId id;
    private final RuntimeId runtimeId;
    private final String version;
    private final String checksum;
    private final String revision;
    private final Path location;
    private final ArtifactState state;
    private final Instant updatedAt;

    private ArtifactRecord(Builder builder) {
        id = Objects.requireNonNull(builder.id, "id");
        runtimeId = Objects.requireNonNull(builder.runtimeId, "runtimeId");
        version = required(builder.version, "version");
        checksum = required(builder.checksum, "checksum");
        revision = required(builder.revision, "revision");
        location = Objects.requireNonNull(builder.location, "location")
            .toAbsolutePath().normalize();
        state = Objects.requireNonNull(builder.state, "state");
        updatedAt = Objects.requireNonNull(builder.updatedAt, "updatedAt");
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder().id(id).runtimeId(runtimeId).version(version)
            .checksum(checksum).revision(revision).location(location)
            .state(state).updatedAt(updatedAt);
    }

    public ArtifactId id() {
        return id;
    }

    public RuntimeId runtimeId() {
        return runtimeId;
    }

    public String version() {
        return version;
    }

    public String checksum() {
        return checksum;
    }

    public String revision() {
        return revision;
    }

    public Path location() {
        return location;
    }

    public ArtifactState state() {
        return state;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ArtifactRecord that)) {
            return false;
        }
        return id.equals(that.id) && runtimeId.equals(that.runtimeId)
            && version.equals(that.version) && checksum.equals(that.checksum)
            && revision.equals(that.revision) && location.equals(that.location)
            && state == that.state && updatedAt.equals(that.updatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, runtimeId, version, checksum, revision,
            location, state, updatedAt);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static final class Builder {
        private ArtifactId id;
        private RuntimeId runtimeId;
        private String version;
        private String checksum;
        private String revision;
        private Path location;
        private ArtifactState state;
        private Instant updatedAt;

        public Builder id(ArtifactId value) {
            id = value;
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

        public Builder checksum(String value) {
            checksum = value;
            return this;
        }

        public Builder revision(String value) {
            revision = value;
            return this;
        }

        public Builder location(Path value) {
            location = value;
            return this;
        }

        public Builder state(ArtifactState value) {
            state = value;
            return this;
        }

        public Builder updatedAt(Instant value) {
            updatedAt = value;
            return this;
        }

        public ArtifactRecord build() {
            return new ArtifactRecord(this);
        }
    }
}
