package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

/** 先登记后执行的一次 lifecycle 收敛句柄。 */
public interface ExecutionUpdate {
    Mono<Void> reconcileAsync();
    Set<ArtifactId> affectedArtifacts();
    List<? extends ExecutionHandle> handles();
    Mono<Void> closeAsync();
}
