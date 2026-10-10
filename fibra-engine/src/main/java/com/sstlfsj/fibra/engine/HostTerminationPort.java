package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.engine.observation.HostTerminationRequest;

@FunctionalInterface
public interface HostTerminationPort {
    void requestTermination(HostTerminationRequest request);
}
