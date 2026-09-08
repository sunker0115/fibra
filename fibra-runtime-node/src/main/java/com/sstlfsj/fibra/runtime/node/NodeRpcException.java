package com.sstlfsj.fibra.runtime.node;

public final class NodeRpcException extends RuntimeException {
    private final NodeRpcPhase phase;

    NodeRpcException(NodeRpcPhase phase, String message) {
        super(message);
        this.phase = phase;
    }

    NodeRpcException(NodeRpcPhase phase, String message, Throwable cause) {
        super(message, cause);
        this.phase = phase;
    }

    public NodeRpcPhase phase() {
        return phase;
    }
}
