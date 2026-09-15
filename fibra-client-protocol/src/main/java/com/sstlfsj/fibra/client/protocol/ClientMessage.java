package com.sstlfsj.fibra.client.protocol;

import java.util.List;
import java.util.Objects;

/** v1 的封闭消息集合；每种消息只携带其阶段允许的身份。 */
public sealed interface ClientMessage permits ClientMessage.Hello, ClientMessage.Welcome,
    ClientMessage.Snapshot, ClientMessage.Prepare, ClientMessage.Activate, ClientMessage.Drain,
    ClientMessage.Stop, ClientMessage.LifecycleResult, ClientMessage.Observed,
    ClientMessage.Call, ClientMessage.CallResult, ClientMessage.Detach {
    String type();

    record Hello(HelloIdentity identity, String executionTarget, List<String> capabilities)
        implements ClientMessage {
        public Hello {
            identity = Objects.requireNonNull(identity, "identity");
            executionTarget = required(executionTarget, "executionTarget");
            capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
            if (capabilities.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("capabilities must contain non-blank values");
            }
        }
        @Override public String type() { return "client.hello"; }
    }

    record Welcome(SessionFence session) implements ClientMessage {
        public Welcome { session = Objects.requireNonNull(session, "session"); }
        @Override public String type() { return "host.welcome"; }
    }

    record Snapshot(SessionFence session, long viewRevision, String targetDigest) implements ClientMessage {
        public Snapshot {
            session = Objects.requireNonNull(session, "session");
            if (viewRevision < 1) throw new IllegalArgumentException("viewRevision must be positive");
            targetDigest = required(targetDigest, "targetDigest");
        }
        @Override public String type() { return "host.snapshot"; }
    }

    record Prepare(LifecycleFence lifecycle) implements ClientMessage {
        public Prepare { lifecycle = Objects.requireNonNull(lifecycle, "lifecycle"); }
        @Override public String type() { return "host.prepare"; }
    }
    record Activate(LifecycleFence lifecycle) implements ClientMessage {
        public Activate { lifecycle = Objects.requireNonNull(lifecycle, "lifecycle"); }
        @Override public String type() { return "host.activate"; }
    }
    record Drain(LifecycleFence lifecycle) implements ClientMessage {
        public Drain { lifecycle = Objects.requireNonNull(lifecycle, "lifecycle"); }
        @Override public String type() { return "host.drain"; }
    }
    record Stop(LifecycleFence lifecycle) implements ClientMessage {
        public Stop { lifecycle = Objects.requireNonNull(lifecycle, "lifecycle"); }
        @Override public String type() { return "host.stop"; }
    }

    record LifecycleResult(LifecycleFence lifecycle, LifecycleOutcome outcome) implements ClientMessage {
        public LifecycleResult {
            lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
            outcome = Objects.requireNonNull(outcome, "outcome");
        }
        @Override public String type() { return "client.lifecycle-result"; }
    }

    record Observed(SessionFence session, ObservedState state) implements ClientMessage {
        public Observed {
            session = Objects.requireNonNull(session, "session");
            state = Objects.requireNonNull(state, "state");
        }
        @Override public String type() { return "client.observed"; }
    }

    record Call(CallFence call, String contributionKind, String contributionId) implements ClientMessage {
        public Call {
            call = Objects.requireNonNull(call, "call");
            contributionKind = required(contributionKind, "contributionKind");
            contributionId = required(contributionId, "contributionId");
        }
        @Override public String type() { return "client.call"; }
    }

    record CallResult(CallFence call, String contributionKind, String contributionId) implements ClientMessage {
        public CallResult {
            call = Objects.requireNonNull(call, "call");
            contributionKind = required(contributionKind, "contributionKind");
            contributionId = required(contributionId, "contributionId");
        }
        @Override public String type() { return "host.call-result"; }
    }

    record Detach(SessionFence session) implements ClientMessage {
        public Detach { session = Objects.requireNonNull(session, "session"); }
        @Override public String type() { return "client.detach"; }
    }

    enum LifecycleOutcome { APPLIED, FAILED }
    enum ObservedState { PENDING, ACTIVE, FAILED }

    private static String required(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
