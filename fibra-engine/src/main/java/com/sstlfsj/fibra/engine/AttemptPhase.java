package com.sstlfsj.fibra.engine;

public enum AttemptPhase {
    REGISTERED,
    PREPARING,
    VALIDATING,
    READY_TO_SAVE,
    SAVING,
    PROMOTING,
    RECONCILING,
    DRAINING,
    STOPPING,
    RELEASING,
    SETTLED,
    FAILED
}
