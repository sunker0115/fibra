package com.sstlfsj.fibra.engine;

public enum FibraDeploymentErrorStage {
    READ,
    VALIDATE,
    PREPARE,
    COMMIT,
    READINESS,
    ROLLBACK,
    RECOVERY
}
