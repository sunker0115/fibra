package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import reactor.core.publisher.Mono;

import java.util.Set;

/** 有界的制品资源替换；不管理实例、发布或目标保存。 */
public interface RuntimeResourceUpdate {
    Mono<Void> prepareAsync();

    Set<ArtifactId> affectedArtifacts();

    /** 准备成功后的完整目标 catalog，包含未受影响的原定义对象。 */
    RuntimeCatalog catalog();

    /** 本次更新实际拥有的资源，不包含借用的保留资源。 */
    RuntimeResourceSnapshot snapshot();

    /** 只转移所有权：新资源交给 owner，旧闭包交给 update；不执行 I/O。 */
    void adopt();

    /** 采纳前清理新资源，采纳后清理旧资源；关闭后不能准备或采纳。 */
    Mono<Void> closeAsync();
}
