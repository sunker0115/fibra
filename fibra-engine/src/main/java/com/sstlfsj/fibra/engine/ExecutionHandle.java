package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import reactor.core.publisher.Mono;

import java.util.Set;

/** 一次具体 execution 的所有权句柄，与 PreparedArtifact 所有权相互独立。 */
public interface ExecutionHandle {
    String runtimeInstanceId();
    Set<ArtifactId> referencedArtifacts();
    Mono<Void> closeAsync();
}
