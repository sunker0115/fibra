package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ManagedFacet;

import java.util.List;

/** runtime 已静态准备的单个 facet；不代表任何执行实例或会话。 */
public interface PreparedArtifact {
    ManagedFacet facet();
    List<ResolvedFacetDependency> dependencies();
}
