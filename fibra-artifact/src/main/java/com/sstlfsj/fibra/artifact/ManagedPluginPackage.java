package com.sstlfsj.fibra.artifact;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** package store 原子发布后的完整逻辑插件 revision。 */
public final class ManagedPluginPackage {
    private final PluginId pluginId;
    private final String version;
    private final String packageRevision;
    private final List<ManagedFacet> facets;

    private ManagedPluginPackage(Builder builder) {
        pluginId = Objects.requireNonNull(builder.pluginId, "pluginId");
        if (builder.version == null || builder.version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        version = builder.version;
        if (builder.packageRevision == null
            || !builder.packageRevision.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                "package revision must be a lowercase SHA-256 digest");
        }
        packageRevision = builder.packageRevision;
        facets = freezeFacets(builder.facets);
    }

    public static Builder builder() { return new Builder(); }

    /** 从已双快照校验的候选包建立完整受管投影；物理 ID 必须逐 facet 显式提供。 */
    public static ManagedPluginPackage from(PluginPackage source,
                                            Map<FacetId, ArtifactId> artifactIds) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(artifactIds, "artifactIds");
        if (!artifactIds.keySet().equals(source.facets().stream()
            .map(PluginFacet::facetId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet()))) {
            throw new IllegalArgumentException(
                "artifact ids must cover every package facet exactly");
        }
        return builder().pluginId(source.pluginId()).version(source.version())
            .packageRevision(source.packageDigest())
            .facets(source.facets().stream().map(facet -> new ManagedFacet(
                artifactIds.get(facet.facetId()), source.pluginId(),
                source.packageDigest(), facet)).toList())
            .build();
    }

    public PluginId pluginId() { return pluginId; }
    public String version() { return version; }
    public String packageRevision() { return packageRevision; }
    public List<ManagedFacet> facets() { return facets; }

    private List<ManagedFacet> freezeFacets(Collection<ManagedFacet> values) {
        Objects.requireNonNull(values, "facets");
        var byId = new LinkedHashMap<FacetId, ManagedFacet>();
        var artifacts = new java.util.LinkedHashSet<ArtifactId>();
        values.stream().map(value -> Objects.requireNonNull(value, "facet"))
            .sorted(Comparator.comparing(value -> value.facet().facetId().value()))
            .forEach(facet -> {
                if (!pluginId.equals(facet.pluginId())
                    || !packageRevision.equals(facet.packageRevision())) {
                    throw new IllegalArgumentException(
                        "managed facet belongs to another package revision");
                }
                if (byId.putIfAbsent(facet.facet().facetId(), facet) != null) {
                    throw new IllegalArgumentException(
                        "duplicate managed facet " + facet.facet().facetId());
                }
                if (!artifacts.add(facet.artifactId())) {
                    throw new IllegalArgumentException(
                        "duplicate package artifact id " + facet.artifactId());
                }
            });
        if (byId.isEmpty()) {
            throw new IllegalArgumentException(
                "managed plugin package must contain at least one facet");
        }
        return List.copyOf(byId.values());
    }

    public static final class Builder {
        private PluginId pluginId;
        private String version;
        private String packageRevision;
        private Collection<ManagedFacet> facets = List.of();

        private Builder() { }
        public Builder pluginId(PluginId value) { pluginId = value; return this; }
        public Builder version(String value) { version = value; return this; }
        public Builder packageRevision(String value) {
            packageRevision = value; return this;
        }
        public Builder facets(Collection<ManagedFacet> value) {
            facets = value; return this;
        }
        public ManagedPluginPackage build() {
            return new ManagedPluginPackage(this);
        }
    }
}
