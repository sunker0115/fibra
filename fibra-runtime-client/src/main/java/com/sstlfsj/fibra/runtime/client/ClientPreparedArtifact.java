package com.sstlfsj.fibra.runtime.client;

import com.sstlfsj.fibra.artifact.ExecutionTarget;
import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.client.protocol.ClientMessage;
import com.sstlfsj.fibra.engine.PreparedArtifact;
import com.sstlfsj.fibra.engine.ResolvedFacetDependency;

import java.util.List;
import java.util.Objects;

/** 已校验的 client facet 静态描述；不代表 client execution 或会话。 */
public record ClientPreparedArtifact(ManagedFacet facet,
                                     List<ResolvedFacetDependency> dependencies,
                                     String entryModule,
                                     List<ClientMessage.ResourceDescriptor> resources,
                                     ExecutionTarget executionTarget,
                                     List<String> requiredCapabilities)
    implements PreparedArtifact {
    public ClientPreparedArtifact {
        Objects.requireNonNull(facet, "facet");
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        if (entryModule == null || entryModule.isBlank()) {
            throw new IllegalArgumentException("entryModule must not be blank");
        }
        resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
        if (resources.stream().noneMatch(resource -> entryModule.equals(resource.path()))) {
            throw new IllegalArgumentException(
                "entryModule must reference a client resource");
        }
        executionTarget = Objects.requireNonNull(executionTarget, "executionTarget");
        requiredCapabilities = List.copyOf(Objects.requireNonNull(
            requiredCapabilities, "requiredCapabilities"));
    }
}
