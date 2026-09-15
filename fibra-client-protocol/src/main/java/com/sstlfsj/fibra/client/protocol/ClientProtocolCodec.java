package com.sstlfsj.fibra.client.protocol;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 严格编解码固定的 protocol v1 envelope，不承担任何 transport 责任。 */
public final class ClientProtocolCodec {
    public static final int VERSION = 1;
    public static final int MAX_ENVELOPE_BYTES = 1024 * 1024;

    private static final Set<String> ENVELOPE_FIELDS = Set.of(
        "protocolVersion", "messageId", "type", "payload");
    private static final Set<String> PHASE_IDENTITIES = Set.of("identity", "session", "lifecycle", "call");
    private final JsonMapper json;

    public ClientProtocolCodec() {
        var constraints = StreamReadConstraints.builder()
            .maxDocumentLength(MAX_ENVELOPE_BYTES)
            .maxNestingDepth(16)
            .maxStringLength(MAX_ENVELOPE_BYTES)
            .build();
        json = JsonMapper.builder(JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build())
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    }

    public ClientEnvelope decode(String wire) {
        return decode(Objects.requireNonNull(wire, "wire").getBytes(StandardCharsets.UTF_8));
    }

    public ClientEnvelope decode(byte[] wire) {
        Objects.requireNonNull(wire, "wire");
        if (wire.length > MAX_ENVELOPE_BYTES) throw malformed("envelope exceeds 1 MiB", null);
        try {
            return decodeEnvelope(json.readTree(wire));
        } catch (ProtocolException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw malformed("invalid JSON envelope", exception);
        }
    }

    public String encode(ClientEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        try {
            var bytes = json.writeValueAsBytes(encodeEnvelope(envelope));
            if (bytes.length > MAX_ENVELOPE_BYTES) throw malformed("envelope exceeds 1 MiB", null);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (ProtocolException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw malformed("cannot encode envelope", exception);
        }
    }

    /** 读取跨语言共用 fixtures 的 JSON 数组；数组元素本身仍是完整 wire envelope。 */
    public List<ClientEnvelope> decodeFixtures(String fixtures) {
        var bytes = Objects.requireNonNull(fixtures, "fixtures").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_ENVELOPE_BYTES) throw malformed("fixtures exceed 1 MiB", null);
        try {
            var root = json.readTree(bytes);
            if (!(root instanceof ArrayNode array)) throw malformed("fixtures must be an array", null);
            var result = new ArrayList<ClientEnvelope>(array.size());
            for (var item : array) result.add(decodeEnvelope(item));
            return List.copyOf(result);
        } catch (ProtocolException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw malformed("invalid fixtures", exception);
        }
    }

    private ClientEnvelope decodeEnvelope(JsonNode node) {
        var envelope = object(node, "envelope", ErrorCode.MALFORMED_MESSAGE);
        exactFields(envelope, ENVELOPE_FIELDS, ErrorCode.MALFORMED_MESSAGE);
        var version = integer(envelope, "protocolVersion", ErrorCode.MALFORMED_MESSAGE);
        if (version != VERSION) {
            throw new ProtocolException(ErrorCode.UNSUPPORTED_PROTOCOL, "protocolVersion must be 1", null);
        }
        var messageId = text(envelope, "messageId", ErrorCode.MALFORMED_MESSAGE);
        var type = text(envelope, "type", ErrorCode.MALFORMED_MESSAGE);
        var payload = object(envelope.get("payload"), "payload", ErrorCode.MALFORMED_MESSAGE);
        var message = switch (type) {
            case "client.hello" -> hello(payload);
            case "host.welcome" -> new ClientMessage.Welcome(sessionMessage(payload));
            case "host.snapshot" -> snapshot(payload);
            case "host.prepare" -> new ClientMessage.Prepare(lifecycleMessage(payload));
            case "host.activate" -> new ClientMessage.Activate(lifecycleMessage(payload));
            case "host.drain" -> new ClientMessage.Drain(lifecycleMessage(payload));
            case "host.stop" -> new ClientMessage.Stop(lifecycleMessage(payload));
            case "client.lifecycle-result" -> lifecycleResult(payload);
            case "client.observed" -> observed(payload);
            case "client.call" -> call(payload, false);
            case "host.call-result" -> call(payload, true);
            case "client.detach" -> new ClientMessage.Detach(sessionMessage(payload));
            default -> throw malformed("unknown message type " + type, null);
        };
        try {
            return new ClientEnvelope(version, messageId, type, message);
        } catch (IllegalArgumentException exception) {
            throw malformed("invalid envelope", exception);
        }
    }

