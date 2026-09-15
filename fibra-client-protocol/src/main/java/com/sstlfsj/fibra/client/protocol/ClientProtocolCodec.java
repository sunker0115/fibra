package com.sstlfsj.fibra.client.protocol;

import com.sstlfsj.fibra.value.LiteralValue;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 严格编解码固定的 protocol v1 envelope，不承担任何 transport 责任。 */
public final class ClientProtocolCodec {
    public static final int VERSION = 1;
    public static final int MAX_ENVELOPE_BYTES = 1024 * 1024;
    public static final int MAX_NESTING_DEPTH = 64;
    public static final int MAX_DECIMAL_CHARACTERS = 1000;

    private static final Set<String> ENVELOPE_FIELDS = Set.of(
        "protocolVersion", "messageId", "type", "payload");
    private static final Set<String> PHASE_IDENTITIES = Set.of("identity", "session", "lifecycle", "call");
    private final JsonMapper json;

    public ClientProtocolCodec() {
        var constraints = StreamReadConstraints.builder()
            .maxNestingDepth(MAX_NESTING_DEPTH)
            .maxStringLength(MAX_ENVELOPE_BYTES)
            .maxNumberLength(MAX_DECIMAL_CHARACTERS)
            .build();
        var writeConstraints = StreamWriteConstraints.builder()
            .maxNestingDepth(MAX_NESTING_DEPTH)
            .build();
        json = JsonMapper.builder(JsonFactory.builder()
                .streamReadConstraints(constraints)
                .streamWriteConstraints(writeConstraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build())
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .build();
    }

    public ClientEnvelope decode(String wire) {
        return decode(Objects.requireNonNull(wire, "wire").getBytes(StandardCharsets.UTF_8));
    }

    public ClientEnvelope decode(byte[] wire) {
        Objects.requireNonNull(wire, "wire");
        enforceSize(wire.length);
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
            enforceSize(bytes.length);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (ProtocolException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw malformed("cannot encode envelope", exception);
        }
    }

    /** 读取跨语言共用 fixtures；文件本身不适用单一 envelope 的 1 MiB 上限。 */
    public List<ClientEnvelope> decodeFixtures(String fixtures) {
        try {
            var root = json.readTree(Objects.requireNonNull(fixtures, "fixtures"));
            if (!(root instanceof ArrayNode array)) throw malformed("fixtures must be an array", null);
            var result = new ArrayList<ClientEnvelope>(array.size());
            for (var item : array) {
                enforceSize(json.writeValueAsBytes(item).length);
                result.add(decodeEnvelope(item));
            }
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
        var type = text(envelope, "type", ErrorCode.MALFORMED_MESSAGE);
        var payload = object(envelope.get("payload"), "payload", ErrorCode.MALFORMED_MESSAGE);
        var message = switch (type) {
            case "client.hello" -> hello(payload);
            case "host.welcome" -> new ClientMessage.Welcome(sessionPayload(payload));
            case "host.snapshot" -> snapshot(payload);
            case "host.prepare" -> new ClientMessage.Prepare(lifecyclePayload(payload));
            case "host.activate" -> new ClientMessage.Activate(lifecyclePayload(payload));
            case "host.drain" -> new ClientMessage.Drain(lifecyclePayload(payload));
            case "host.stop" -> new ClientMessage.Stop(lifecyclePayload(payload));
            case "client.lifecycle-result" -> lifecycleResult(payload);
            case "client.observed" -> observed(payload);
            case "client.call" -> call(payload);
            case "host.call-result" -> callResult(payload);
            case "client.detach" -> new ClientMessage.Detach(sessionPayload(payload));
            default -> throw malformed("unknown message type " + type, null);
        };
        try {
            return new ClientEnvelope(version, text(envelope, "messageId", ErrorCode.MALFORMED_MESSAGE), type, message);
        } catch (IllegalArgumentException exception) {
            throw malformed("invalid envelope", exception);
        }
    }

    private ClientMessage.Hello hello(ObjectNode payload) {
        phasePayload(payload, "identity", Set.of("identity", "executionTarget", "capabilities"));
        var identity = object(payload.get("identity"), "identity", ErrorCode.INVALID_IDENTITY);
        exactFields(identity, Set.of("clientNonce"), ErrorCode.INVALID_IDENTITY);
        return new ClientMessage.Hello(new HelloIdentity(text(identity, "clientNonce", ErrorCode.INVALID_IDENTITY)),
            text(payload, "executionTarget", ErrorCode.MALFORMED_MESSAGE), strings(payload.get("capabilities"), "capabilities"));
    }

    private ClientMessage.Snapshot snapshot(ObjectNode payload) {
        phasePayload(payload, "session", Set.of("session", "viewRevision", "targetRevision", "targetDigest", "assignments", "contributions"));
        try {
            return new ClientMessage.Snapshot(session(payload.get("session")),
                text(payload, "viewRevision", ErrorCode.MALFORMED_MESSAGE),
                canonicalPositiveLong(payload, "targetRevision", ErrorCode.MALFORMED_MESSAGE),
                digest(payload, "targetDigest"), assignments(payload.get("assignments")), contributions(payload.get("contributions")));
        } catch (IllegalArgumentException exception) {
            throw malformed("invalid snapshot", exception);
        }
    }

    private ClientMessage.LifecycleResult lifecycleResult(ObjectNode payload) {
        phasePayload(payload, "lifecycle", Set.of("lifecycle", "outcome"));
        return new ClientMessage.LifecycleResult(lifecycle(payload.get("lifecycle")), lifecycleOutcome(payload.get("outcome")));
    }

    private ClientMessage.Observed observed(ObjectNode payload) {
        phasePayload(payload, "session", Set.of("session", "executions"));
        return new ClientMessage.Observed(session(payload.get("session")), executions(payload.get("executions")));
    }

    private ClientMessage.Call call(ObjectNode payload) {
        phasePayload(payload, "call", Set.of("call", "contributionKind", "contributionId", "input"));
        return new ClientMessage.Call(call(payload.get("call")), text(payload, "contributionKind", ErrorCode.MALFORMED_MESSAGE),
            contributionId(payload.get("contributionId")), literal(payload.get("input"), 3));
    }

    private ClientMessage.CallResult callResult(ObjectNode payload) {
        phasePayload(payload, "call", Set.of("call", "contributionKind", "contributionId", "outcome"));
        return new ClientMessage.CallResult(call(payload.get("call")), text(payload, "contributionKind", ErrorCode.MALFORMED_MESSAGE),
            contributionId(payload.get("contributionId")), callOutcome(payload.get("outcome")));
    }

    private SessionFence sessionPayload(ObjectNode payload) {
        phasePayload(payload, "session", Set.of("session"));
        return session(payload.get("session"));
    }

    private LifecycleFence lifecyclePayload(ObjectNode payload) {
        phasePayload(payload, "lifecycle", Set.of("lifecycle"));
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
        exactFields(value, Set.of("session", "targetRevision", "runtimeInstanceId", "lifecycleOperationId"), ErrorCode.INVALID_IDENTITY);
        try {
            return new LifecycleFence(session(value.get("session")), canonicalPositiveLong(value, "targetRevision", ErrorCode.INVALID_IDENTITY),
                text(value, "runtimeInstanceId", ErrorCode.INVALID_IDENTITY), text(value, "lifecycleOperationId", ErrorCode.INVALID_IDENTITY));
        } catch (IllegalArgumentException exception) {
            throw invalidIdentity("invalid lifecycle fence", exception);
        }
    }

    private CallFence call(JsonNode node) {
        var value = object(node, "call", ErrorCode.INVALID_IDENTITY);
        exactFields(value, Set.of("session", "expectedViewRevision", "registrationIdentity"), ErrorCode.INVALID_IDENTITY);
        try {
            return new CallFence(session(value.get("session")), text(value, "expectedViewRevision", ErrorCode.INVALID_IDENTITY),
                canonicalPositiveLong(value, "registrationIdentity", ErrorCode.INVALID_IDENTITY));
        } catch (IllegalArgumentException exception) {
            throw invalidIdentity("invalid call fence", exception);
        }
    }

    private List<ClientMessage.Assignment> assignments(JsonNode node) {
        var array = array(node, "assignments", ErrorCode.MALFORMED_MESSAGE);
        var result = new ArrayList<ClientMessage.Assignment>(array.size());
        for (var item : array) {
            var value = object(item, "assignment", ErrorCode.MALFORMED_MESSAGE);
            exactFields(value, Set.of("pluginId", "facetId", "runtimeInstanceId", "executionTarget", "entryModule", "payloadDigest", "requiredCapabilities", "resources"), ErrorCode.MALFORMED_MESSAGE);
            result.add(new ClientMessage.Assignment(text(value, "pluginId", ErrorCode.MALFORMED_MESSAGE), text(value, "facetId", ErrorCode.MALFORMED_MESSAGE),
                text(value, "runtimeInstanceId", ErrorCode.MALFORMED_MESSAGE), text(value, "executionTarget", ErrorCode.MALFORMED_MESSAGE),
                text(value, "entryModule", ErrorCode.MALFORMED_MESSAGE), digest(value, "payloadDigest"),
                strings(value.get("requiredCapabilities"), "requiredCapabilities"), resources(value.get("resources"))));
        }
        return List.copyOf(result);
    }

    private List<ClientMessage.Resource> resources(JsonNode node) {
        var array = array(node, "resources", ErrorCode.MALFORMED_MESSAGE);
        var result = new ArrayList<ClientMessage.Resource>(array.size());
        for (var item : array) {
            var value = object(item, "resource", ErrorCode.MALFORMED_MESSAGE);
            exactFields(value, Set.of("path", "digest", "content"), ErrorCode.MALFORMED_MESSAGE);
            result.add(new ClientMessage.Resource(text(value, "path", ErrorCode.MALFORMED_MESSAGE), digest(value, "digest"), resourceContent(value.get("content"))));
        }
        return List.copyOf(result);
    }

    private ClientMessage.ResourceContent resourceContent(JsonNode node) {
        var value = object(node, "content", ErrorCode.MALFORMED_MESSAGE);
        var kind = text(value, "kind", ErrorCode.MALFORMED_MESSAGE);
        return switch (kind) {
            case "URL" -> {
                exactFields(value, Set.of("kind", "url"), ErrorCode.MALFORMED_MESSAGE);
                yield new ClientMessage.UrlContent(text(value, "url", ErrorCode.MALFORMED_MESSAGE));
            }
            case "BYTES" -> {
                exactFields(value, Set.of("kind", "base64"), ErrorCode.MALFORMED_MESSAGE);
                try {
                    yield new ClientMessage.BytesContent(textual(value, "base64", ErrorCode.MALFORMED_MESSAGE));
                } catch (IllegalArgumentException exception) {
                    throw malformed("invalid base64 resource", exception);
                }
            }
            default -> throw malformed("invalid resource content kind", null);
        };
    }

    private List<ClientMessage.Contribution> contributions(JsonNode node) {
        var array = array(node, "contributions", ErrorCode.MALFORMED_MESSAGE);
        var result = new ArrayList<ClientMessage.Contribution>(array.size());
        for (var item : array) {
            var value = object(item, "contribution", ErrorCode.MALFORMED_MESSAGE);
            exactFields(value, Set.of("contributionKind", "contributionId", "registrationIdentity"), ErrorCode.MALFORMED_MESSAGE);
            result.add(new ClientMessage.Contribution(text(value, "contributionKind", ErrorCode.MALFORMED_MESSAGE),
                contributionId(value.get("contributionId")), canonicalPositiveLong(value, "registrationIdentity", ErrorCode.MALFORMED_MESSAGE)));
        }
        return List.copyOf(result);
    }

    private ClientMessage.ContributionId contributionId(JsonNode node) {
        var value = object(node, "contributionId", ErrorCode.MALFORMED_MESSAGE);
        exactFields(value, Set.of("providerInstanceId", "localName"), ErrorCode.MALFORMED_MESSAGE);
        return new ClientMessage.ContributionId(text(value, "providerInstanceId", ErrorCode.MALFORMED_MESSAGE), text(value, "localName", ErrorCode.MALFORMED_MESSAGE));
    }

    private ClientMessage.LifecycleOutcome lifecycleOutcome(JsonNode node) {
        var value = object(node, "outcome", ErrorCode.MALFORMED_MESSAGE);
        return switch (text(value, "kind", ErrorCode.MALFORMED_MESSAGE)) {
            case "APPLIED" -> { exactFields(value, Set.of("kind"), ErrorCode.MALFORMED_MESSAGE); yield new ClientMessage.LifecycleOutcome.Applied(); }
            case "FAILED" -> { exactFields(value, Set.of("kind", "failure"), ErrorCode.MALFORMED_MESSAGE); yield new ClientMessage.LifecycleOutcome.Failed(failure(value.get("failure"))); }
            default -> throw malformed("invalid lifecycle outcome kind", null);
        };
    }

    private ClientMessage.CallOutcome callOutcome(JsonNode node) {
        var value = object(node, "outcome", ErrorCode.MALFORMED_MESSAGE);
        return switch (text(value, "kind", ErrorCode.MALFORMED_MESSAGE)) {
            case "SUCCESS" -> { exactFields(value, Set.of("kind", "value"), ErrorCode.MALFORMED_MESSAGE); yield new ClientMessage.CallOutcome.Success(literal(value.get("value"), 4)); }
            case "FAILED" -> { exactFields(value, Set.of("kind", "failure"), ErrorCode.MALFORMED_MESSAGE); yield new ClientMessage.CallOutcome.Failed(failure(value.get("failure"))); }
            default -> throw malformed("invalid call outcome kind", null);
        };
    }

    private ClientMessage.Failure failure(JsonNode node) {
        var value = object(node, "failure", ErrorCode.MALFORMED_MESSAGE);
        exactFields(value, Set.of("code", "message", "diagnostics"), ErrorCode.MALFORMED_MESSAGE);
        var diagnostics = object(value.get("diagnostics"), "diagnostics", ErrorCode.MALFORMED_MESSAGE);
        var result = new HashMap<String, String>();
        diagnostics.properties().forEach(entry -> {
            if (!entry.getValue().isTextual() || entry.getValue().textValue().isBlank()) throw malformed("diagnostics values must be non-blank strings", null);
            result.put(entry.getKey(), entry.getValue().textValue());
        });
        return new ClientMessage.Failure(text(value, "code", ErrorCode.MALFORMED_MESSAGE), text(value, "message", ErrorCode.MALFORMED_MESSAGE), result);
    }

    private List<ClientMessage.ExecutionObservation> executions(JsonNode node) {
        var array = array(node, "executions", ErrorCode.MALFORMED_MESSAGE);
        var result = new ArrayList<ClientMessage.ExecutionObservation>(array.size());
        for (var item : array) {
            var value = object(item, "execution", ErrorCode.MALFORMED_MESSAGE);
            var state = enumValue(value, "state", ClientMessage.ObservedState.class);
            exactFields(value, state == ClientMessage.ObservedState.FAILED
                ? Set.of("targetRevision", "runtimeInstanceId", "lifecycleOperationId", "state", "failure")
                : Set.of("targetRevision", "runtimeInstanceId", "lifecycleOperationId", "state"), ErrorCode.MALFORMED_MESSAGE);
            result.add(new ClientMessage.ExecutionObservation(canonicalPositiveLong(value, "targetRevision", ErrorCode.MALFORMED_MESSAGE),
                text(value, "runtimeInstanceId", ErrorCode.MALFORMED_MESSAGE), text(value, "lifecycleOperationId", ErrorCode.MALFORMED_MESSAGE), state,
                state == ClientMessage.ObservedState.FAILED ? failure(value.get("failure")) : null));
        }
        return List.copyOf(result);
    }

    private LiteralValue literal(JsonNode node, int depth) {
        if (node == null) throw malformed("literal must be present", null);
        if (node.isNull()) return LiteralValue.NullValue.INSTANCE;
        if (node.isBoolean()) return new LiteralValue.BooleanValue(node.booleanValue());
        if (node.isTextual()) return new LiteralValue.StringValue(node.textValue());
        if (node.isNumber()) throw malformed("literal numbers require the NUMBER tag", null);
        if (node instanceof ArrayNode array) {
            enforceDepth(depth);
            var result = new ArrayList<LiteralValue>(array.size());
            for (var item : array) result.add(literal(item, depth + 1));
            return new LiteralValue.ListValue(result);
        }
        if (node instanceof ObjectNode object) {
            enforceDepth(depth);
            var kind = text(object, "kind", ErrorCode.MALFORMED_MESSAGE);
            if (kind.equals("NUMBER")) return taggedNumber(object);
            if (kind.equals("OBJECT")) return taggedObject(object, depth);
            throw malformed("invalid literal kind", null);
        }
        throw malformed("unsupported literal", null);
    }

    private LiteralValue.NumberValue taggedNumber(ObjectNode object) {
        exactFields(object, Set.of("kind", "value"), ErrorCode.MALFORMED_MESSAGE);
        var wireValue = textual(object, "value", ErrorCode.MALFORMED_MESSAGE);
        if (wireValue.length() > MAX_DECIMAL_CHARACTERS) {
            throw malformed("NUMBER value exceeds character limit", null);
        }
        try {
            var number = new LiteralValue.NumberValue(new BigDecimal(wireValue));
            if (!wireValue.equals(number.value().toString())) {
                throw malformed("NUMBER value must be canonical", null);
            }
            return number;
        } catch (NumberFormatException exception) {
            throw malformed("NUMBER value must be a decimal", exception);
        }
    }

    private LiteralValue.ObjectValue taggedObject(ObjectNode object, int depth) {
        exactFields(object, Set.of("kind", "values"), ErrorCode.MALFORMED_MESSAGE);
        var values = object(object.get("values"), "values", ErrorCode.MALFORMED_MESSAGE);
        enforceDepth(depth + 1);
        var result = new HashMap<String, LiteralValue>();
        values.properties().forEach(entry -> result.put(entry.getKey(), literal(entry.getValue(), depth + 2)));
        return new LiteralValue.ObjectValue(result);
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
            case ClientMessage.Hello value -> {
                payload.set("identity", helloIdentity(value.identity()));
                payload.put("executionTarget", value.executionTarget());
                strings(payload, "capabilities", value.capabilities());
            }
            case ClientMessage.Welcome value -> payload.set("session", session(value.session()));
            case ClientMessage.Snapshot value -> {
                payload.set("session", session(value.session()));
                payload.put("viewRevision", value.viewRevision());
                payload.put("targetRevision", Long.toString(value.targetRevision()));
                payload.put("targetDigest", value.targetDigest());
                assignments(payload, value.assignments());
                contributions(payload, value.contributions());
            }
            case ClientMessage.Prepare value -> payload.set("lifecycle", lifecycle(value.lifecycle()));
            case ClientMessage.Activate value -> payload.set("lifecycle", lifecycle(value.lifecycle()));
            case ClientMessage.Drain value -> payload.set("lifecycle", lifecycle(value.lifecycle()));
            case ClientMessage.Stop value -> payload.set("lifecycle", lifecycle(value.lifecycle()));
            case ClientMessage.LifecycleResult value -> {
                payload.set("lifecycle", lifecycle(value.lifecycle()));
                payload.set("outcome", lifecycleOutcome(value.outcome()));
            }
            case ClientMessage.Observed value -> {
                payload.set("session", session(value.session()));
                executions(payload, value.executions());
            }
            case ClientMessage.Call value -> {
                callPayload(payload, value.call(), value.contributionKind(), value.contributionId());
                payload.set("input", literal(value.input(), 3));
            }
            case ClientMessage.CallResult value -> {
                callPayload(payload, value.call(), value.contributionKind(), value.contributionId());
                payload.set("outcome", callOutcome(value.outcome()));
            }
            case ClientMessage.Detach value -> payload.set("session", session(value.session()));
        }
        return payload;
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
        value.put("targetRevision", Long.toString(fence.targetRevision()));
        value.put("runtimeInstanceId", fence.runtimeInstanceId());
        value.put("lifecycleOperationId", fence.lifecycleOperationId());
        return value;
    }

    private ObjectNode call(CallFence fence) {
        var value = json.createObjectNode();
        value.set("session", session(fence.session()));
        value.put("expectedViewRevision", fence.expectedViewRevision());
        value.put("registrationIdentity", Long.toString(fence.registrationIdentity()));
        return value;
    }

    private void callPayload(ObjectNode payload, CallFence fence, String kind, ClientMessage.ContributionId id) {
        payload.set("call", call(fence));
        payload.put("contributionKind", kind);
        payload.set("contributionId", contributionId(id));
    }

    private ObjectNode contributionId(ClientMessage.ContributionId id) {
        var value = json.createObjectNode();
        value.put("providerInstanceId", id.providerInstanceId());
        value.put("localName", id.localName());
        return value;
    }
    private void strings(ObjectNode object, String name, List<String> values) { var array = object.putArray(name); values.forEach(array::add); }
    private List<String> strings(JsonNode node, String name) { var array = array(node, name, ErrorCode.MALFORMED_MESSAGE); var values = new ArrayList<String>(array.size()); for (var item : array) { if (!item.isTextual() || item.textValue().isBlank()) throw malformed(name + " must contain non-blank strings", null); values.add(item.textValue()); } return List.copyOf(values); }
    private void assignments(ObjectNode payload, List<ClientMessage.Assignment> values) { var array = payload.putArray("assignments"); for (var value : values) { var item = array.addObject(); item.put("pluginId", value.pluginId()); item.put("facetId", value.facetId()); item.put("runtimeInstanceId", value.runtimeInstanceId()); item.put("executionTarget", value.executionTarget()); item.put("entryModule", value.entryModule()); item.put("payloadDigest", value.payloadDigest()); strings(item, "requiredCapabilities", value.requiredCapabilities()); var resources = item.putArray("resources"); for (var resource : value.resources()) { var node = resources.addObject(); node.put("path", resource.path()); node.put("digest", resource.digest()); node.set("content", resourceContent(resource.content())); } } }
    private ObjectNode resourceContent(ClientMessage.ResourceContent content) { var value = json.createObjectNode(); value.put("kind", content.kind()); switch (content) { case ClientMessage.UrlContent url -> value.put("url", url.url()); case ClientMessage.BytesContent bytes -> value.put("base64", bytes.base64()); } return value; }
    private void contributions(ObjectNode payload, List<ClientMessage.Contribution> values) { var array = payload.putArray("contributions"); for (var value : values) { var node = array.addObject(); node.put("contributionKind", value.contributionKind()); node.set("contributionId", contributionId(value.contributionId())); node.put("registrationIdentity", Long.toString(value.registrationIdentity())); } }
    private ObjectNode lifecycleOutcome(ClientMessage.LifecycleOutcome outcome) { var value = json.createObjectNode(); switch (outcome) { case ClientMessage.LifecycleOutcome.Applied ignored -> value.put("kind", "APPLIED"); case ClientMessage.LifecycleOutcome.Failed failed -> { value.put("kind", "FAILED"); value.set("failure", failure(failed.failure())); } } return value; }
    private ObjectNode callOutcome(ClientMessage.CallOutcome outcome) {
        var value = json.createObjectNode();
        switch (outcome) {
            case ClientMessage.CallOutcome.Success success -> {
                value.put("kind", "SUCCESS");
                value.set("value", literal(success.value(), 4));
            }
            case ClientMessage.CallOutcome.Failed failed -> {
                value.put("kind", "FAILED");
                value.set("failure", failure(failed.failure()));
            }
        }
        return value;
    }
    private ObjectNode failure(ClientMessage.Failure failure) { var value = json.createObjectNode(); value.put("code", failure.code()); value.put("message", failure.message()); var diagnostics = value.putObject("diagnostics"); failure.diagnostics().forEach(diagnostics::put); return value; }
    private void executions(ObjectNode payload, List<ClientMessage.ExecutionObservation> values) { var array = payload.putArray("executions"); for (var value : values) { var node = array.addObject(); node.put("targetRevision", Long.toString(value.targetRevision())); node.put("runtimeInstanceId", value.runtimeInstanceId()); node.put("lifecycleOperationId", value.lifecycleOperationId()); node.put("state", value.state().name()); if (value.failure() != null) node.set("failure", failure(value.failure())); } }
    private JsonNode literal(LiteralValue value, int depth) {
        return switch (value) {
            case LiteralValue.NullValue ignored -> json.getNodeFactory().nullNode();
            case LiteralValue.BooleanValue scalar -> json.getNodeFactory().booleanNode(scalar.value());
            case LiteralValue.StringValue scalar -> json.getNodeFactory().stringNode(scalar.value());
            case LiteralValue.NumberValue scalar -> {
                if (scalar.value().precision() > MAX_DECIMAL_CHARACTERS) {
                    throw malformed("NUMBER value exceeds character limit", null);
                }
                enforceDepth(depth);
                var wireValue = scalar.value().toString();
                if (wireValue.length() > MAX_DECIMAL_CHARACTERS) {
                    throw malformed("NUMBER value exceeds character limit", null);
                }
                var result = json.createObjectNode();
                result.put("kind", "NUMBER");
                result.put("value", wireValue);
                yield result;
            }
            case LiteralValue.ListValue list -> {
                enforceDepth(depth);
                var array = json.createArrayNode();
                list.values().forEach(item -> array.add(literal(item, depth + 1)));
                yield array;
            }
            case LiteralValue.ObjectValue object -> {
                enforceDepth(depth);
                var result = json.createObjectNode();
                result.put("kind", "OBJECT");
                var values = result.putObject("values");
                enforceDepth(depth + 1);
                object.values().forEach((key, item) -> values.set(key, literal(item, depth + 2)));
                yield result;
            }
        };
    }

    private static void enforceSize(int length) { if (length > MAX_ENVELOPE_BYTES) throw malformed("envelope exceeds 1 MiB", null); }
    private static ObjectNode object(JsonNode node, String name, ErrorCode code) { if (!(node instanceof ObjectNode value)) throw failure(code, name + " must be an object", null); return value; }
    private static ArrayNode array(JsonNode node, String name, ErrorCode code) { if (!(node instanceof ArrayNode value)) throw failure(code, name + " must be an array", null); return value; }
    private static String text(ObjectNode object, String name, ErrorCode code) { var value = object.get(name); if (value == null || !value.isTextual() || value.textValue().isBlank()) throw failure(code, name + " must be a non-blank string", null); return value.textValue(); }
    private static String textual(ObjectNode object, String name, ErrorCode code) { var value = object.get(name); if (value == null || !value.isTextual()) throw failure(code, name + " must be a string", null); return value.textValue(); }
    private static int integer(ObjectNode object, String name, ErrorCode code) { var value = object.get(name); if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw failure(code, name + " must be an integer", null); return value.intValue(); }
    private static long canonicalPositiveLong(ObjectNode object, String name, ErrorCode code) {
        var value = textual(object, name, code);
        if (!value.matches("[1-9][0-9]*")) {
            throw failure(code, name + " must be a canonical positive decimal string", null);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw failure(code, name + " exceeds signed long range", exception);
        }
    }
    private static void enforceDepth(int depth) {
        if (depth > MAX_NESTING_DEPTH) throw malformed("literal exceeds nesting depth", null);
    }
    private static String digest(ObjectNode object, String name) { var value = text(object, name, ErrorCode.MALFORMED_MESSAGE); if (!value.matches("[0-9a-f]{64}")) throw malformed(name + " must be a lowercase SHA-256 digest", null); return value; }
    private static <E extends Enum<E>> E enumValue(ObjectNode object, String name, Class<E> type) { try { return Enum.valueOf(type, text(object, name, ErrorCode.MALFORMED_MESSAGE)); } catch (IllegalArgumentException exception) { throw malformed("invalid " + name, exception); } }
    private static void exactFields(ObjectNode object, Set<String> expected, ErrorCode code) { if (!object.propertyNames().equals(expected)) throw failure(code, "unexpected or missing fields", null); }
    private static void phasePayload(ObjectNode payload, String identity, Set<String> expected) { if (payload.propertyNames().equals(expected)) return; var properties = payload.propertyNames(); if (!properties.contains(identity) || properties.stream().anyMatch(PHASE_IDENTITIES::contains) && properties.stream().anyMatch(name -> PHASE_IDENTITIES.contains(name) && !name.equals(identity))) throw invalidIdentity("missing or mixed phase identity", null); throw malformed("unexpected or missing payload fields", null); }
    private static ProtocolException malformed(String message, Throwable cause) { return failure(ErrorCode.MALFORMED_MESSAGE, message, cause); }
    private static ProtocolException invalidIdentity(String message, Throwable cause) { return failure(ErrorCode.INVALID_IDENTITY, message, cause); }
    private static ProtocolException failure(ErrorCode code, String message, Throwable cause) { return new ProtocolException(code, message, cause); }

    public enum ErrorCode { MALFORMED_MESSAGE, INVALID_IDENTITY, UNSUPPORTED_PROTOCOL, STALE_OPERATION }
    public static final class ProtocolException extends RuntimeException { private final ErrorCode code; ProtocolException(ErrorCode code, String message, Throwable cause) { super(message, cause); this.code = Objects.requireNonNull(code, "code"); } public ErrorCode code() { return code; } }

    /** 每次成功接收都会原子消费 pending operation，迟到或重复回复均被拒绝。 */
    public static final class LifecycleFenceTracker {
        private final ConcurrentHashMap<RuntimeKey, LifecycleFence> current = new ConcurrentHashMap<>();
        public void begin(LifecycleFence fence) { fence = Objects.requireNonNull(fence, "fence"); current.put(new RuntimeKey(fence.session(), fence.runtimeInstanceId()), fence); }
        public void accept(LifecycleFence response) { response = Objects.requireNonNull(response, "response"); if (!current.remove(new RuntimeKey(response.session(), response.runtimeInstanceId()), response)) throw new ProtocolException(ErrorCode.STALE_OPERATION, "lifecycle response does not match the current operation", null); }
        private record RuntimeKey(SessionFence session, String runtimeInstanceId) { }
    }
}
