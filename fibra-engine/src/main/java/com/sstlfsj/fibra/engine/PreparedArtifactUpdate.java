package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

/** 先登记后准备的一次受影响制品闭包更新。 */
public interface PreparedArtifactUpdate {
    Mono<Void> prepareAsync();
    Map<ArtifactId, PreparedArtifact> preparedArtifacts();
    Set<ArtifactId> affectedArtifacts();
    /** 只转移所有权，不执行 I/O。 */
    void adopt();
    Mono<Void> closeAsync();
}