    private ClientMessage.Hello hello(ObjectNode payload) {
        exactFields(payload, Set.of("identity", "executionTarget", "capabilities"), ErrorCode.INVALID_IDENTITY);
        var identity = object(payload.get("identity"), "identity", ErrorCode.INVALID_IDENTITY);
        exactFields(identity, Set.of("clientNonce"), ErrorCode.INVALID_IDENTITY);
        var capabilities = array(payload.get("capabilities"), "capabilities", ErrorCode.MALFORMED_MESSAGE);
        var values = new ArrayList<String>(capabilities.size());
        for (var capability : capabilities) {
            if (!capability.isTextual() || capability.textValue().isBlank()) {
                throw malformed("capabilities must contain non-blank strings", null);
            }
            values.add(capability.textValue());
        }
        try {
            return new ClientMessage.Hello(new HelloIdentity(text(identity, "clientNonce", ErrorCode.INVALID_IDENTITY)),
                text(payload, "executionTarget", ErrorCode.MALFORMED_MESSAGE), values);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentity("invalid hello identity", exception);
        }
    }

    private ClientMessage.Snapshot snapshot(ObjectNode payload) {
        exactFields(payload, Set.of("session", "viewRevision", "targetDigest"), ErrorCode.INVALID_IDENTITY);
        try {
            return new ClientMessage.Snapshot(session(payload.get("session")),
                longValue(payload, "viewRevision", ErrorCode.MALFORMED_MESSAGE),
                text(payload, "targetDigest", ErrorCode.MALFORMED_MESSAGE));
        } catch (IllegalArgumentException exception) {
            throw malformed("invalid snapshot", exception);
        }
    }

    private ClientMessage.LifecycleResult lifecycleResult(ObjectNode payload) {
        exactFields(payload, Set.of("lifecycle", "outcome"), ErrorCode.INVALID_IDENTITY);
        try {
            return new ClientMessage.LifecycleResult(lifecycle(payload.get("lifecycle")),
                enumValue(payload, "outcome", ClientMessage.LifecycleOutcome.class));
        } catch (IllegalArgumentException exception) {
            throw malformed("invalid lifecycle result", exception);
        }
    }

    private ClientMessage.Observed observed(ObjectNode payload) {
        exactFields(payload, Set.of("session", "state"), ErrorCode.INVALID_IDENTITY);
        try {
            return new ClientMessage.Observed(session(payload.get("session")),
                enumValue(payload, "state", ClientMessage.ObservedState.class));
        } catch (IllegalArgumentException exception) {
            throw malformed("invalid observed message", exception);
        }
    }

    private ClientMessage call(ObjectNode payload, boolean result) {
        exactFields(payload, Set.of("call", "contributionKind", "contributionId"), ErrorCode.INVALID_IDENTITY);
        try {
            var fence = call(payload.get("call"));
            var kind = text(payload, "contributionKind", ErrorCode.MALFORMED_MESSAGE);
            var id = text(payload, "contributionId", ErrorCode.MALFORMED_MESSAGE);
            return result ? new ClientMessage.CallResult(fence, kind, id)
                : new ClientMessage.Call(fence, kind, id);
        } catch (IllegalArgumentException exception) {
            throw malformed("invalid call", exception);
        }
    }

    private SessionFence sessionMessage(ObjectNode payload) {
        exactFields(payload, Set.of("session"), ErrorCode.INVALID_IDENTITY);
        return session(payload.get("session"));
    }

    private LifecycleFence lifecycleMessage(ObjectNode payload) {
        exactFields(payload, Set.of("lifecycle"), ErrorCode.INVALID_IDENTITY);
        return lifecycle(payload.get("lifecycle"));
    }

    private SessionFence session(JsonNode node) {
        var value = object(node, "session", ErrorCode.INVALID_IDENTITY);
        exactFields(value, Set.of("hostInstanceId", "clientExecutionId"), ErrorCode.INVALID_IDENTITY);
        try {
            return new SessionFence(text(value, "hostInstanceId", ErrorCode.INVALID_IDENTITY),
                text(value, "clientExecutionId", ErrorCode.INVALID_IDENTITY));
        } catch (IllegalArgumentException exception) {
            throw invalidIdentity("invalid session fence", exception);
        }
    }

