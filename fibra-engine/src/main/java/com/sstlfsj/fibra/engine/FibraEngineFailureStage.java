package com.sstlfsj.fibra.engine;

public enum FibraEngineFailureStage {
    STARTUP,
    ARTIFACT_RECONCILE,
    CONFIG_RECONCILE,
    DEPLOYMENT,
    READINESS,
    CLOSE
}
