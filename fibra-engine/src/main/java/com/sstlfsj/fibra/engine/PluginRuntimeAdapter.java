package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactPackage;
import com.sstlfsj.fibra.artifact.RuntimeId;
import reactor.core.publisher.Mono;

public interface PluginRuntimeAdapter {
    RuntimeId id();

    /** 只探测安装单元元数据；返回的 source 必须是整个安装单元根目录。 */
    Mono<DeploymentArtifact> probe(ArtifactPackage artifact);

    Mono<RuntimeArtifactInspection> inspect(ArtifactRecord artifact);

    /** 只创建所有权句柄，不打开文件、ClassLoader 或进程；资源在句柄登记后准备。 */
    RuntimeResourceOwner create();
}