    private LifecycleFence lifecycle(JsonNode node) {
        var value = object(node, "lifecycle", ErrorCode.INVALID_IDENTITY);
        exactFields(value, Set.of("session", "targetRevision", "runtimeInstanceId", "lifecycleOperationId"),
            ErrorCode.INVALID_IDENTITY);
        try {
            return new LifecycleFence(session(value.get("session")),
                longValue(value, "targetRevision", ErrorCode.INVALID_IDENTITY),
                text(value, "runtimeInstanceId", ErrorCode.INVALID_IDENTITY),
                text(value, "lifecycleOperationId", ErrorCode.INVALID_IDENTITY));
        } catch (IllegalArgumentException exception) {
            throw invalidIdentity("invalid lifecycle fence", exception);
        }
    }

    private CallFence call(JsonNode node) {
        var value = object(node, "call", ErrorCode.INVALID_IDENTITY);
        exactFields(value, Set.of("session", "expectedViewRevision", "registrationIdentity"),
            ErrorCode.INVALID_IDENTITY);
        try {
            return new CallFence(session(value.get("session")),
                longValue(value, "expectedViewRevision", ErrorCode.INVALID_IDENTITY),
                text(value, "registrationIdentity", ErrorCode.INVALID_IDENTITY));
        } catch (IllegalArgumentException exception) {
            throw invalidIdentity("invalid call fence", exception);
        }
    }

    private ObjectNode encodeEnvelope(ClientEnvelope envelope) {
        var result = json.createObjectNode();
        result.put("protocolVersion", envelope.protocolVersion());
        result.put("messageId", envelope.messageId());
        result.put("type", envelope.type());
        result.set("payload", encodeMessage(envelope.message()));
        return result;
    }

    private ObjectNode encodeMessage(ClientMessage message) {
        var payload = json.createObjectNode();
        switch (message) {
            case ClientMessage.Hello hello -> {
                payload.set("identity", helloIdentity(hello.identity()));
                payload.put("executionTarget", hello.executionTarget());
                var capabilities = payload.putArray("capabilities");
                hello.capabilities().forEach(capabilities::add);
            }
            case ClientMessage.Welcome welcome -> payload.set("session", session(welcome.session()));
            case ClientMessage.Snapshot snapshot -> {
                payload.set("session", session(snapshot.session()));
                payload.put("viewRevision", snapshot.viewRevision());
                payload.put("targetDigest", snapshot.targetDigest());
            }
            case ClientMessage.Prepare prepare -> payload.set("lifecycle", lifecycle(prepare.lifecycle()));
            case ClientMessage.Activate activate -> payload.set("lifecycle", lifecycle(activate.lifecycle()));
            case ClientMessage.Drain drain -> payload.set("lifecycle", lifecycle(drain.lifecycle()));
            case ClientMessage.Stop stop -> payload.set("lifecycle", lifecycle(stop.lifecycle()));
            case ClientMessage.LifecycleResult result -> {
                payload.set("lifecycle", lifecycle(result.lifecycle()));
                payload.put("outcome", result.outcome().name());
            }
            case ClientMessage.Observed observed -> {
                payload.set("session", session(observed.session()));
                payload.put("state", observed.state().name());
            }
            case ClientMessage.Call call -> callPayload(payload, call.call(), call.contributionKind(), call.contributionId());
            case ClientMessage.CallResult result -> callPayload(payload, result.call(), result.contributionKind(), result.contributionId());
            case ClientMessage.Detach detach -> payload.set("session", session(detach.session()));
        }
        return payload;
    }

    private static void callPayload(ObjectNode payload, CallFence fence, String kind, String id) {
        payload.set("call", call(fence));
        payload.put("contributionKind", kind);
        payload.put("contributionId", id);
    }

    private ObjectNode helloIdentity(HelloIdentity identity) {
        var value = json.createObjectNode();
        value.put("clientNonce", identity.clientNonce());
        return value;
    }

    private ObjectNode session(SessionFence fence) {
        var value = json.createObjectNode();
        value.put("hostInstanceId", fence.hostInstanceId());
        value.put("clientExecutionId", fence.clientExecutionId());
        return value;
    }

    private ObjectNode lifecycle(LifecycleFence fence) {
        var value = json.createObjectNode();
        value.set("session", session(fence.session()));
        value.put("targetRevision", fence.targetRevision());
        value.put("runtimeInstanceId", fence.runtimeInstanceId());
        value.put("lifecycleOperationId", fence.lifecycleOperationId());
        return value;
    }

