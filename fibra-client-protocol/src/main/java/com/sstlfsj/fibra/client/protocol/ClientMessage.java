package com.sstlfsj.fibra.client.protocol;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.List;
import java.util.Map;
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
            capabilities = frozenStrings(capabilities, "capabilities");
        }
        @Override public String type() { return "client.hello"; }
    }

    record Welcome(SessionFence session) implements ClientMessage {
        public Welcome { session = Objects.requireNonNull(session, "session"); }
        @Override public String type() { return "host.welcome"; }
    }

    record Snapshot(SessionFence session, String viewRevision, long targetRevision, String targetDigest,
                    List<Assignment> assignments, List<Contribution> contributions) implements ClientMessage {
        public Snapshot {
            session = Objects.requireNonNull(session, "session");
            viewRevision = required(viewRevision, "viewRevision");
            if (targetRevision < 1) throw new IllegalArgumentException("targetRevision must be positive");
            targetDigest = digest(targetDigest, "targetDigest");
            assignments = List.copyOf(Objects.requireNonNull(assignments, "assignments"));
            contributions = List.copyOf(Objects.requireNonNull(contributions, "contributions"));
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

    record Observed(SessionFence session, List<ExecutionObservation> executions) implements ClientMessage {
        public Observed {
            session = Objects.requireNonNull(session, "session");
            executions = List.copyOf(Objects.requireNonNull(executions, "executions"));
        }
        @Override public String type() { return "client.observed"; }
    }

    record Call(CallFence call, String contributionKind, ContributionId contributionId, LiteralValue input)
        implements ClientMessage {
        public Call {
            call = Objects.requireNonNull(call, "call");
            contributionKind = required(contributionKind, "contributionKind");
            contributionId = Objects.requireNonNull(contributionId, "contributionId");
            input = Objects.requireNonNull(input, "input");
        }
        @Override public String type() { return "client.call"; }
    }

    record CallResult(CallFence call, String contributionKind, ContributionId contributionId,
                      CallOutcome outcome) implements ClientMessage {
        public CallResult {
            call = Objects.requireNonNull(call, "call");
            contributionKind = required(contributionKind, "contributionKind");
            contributionId = Objects.requireNonNull(contributionId, "contributionId");
            outcome = Objects.requireNonNull(outcome, "outcome");
        }
        @Override public String type() { return "host.call-result"; }
    }

    record Detach(SessionFence session) implements ClientMessage {
        public Detach { session = Objects.requireNonNull(session, "session"); }
        @Override public String type() { return "client.detach"; }
    }

    record Assignment(String pluginId, String facetId, String runtimeInstanceId, String executionTarget,
                      String entryModule, String payloadDigest, List<String> requiredCapabilities,
                      List<ResourceDescriptor> resources) {
        public Assignment {
            pluginId = required(pluginId, "pluginId");
            facetId = required(facetId, "facetId");
            runtimeInstanceId = required(runtimeInstanceId, "runtimeInstanceId");
            executionTarget = required(executionTarget, "executionTarget");
            entryModule = required(entryModule, "entryModule");
            payloadDigest = digest(payloadDigest, "payloadDigest");
            requiredCapabilities = frozenStrings(requiredCapabilities, "requiredCapabilities");
            resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
            if (resources.stream().map(ResourceDescriptor::path).distinct().count() != resources.size()) {
                throw new IllegalArgumentException("resource paths must be unique within an assignment");
            }
            var entryPath = entryModule;
            if (resources.stream().noneMatch(resource -> resource.path().equals(entryPath))) {
                throw new IllegalArgumentException("entryModule must reference a resource in the assignment");
            }
        }
    }

    record ResourceDescriptor(String path, String digest, long byteLength) {
        public ResourceDescriptor {
            path = resourcePath(path);
            digest = ClientMessage.digest(digest, "digest");
            if (byteLength < 0) {
                throw new IllegalArgumentException("byteLength must be non-negative");
            }
        }
    }

    record Contribution(String contributionKind, ContributionId contributionId, long registrationIdentity) {
        public Contribution {
            contributionKind = required(contributionKind, "contributionKind");
            contributionId = Objects.requireNonNull(contributionId, "contributionId");
            if (registrationIdentity < 1) {
                throw new IllegalArgumentException("registrationIdentity must be positive");
            }
        }
    }

    record ContributionId(String providerInstanceId, String localName) {
        public ContributionId {
            providerInstanceId = required(providerInstanceId, "providerInstanceId");
            localName = required(localName, "localName");
        }
    }

    sealed interface LifecycleOutcome permits LifecycleOutcome.Applied, LifecycleOutcome.Failed {
        record Applied() implements LifecycleOutcome { }
        record Failed(Failure failure) implements LifecycleOutcome {
            public Failed { failure = Objects.requireNonNull(failure, "failure"); }
        }
    }

    sealed interface CallOutcome permits CallOutcome.Success, CallOutcome.Failed {
        record Success(LiteralValue value) implements CallOutcome {
            public Success { value = Objects.requireNonNull(value, "value"); }
        }
        record Failed(Failure failure) implements CallOutcome {
            public Failed { failure = Objects.requireNonNull(failure, "failure"); }
        }
    }

    record Failure(String code, String message, Map<String, String> diagnostics) {
        public Failure {
            code = required(code, "code");
            message = required(message, "message");
            diagnostics = Map.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
            if (diagnostics.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getKey().isBlank()
                || entry.getValue() == null || entry.getValue().isBlank())) {
                throw new IllegalArgumentException("diagnostics must contain non-blank entries");
            }
        }
    }

    record ExecutionObservation(long targetRevision, String runtimeInstanceId, String lifecycleOperationId,
                                ObservedState state, Failure failure) {
        public ExecutionObservation {
            if (targetRevision < 1) throw new IllegalArgumentException("targetRevision must be positive");
            runtimeInstanceId = required(runtimeInstanceId, "runtimeInstanceId");
            lifecycleOperationId = required(lifecycleOperationId, "lifecycleOperationId");
            state = Objects.requireNonNull(state, "state");
            if ((state == ObservedState.FAILED) != (failure != null)) {
                throw new IllegalArgumentException("only FAILED observations carry failure");
            }
        }
    }

    enum ObservedState { PENDING, ACTIVE, FAILED }

    private static List<String> frozenStrings(List<String> values, String name) {
        values = List.copyOf(Objects.requireNonNull(values, name));
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " must contain non-blank values");
        }
        return values;
    }

    private static String digest(String value, String name) {
        value = required(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
        }
        return value;
    }

    private static String required(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static String resourcePath(String value) {
        value = required(value, "path");
        if (value.startsWith("/") || value.contains("\\")) {
            throw new IllegalArgumentException("path must be a relative logical resource path");
        }
        var segments = value.split("/", -1);
        if (java.util.Arrays.stream(segments).anyMatch(segment -> segment.isEmpty()
            || segment.equals(".") || segment.equals(".."))) {
            throw new IllegalArgumentException("path must use normalized logical segments");
        }
        var first = segments[0];
        var colon = first.indexOf(':');
        if (colon > 0 && first.substring(0, colon).matches("[A-Za-z][A-Za-z0-9+.-]*")) {
            throw new IllegalArgumentException("path must not be a URI or host path");
        }
        return value;
    }
}
