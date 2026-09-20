package com.sstlfsj.fibra.artifact;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record PluginFacet(FacetId facetId, FacetRole role, RuntimeId runtimeId,
                          ExecutionTarget executionTarget, Path payload,
                          String payloadDigest, List<FacetDependency> dependencies,
                          List<String> requiredCapabilities) {
    public PluginFacet {
        Objects.requireNonNull(facetId, "facetId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(runtimeId, "runtimeId");
        Objects.requireNonNull(executionTarget, "executionTarget");
        Objects.requireNonNull(payload, "payload");
        if (payloadDigest == null || !payloadDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                "payload digest must be a lowercase SHA-256 digest");
        }
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        var capabilities = new LinkedHashSet<String>();
        for (var capability : Objects.requireNonNull(requiredCapabilities,
            "requiredCapabilities")) {
            if (capability == null || capability.isBlank()) {
                throw new IllegalArgumentException(
                    "required capability must not be blank");
            }
            if (!capabilities.add(capability)) {
                throw new IllegalArgumentException(
                    "duplicate required capability: " + capability);
            }
        }
        requiredCapabilities = List.copyOf(capabilities);
    }
}