    private static ObjectNode call(CallFence fence) {
        var value = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        value.set("session", tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
            .put("hostInstanceId", fence.session().hostInstanceId())
            .put("clientExecutionId", fence.session().clientExecutionId()));
        value.put("expectedViewRevision", fence.expectedViewRevision());
        value.put("registrationIdentity", fence.registrationIdentity());
        return value;
    }

    private static ObjectNode object(JsonNode node, String name, ErrorCode errorCode) {
        if (!(node instanceof ObjectNode value)) throw failure(errorCode, name + " must be an object", null);
        return value;
    }

    private static ArrayNode array(JsonNode node, String name, ErrorCode errorCode) {
        if (!(node instanceof ArrayNode value)) throw failure(errorCode, name + " must be an array", null);
        return value;
    }

    private static String text(ObjectNode object, String name, ErrorCode errorCode) {
        var value = object.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw failure(errorCode, name + " must be a non-blank string", null);
        }
        return value.textValue();
    }

    private static int integer(ObjectNode object, String name, ErrorCode errorCode) {
        var value = object.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw failure(errorCode, name + " must be an integer", null);
        }
        return value.intValue();
    }

    private static long longValue(ObjectNode object, String name, ErrorCode errorCode) {
        var value = object.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw failure(errorCode, name + " must be an integer", null);
        }
        return value.longValue();
    }

    private static <E extends Enum<E>> E enumValue(ObjectNode object, String name, Class<E> type) {
        var value = text(object, name, ErrorCode.MALFORMED_MESSAGE);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw malformed("invalid " + name, exception);
        }
    }

    private static void exactFields(ObjectNode object, Set<String> expected, ErrorCode errorCode) {
        var actual = new HashSet<String>();
        actual.addAll(object.propertyNames());
        if (actual.equals(expected)) return;
        if (errorCode == ErrorCode.INVALID_IDENTITY) {
            var expectedIdentity = expected.stream().filter(PHASE_IDENTITIES::contains).findFirst();
            if (expectedIdentity.isEmpty()) {
                throw invalidIdentity("invalid identity fields", null);
            }
            var hasWrongPhaseIdentity = actual.stream()
                .filter(PHASE_IDENTITIES::contains)
                .anyMatch(name -> !name.equals(expectedIdentity.get()));
            if (!actual.contains(expectedIdentity.get()) || hasWrongPhaseIdentity) {
                throw invalidIdentity("missing or mixed phase identity", null);
            }
        }
        throw failure(errorCode == ErrorCode.INVALID_IDENTITY ? ErrorCode.MALFORMED_MESSAGE : errorCode,
            "unexpected or missing fields", null);
    }

    private static ProtocolException malformed(String message, Throwable cause) {
        return failure(ErrorCode.MALFORMED_MESSAGE, message, cause);
    }

    private static ProtocolException invalidIdentity(String message, Throwable cause) {
        return failure(ErrorCode.INVALID_IDENTITY, message, cause);
    }

    private static ProtocolException failure(ErrorCode code, String message, Throwable cause) {
        return new ProtocolException(code, message, cause);
    }

    public enum ErrorCode {
        MALFORMED_MESSAGE,
        INVALID_IDENTITY,
        UNSUPPORTED_PROTOCOL,
        STALE_OPERATION
    }

    public static final class ProtocolException extends RuntimeException {
        private final ErrorCode code;

        ProtocolException(ErrorCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = Objects.requireNonNull(code, "code");
        }

        public ErrorCode code() {
            return code;
        }
    }

    /** 以完整 lifecycle fence 围住每个 runtime 的当前操作，拒绝迟到回复。 */
    public static final class LifecycleFenceTracker {
        private final Map<RuntimeKey, LifecycleFence> current = new HashMap<>();

        public void begin(LifecycleFence fence) {
            fence = Objects.requireNonNull(fence, "fence");
            current.put(new RuntimeKey(fence.session(), fence.runtimeInstanceId()), fence);
        }

        public void accept(LifecycleFence response) {
            response = Objects.requireNonNull(response, "response");
            var expected = current.get(new RuntimeKey(response.session(), response.runtimeInstanceId()));
            if (!response.equals(expected)) {
                throw new ProtocolException(ErrorCode.STALE_OPERATION,
                    "lifecycle response does not match the current operation", null);
            }
        }

        private record RuntimeKey(SessionFence session, String runtimeInstanceId) { }
    }
}
