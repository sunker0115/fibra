package com.sstlfsj.fibra.engine;

@FunctionalInterface
public interface HostTerminationPort {
    void requestTermination(HostTerminationRequest request);
}
