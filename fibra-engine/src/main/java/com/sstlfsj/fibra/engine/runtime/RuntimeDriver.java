package com.sstlfsj.fibra.engine.runtime;

import com.sstlfsj.fibra.engine.deployment.PluginFacetSource;
import com.sstlfsj.fibra.engine.observation.RuntimeDriverSnapshot;

import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.RuntimeId;
import reactor.core.publisher.Mono;

public interface RuntimeDriver {
    RuntimeId id();

    Mono<RuntimeArtifactInspection> probe(PluginFacetSource source);

    Mono<RuntimeArtifactInspection> inspect(ManagedFacet facet);

    RuntimeCandidate createCandidate(RuntimeTargetSlice target);

    RuntimeDriverSnapshot snapshot();

    Mono<Void> closeAsync();
}
