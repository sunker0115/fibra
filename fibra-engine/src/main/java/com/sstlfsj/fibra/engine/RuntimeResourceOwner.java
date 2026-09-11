package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactRecord;
import reactor.core.publisher.Mono;

import java.util.List;

/** 长期 runtime 资源所有者；调用方在清理全部插件实例后关闭。 */
public interface RuntimeResourceOwner {
    /** 只登记一个局部更新句柄，不执行 I/O；未清理完的更新阻止下一次更新。 */
    RuntimeResourceUpdate createUpdate(List<ArtifactRecord> target);

    RuntimeCatalog catalog();

    /** 当前活动资源及尚未清理完的更新资源，身份不可重复。 */
    RuntimeResourceSnapshot snapshot();

    /** 等待已开始的准备；保留失败资源及其真实先决依赖，继续清理独立资源，缓存完整关闭终态。 */
    Mono<Void> closeAsync();
}
