package com.sstlfsj.fibra.engine;

/** Fibra engine 的终止性生命周期状态。 */
public enum FibraEngineState {
    NEW,
    STARTING,
    RUNNING,
    DEGRADED,
    STOPPING,
    TERMINATED
}
