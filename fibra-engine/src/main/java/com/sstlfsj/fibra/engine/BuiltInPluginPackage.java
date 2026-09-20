package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.*;
import java.util.*;

/** 内建 package 的纯身份声明；实际 definitions 只归 provider 私有所有。 */
public final class BuiltInPluginPackage {
    private final PluginId pluginId;
    private final String version;
    private final String packageDigest;
    private final List<BuiltInFacet> facets;
    private BuiltInPluginPackage(Builder builder) {
        pluginId = Objects.requireNonNull(builder.pluginId, "pluginId");
        version = Objects.requireNonNull(builder.version, "version");
        packageDigest = Objects.requireNonNull(builder.packageDigest, "packageDigest");
        if (version.isBlank() || !packageDigest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid built-in package identity");
        facets = builder.facets.stream().sorted(Comparator.comparing(value -> value.facetId().value())).toList();
        if (facets.isEmpty() || facets.stream().map(BuiltInFacet::facetId).distinct().count() != facets.size()) {
            throw new IllegalArgumentException("built-in facets must be nonempty and unique");
        }
        if (facets.stream().map(BuiltInFacet::runtimeId).distinct().count() != 1) {
            throw new IllegalArgumentException("a built-in package must belong to one runtime provider");
        }
    }
    public static Builder builder() { return new Builder(); }
    public PluginId pluginId() { return pluginId; }
    public String version() { return version; }
    public String packageDigest() { return packageDigest; }
    public List<BuiltInFacet> facets() { return facets; }
    public ArtifactId artifactId(FacetId facetId) {
        if (facets.stream().noneMatch(facet -> facet.facetId().equals(facetId))) throw new IllegalArgumentException("unknown built-in facet " + facetId);
        return new ArtifactId("built-in:" + pluginId.value() + ':' + packageDigest + ':' + facetId.value());
    }
    public PluginSelection selection(boolean enabled) { return new PluginSelection(pluginId, packageDigest, enabled); }
    public static final class Builder {
        private PluginId pluginId;
        private String version;
        private String packageDigest;
        private List<BuiltInFacet> facets = List.of();
        private Builder() { }
        public Builder pluginId(PluginId value) { pluginId = value; return this; }
        public Builder version(String value) { version = value; return this; }
        public Builder packageDigest(String value) { packageDigest = value; return this; }
        public Builder facets(List<BuiltInFacet> value) { facets = List.copyOf(value); return this; }
        public BuiltInPluginPackage build() { return new BuiltInPluginPackage(this); }
    }
}
