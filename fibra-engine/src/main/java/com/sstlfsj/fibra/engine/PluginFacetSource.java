package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ManagedFacet;

import java.util.List;
import java.util.Objects;

public record PluginFacetSource(ManagedFacet facet,
                                List<ResolvedFacetDependency> dependencies) {
    public PluginFacetSource {
        Objects.requireNonNull(facet, "facet");
        dependencies = List.copyOf(Objects.requireNonNull(dependencies,
            "dependencies"));
    }
}
