package com.sstlfsj.fibra.engine.deployment;

import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.PluginId;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 精确部署目标所选插件包的静态声明目录，不表示运行就绪。 */
public record PackageDefinitionCatalog(long targetRevision, PluginId pluginId,
                                       String packageRevision,
                                       List<FacetDefinitionNames> facets) {
    public PackageDefinitionCatalog {
        if (targetRevision < 0) {
            throw new IllegalArgumentException("target revision must not be negative");
        }
        Objects.requireNonNull(pluginId, "pluginId");
        if (packageRevision == null || !packageRevision.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                "package revision must be a lowercase SHA-256 digest");
        }
        Objects.requireNonNull(facets, "facets");
        facets = facets.stream()
            .map(facet -> Objects.requireNonNull(facet, "facet"))
            .sorted(Comparator.comparing(facet -> facet.facetId().value()))
            .toList();
        var facetIds = new HashSet<FacetId>();
        for (var facet : facets) {
            if (!facetIds.add(facet.facetId())) {
                throw new IllegalArgumentException("duplicate facet id " + facet.facetId());
            }
        }
    }
}
