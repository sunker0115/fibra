package com.sstlfsj.fibra.artifact;

import java.util.Objects;

public record FacetDependency(PluginId pluginId, FacetId facetId) {
    public FacetDependency {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(facetId, "facetId");
    }
}
