package com.sstlfsj.fibra.engine;

import java.util.List;

/** 无持久部署目标时提供完整初始制品选择；已有目标恢复不读取此来源。 */
@FunctionalInterface
public interface InitialArtifactSource {
    List<DeploymentArtifact> load();
}
