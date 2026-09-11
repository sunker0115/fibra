package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.RuntimeId;
import reactor.core.publisher.Mono;

public interface PluginRuntimeAdapter {
    RuntimeId id();

    Mono<RuntimeArtifactInspection> inspect(ArtifactRecord artifact);

    /** 只创建所有权句柄，不打开文件、ClassLoader 或进程；资源在句柄登记后准备。 */
    RuntimeResourceOwner create();
}
