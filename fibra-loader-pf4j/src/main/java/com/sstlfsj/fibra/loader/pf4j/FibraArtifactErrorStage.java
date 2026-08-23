package com.sstlfsj.fibra.loader.pf4j;

/** 插件制品失败所处的稳定阶段。 */
public enum FibraArtifactErrorStage {
    READ,
    VALIDATE,
    RESOLVE,
    DISPOSE,
    INSTALL,
    APPLY,
    ROLLBACK
}
