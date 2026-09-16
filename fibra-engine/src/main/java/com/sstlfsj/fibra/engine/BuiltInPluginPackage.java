package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ExecutionTarget;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.config.PluginDefinitionRef;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 带稳定逻辑 package 身份的 Host 内建 definitions。 */
public final class BuiltInPluginPackage {
    public static final FacetId HOST_FACET = new FacetId("host");
    public static final ExecutionTarget HOST_TARGET = new ExecutionTarget("host");

    private final PluginId pluginId;
    private final String version;
    private final String packageDigest;
    private final PluginCatalog catalog;
    private final Map<PluginDefinitionRef, PluginCatalogEntry<?>> definitions;

    private BuiltInPluginPackage(Builder builder) {
        pluginId = Objects.requireNonNull(builder.pluginId, "pluginId");
        version = required(builder.version, "version");
        if (builder.packageDigest == null
            || !builder.packageDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                "package digest must be a lowercase SHA-256 digest");
        }
        packageDigest = builder.packageDigest;
        catalog = Objects.requireNonNull(builder.catalog, "catalog");
        var owned = new LinkedHashMap<PluginDefinitionRef, PluginCatalogEntry<?>>();
        catalog.entries().stream()
            .sorted(Comparator.comparing(entry -> entry.definition().name()))
            .forEach(entry -> owned.put(
                new PluginDefinitionRef(pluginId.value(), entry.definition().name()), entry));
        if (owned.isEmpty()) {
            throw new IllegalArgumentException(
                "built-in plugin package must contain at least one definition");
        }
        definitions = Collections.unmodifiableMap(owned);
    }

    public static Builder builder() { return new Builder(); }

    public PluginId pluginId() { return pluginId; }
    public String version() { return version; }
    public String packageDigest() { return packageDigest; }
    public FacetId facetId() { return HOST_FACET; }
    public ExecutionTarget executionTarget() { return HOST_TARGET; }
    /** 内建 Host facet 的稳定逻辑制品身份，不对应可安装文件。 */
    public ArtifactId artifactId() {
        return new ArtifactId("built-in:" + pluginId.value() + ":host");
    }
    public PluginCatalog catalog() { return catalog; }
    public Map<PluginDefinitionRef, PluginCatalogEntry<?>> definitions() {
        return definitions;
    }
    public PluginSelection selection(boolean enabled) {
        return new PluginSelection(pluginId, packageDigest, enabled);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static final class Builder {
        private PluginId pluginId;
        private String version;
        private String packageDigest;
        private PluginCatalog catalog;

        private Builder() { }
        public Builder pluginId(PluginId value) { pluginId = value; return this; }
        public Builder version(String value) { version = value; return this; }
        public Builder packageDigest(String value) { packageDigest = value; return this; }
        public Builder catalog(PluginCatalog value) { catalog = value; return this; }
        public BuiltInPluginPackage build() { return new BuiltInPluginPackage(this); }
    }
}
