package com.sstlfsj.fibra.runtime.node;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public final class NodeRuntimeOptions {
    private final Path nodeExecutable;
    private final Path sessionRoot;
    private final Duration handshakeTimeout;
    private final Duration defaultRequestTimeout;
    private final Duration heartbeatInterval;
    private final Duration heartbeatTimeout;
    private final int maxMessageBytes;
    private final Duration terminateTimeout;

    private NodeRuntimeOptions(Builder builder) {
        nodeExecutable = Objects.requireNonNull(builder.nodeExecutable, "nodeExecutable");
        sessionRoot = Objects.requireNonNull(builder.sessionRoot, "sessionRoot");
        handshakeTimeout = positive(builder.handshakeTimeout, "handshakeTimeout");
        defaultRequestTimeout = positive(builder.defaultRequestTimeout,
            "defaultRequestTimeout");
        heartbeatInterval = positive(builder.heartbeatInterval, "heartbeatInterval");
        heartbeatTimeout = positive(builder.heartbeatTimeout, "heartbeatTimeout");
        terminateTimeout = positive(builder.terminateTimeout, "terminateTimeout");
        if (builder.maxMessageBytes <= 0) {
            throw new IllegalArgumentException("maxMessageBytes must be positive");
        }
        maxMessageBytes = builder.maxMessageBytes;
    }

    public static Builder builder(Path nodeExecutable, Path sessionRoot) {
        return new Builder(nodeExecutable, sessionRoot);
    }

    public static NodeRuntimeOptions defaults(Path nodeExecutable, Path sessionRoot) {
        return builder(nodeExecutable, sessionRoot).build();
    }

    public Path nodeExecutable() { return nodeExecutable; }
    public Path sessionRoot() { return sessionRoot; }
    public Duration handshakeTimeout() { return handshakeTimeout; }
    public Duration defaultRequestTimeout() { return defaultRequestTimeout; }
    public Duration heartbeatInterval() { return heartbeatInterval; }
    public Duration heartbeatTimeout() { return heartbeatTimeout; }
    public int maxMessageBytes() { return maxMessageBytes; }
    public Duration terminateTimeout() { return terminateTimeout; }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public static final class Builder {
        private final Path nodeExecutable;
        private final Path sessionRoot;
        private Duration handshakeTimeout = Duration.ofSeconds(5);
        private Duration defaultRequestTimeout = Duration.ofSeconds(30);
        private Duration heartbeatInterval = Duration.ofSeconds(15);
        private Duration heartbeatTimeout = Duration.ofSeconds(5);
        private int maxMessageBytes = 1024 * 1024;
        private Duration terminateTimeout = Duration.ofSeconds(5);

        private Builder(Path nodeExecutable, Path sessionRoot) {
            this.nodeExecutable = nodeExecutable;
            this.sessionRoot = sessionRoot;
        }

        public Builder handshakeTimeout(Duration value) {
            handshakeTimeout = value; return this;
        }
        public Builder defaultRequestTimeout(Duration value) {
            defaultRequestTimeout = value; return this;
        }
        public Builder heartbeatInterval(Duration value) {
            heartbeatInterval = value; return this;
        }
        public Builder heartbeatTimeout(Duration value) {
            heartbeatTimeout = value; return this;
        }
        public Builder maxMessageBytes(int value) { maxMessageBytes = value; return this; }
        public Builder terminateTimeout(Duration value) {
            terminateTimeout = value; return this;
        }
        public NodeRuntimeOptions build() { return new NodeRuntimeOptions(this); }
    }
}
