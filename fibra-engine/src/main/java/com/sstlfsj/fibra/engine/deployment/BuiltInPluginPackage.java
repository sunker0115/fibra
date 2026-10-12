package com.sstlfsj.fibra.engine.deployment;

import com.sstlfsj.fibra.artifact.*;
import com.sstlfsj.fibra.value.LiteralValue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
        var implementationDigest = Objects.requireNonNull(builder.implementationDigest, "implementationDigest");
        if (version.isBlank() || !implementationDigest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid built-in package identity");
        facets = builder.facets.stream().sorted(Comparator.comparing(value -> value.facetId().value())).toList();
        if (facets.isEmpty() || facets.stream().map(BuiltInFacet::facetId).distinct().count() != facets.size()) {
            throw new IllegalArgumentException("built-in facets must be nonempty and unique");
        }
        if (facets.stream().map(BuiltInFacet::runtimeId).distinct().count() != 1) {
            throw new IllegalArgumentException("a built-in package must belong to one runtime provider");
        }
        packageDigest = contentDigest(implementationDigest);
    }
    private String contentDigest(String implementationDigest) {
        var declarations = facets.stream().map(facet -> {
            var schemas = new TreeMap<String, Object>();
            facet.configSchemas().forEach((name, source) -> schemas.put(name,
                Map.of("profile", source.profile(), "schema", source.schema())));
            var dependencies = facet.dependencies().stream().sorted(Comparator
                .comparing((FacetDependency value) -> value.pluginId().value())
                .thenComparing(value -> value.facetId().value()))
                .map(value -> Map.of("pluginId", value.pluginId().value(), "facetId", value.facetId().value())).toList();
            return Map.of("facetId", facet.facetId().value(), "runtime", facet.runtimeId().value(),
                "target", facet.executionTarget().value(), "dependencies", dependencies,
                "capabilities", facet.requiredCapabilities().stream().sorted().toList(),
                "definitionIds", facet.definitionIds().stream().sorted().toList(), "configSchemas", schemas);
        }).toList();
        var canonical = LiteralValue.of(Map.of("pluginId", pluginId.value(), "version", version,
            "implementationDigest", implementationDigest, "facets", declarations)).canonicalJson();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("fibra-built-in-package-v2\0" + canonical).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
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
        private String implementationDigest;
        private List<BuiltInFacet> facets = List.of();
        private Builder() { }
        public Builder pluginId(PluginId value) { pluginId = value; return this; }
        public Builder version(String value) { version = value; return this; }
        public Builder implementationDigest(String value) { implementationDigest = value; return this; }
        public Builder facets(List<BuiltInFacet> value) { facets = List.copyOf(value); return this; }
        public BuiltInPluginPackage build() { return new BuiltInPluginPackage(this); }
    }
}
