package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.engine.ArtifactRuntime;
import com.sstlfsj.fibra.engine.DeploymentTargetCompiler;
import com.sstlfsj.fibra.engine.PreparedArtifact;
import com.sstlfsj.fibra.engine.ResolvedFacetDependency;

import java.util.List;
import java.util.Objects;

/** 已静态校验的 Node facet；不代表 sidecar、contribution 注册或执行会话。 */
public final class NodePreparedArtifact implements PreparedArtifact {
    private final ManagedFacet facet;
    private final List<ResolvedFacetDependency> dependencies;
    private final NodeFacetDescriptor descriptor;
    private final String resourceIdentity;

    NodePreparedArtifact(ManagedFacet facet,
                         List<ResolvedFacetDependency> dependencies,
                         NodeFacetDescriptor descriptor,
                         String resourceIdentity) {
        this.facet = Objects.requireNonNull(facet, "facet");
        this.dependencies = List.copyOf(Objects.requireNonNull(dependencies,
            "dependencies"));
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.resourceIdentity = Objects.requireNonNull(resourceIdentity,
            "resourceIdentity");
    }

    @Override
    public ManagedFacet facet() {
        return facet;
    }

    @Override
    public List<ResolvedFacetDependency> dependencies() {
        return dependencies;
    }

    /** execution 面只读取已验证的局部描述，绝不重新读取 payload。 */
    public NodeFacetDescriptor descriptor() {
        return descriptor;
    }

    boolean matches(DeploymentTargetCompiler.CompiledFacet candidate) {
        return facet.equals(candidate.facet())
            && dependencies.equals(candidate.dependencies());
    }

    ArtifactRuntime.Resource resource(ArtifactRuntime.ResourceState state) {
        return new ArtifactRuntime.Resource(facet, resourceIdentity, state, null);
    }
}
