package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.bridge.RemoteContributionFailure;

import java.util.Objects;
import java.util.Optional;

public final class NodeRpcException extends RuntimeException {
    private final NodeRpcPhase phase;
    private final RemoteContributionFailure remoteFailure;
    private final boolean cancelledBeforeSend;

    NodeRpcException(NodeRpcPhase phase, String message) {
        super(message);
        this.phase = Objects.requireNonNull(phase, "phase");
        remoteFailure = null;
        cancelledBeforeSend = false;
    }

    NodeRpcException(NodeRpcPhase phase, String message, Throwable cause) {
        super(message, cause);
        this.phase = Objects.requireNonNull(phase, "phase");
        remoteFailure = null;
        cancelledBeforeSend = false;
    }

    NodeRpcException(NodeRpcPhase phase, RemoteContributionFailure remoteFailure) {
        super(Objects.requireNonNull(remoteFailure, "remoteFailure").message());
        this.phase = Objects.requireNonNull(phase, "phase");
        this.remoteFailure = remoteFailure;
        cancelledBeforeSend = false;
    }

    private NodeRpcException(NodeRpcPhase phase, String message,
                             boolean cancelledBeforeSend) {
        super(message);
        this.phase = Objects.requireNonNull(phase, "phase");
        remoteFailure = null;
        this.cancelledBeforeSend = cancelledBeforeSend;
    }

    public NodeRpcPhase phase() {
        return phase;
    }

    public Optional<RemoteContributionFailure> remoteFailure() {
        return Optional.ofNullable(remoteFailure);
    }

    static NodeRpcException cancelledBeforeSend(String method) {
        return new NodeRpcException(NodeRpcPhase.REQUEST,
            "Node request was cancelled before send: " + method, true);
    }

    boolean wasCancelledBeforeSend() {
        return cancelledBeforeSend;
    }
}
