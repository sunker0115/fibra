package com.sstlfsj.fibra.artifact;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** package store 原子发布的一条逻辑插件 revision 记录。 */
public final class PluginPackageRecord {
    private final PluginId pluginId;
    private final String version;
    private final String packageRevision;
    private final Path location;
    private final ManagedPluginPackage managedPackage;
    private final ArtifactState state;
    private final Instant updatedAt;

    private PluginPackageRecord(Builder builder) {
        pluginId = Objects.requireNonNull(builder.pluginId, "pluginId");
        version = required(builder.version, "version");
        packageRevision = digest(builder.packageRevision, "packageRevision");
        location = Objects.requireNonNull(builder.location, "location")
            .toAbsolutePath().normalize();
        managedPackage = Objects.requireNonNull(builder.managedPackage, "managedPackage");
        state = Objects.requireNonNull(builder.state, "state");
        updatedAt = Objects.requireNonNull(builder.updatedAt, "updatedAt");
        if (!pluginId.equals(managedPackage.pluginId())
            || !version.equals(managedPackage.version())
            || !packageRevision.equals(managedPackage.packageRevision())) {
            throw new IllegalArgumentException(
                "managed package identity does not match package record");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return builder().pluginId(pluginId).version(version)
            .packageRevision(packageRevision).location(location)
            .managedPackage(managedPackage).state(state).updatedAt(updatedAt);
    }

    public PluginId pluginId() { return pluginId; }
    public String version() { return version; }
    public String packageRevision() { return packageRevision; }
    public Path location() { return location; }
    public ManagedPluginPackage managedPackage() { return managedPackage; }
    public ArtifactState state() { return state; }
    public Instant updatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PluginPackageRecord that)) {
            return false;
        }
        return pluginId.equals(that.pluginId) && version.equals(that.version)
            && packageRevision.equals(that.packageRevision)
            && location.equals(that.location)
            && managedPackage.equals(that.managedPackage)
            && state == that.state && updatedAt.equals(that.updatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pluginId, version, packageRevision, location,
            managedPackage, state, updatedAt);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String digest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                name + " must be a lowercase SHA-256 digest");
        }
        return value;
    }

    public static final class Builder {
        private PluginId pluginId;
        private String version;
        private String packageRevision;
        private Path location;
        private ManagedPluginPackage managedPackage;
        private ArtifactState state;
        private Instant updatedAt;

        private Builder() { }
        public Builder pluginId(PluginId value) { pluginId = value; return this; }
        public Builder version(String value) { version = value; return this; }
        public Builder packageRevision(String value) {
            packageRevision = value; return this;
        }
        public Builder location(Path value) { location = value; return this; }
        public Builder managedPackage(ManagedPluginPackage value) {
            managedPackage = value; return this;
        }
        public Builder state(ArtifactState value) { state = value; return this; }
        public Builder updatedAt(Instant value) { updatedAt = value; return this; }
        public PluginPackageRecord build() { return new PluginPackageRecord(this); }
    }
}
