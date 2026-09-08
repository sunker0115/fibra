package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.RuntimeId;
import reactor.core.publisher.Mono;

public interface PluginRuntimeAdapter extends AutoCloseable {
    RuntimeId id();

    Mono<RuntimeArtifactInspection> inspect(ArtifactRecord artifact);

    Mono<PreparedRuntimeGeneration> prepare(RuntimeChangeRequest request);

    @Override
    default void close() {
    }
}
