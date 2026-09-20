package com.sstlfsj.fibra.client.protocol;

import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientProtocolCodecTest {
    private final ClientProtocolCodec codec = new ClientProtocolCodec();

    @Test
    void rejectsStrictlyMalformedAndUnsupportedWires() {
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            "{\"protocolVersion\":1,\"messageId\":\"one\",\"type\":\"client.detach\","
                + "\"payload\":{\"session\":{\"hostInstanceId\":\"host\","
                + "\"clientExecutionId\":\"client\"},\"unexpected\":true}}" );
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            "{\"protocolVersion\":1,\"messageId\":\"one\",\"type\":\"client.observed\","
                + "\"payload\":{\"session\":{\"hostInstanceId\":\"host\","
                + "\"clientExecutionId\":\"client\"},\"executions\":[{\"unitTargetRevision\":1,"
                + "\"runtimeInstanceId\":\"runtime\",\"runtimeInstanceId\":\"duplicate\","
                + "\"lifecycleOperationId\":\"op\",\"state\":\"ACTIVE\"}]}}" );
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            "{\"protocolVersion\":1,\"messageId\":\"one\",\"type\":\"client.detach\","
                + "\"payload\":{\"session\":{\"hostInstanceId\":\"host\","
                + "\"clientExecutionId\":\"client\"}}} true");
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            "{\"protocolVersion\":1,\"messageId\":\"one\",\"type\":\"client.future\","
                + "\"payload\":{}}");
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            "{\"protocolVersion\":1,\"messageId\":\"one\",\"type\":\"host.snapshot\",\"payload\":{"
                + "\"session\":{\"hostInstanceId\":\"host\",\"clientExecutionId\":\"client\"},"
                + "\"viewRevision\":\"0\",\"targetRevision\":\"1\",\"targetDigest\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\","
                + "\"assignments\":[{\"pluginId\":\"plugin\",\"facetId\":\"facet\",\"desiredEntryId\":\"entry\",\"definitionId\":\"definition\",\"runtimeInstanceId\":\"runtime\",\"unitTargetRevision\":\"1\",\"executionTarget\":\"client:web\",\"config\":null,\"entryModule\":\"index.js\","
                + "\"payloadDigest\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\",\"requiredCapabilities\":[],"
                + "\"resources\":[{\"path\":\"index.js\",\"digest\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\",\"byteLength\":\"0\"}],"
                + "\"unknown\":true}],\"contributions\":[]}}");
        assertCode(ClientProtocolCodec.ErrorCode.UNSUPPORTED_PROTOCOL,
            "{\"protocolVersion\":2,\"messageId\":\"one\",\"type\":\"client.hello\","
                + "\"payload\":{\"identity\":{\"clientNonce\":\"nonce\"},"
                + "\"executionTarget\":\"client:web\",\"capabilities\":[]}}");
    }

    @Test
    void rejectsMissingAndMixedIdentitiesAtEveryDepth() {
        assertCode(ClientProtocolCodec.ErrorCode.INVALID_IDENTITY,
            "{\"protocolVersion\":1,\"messageId\":\"one\",\"type\":\"client.hello\","
                + "\"payload\":{\"executionTarget\":\"client:web\",\"capabilities\":[]}}");
        assertCode(ClientProtocolCodec.ErrorCode.INVALID_IDENTITY,
            "{\"protocolVersion\":1,\"messageId\":\"one\",\"type\":\"host.prepare\","
                + "\"payload\":{\"lifecycle\":{\"session\":{\"hostInstanceId\":\"host\"},"
                + "\"unitTargetRevision\":1,\"runtimeInstanceId\":\"runtime\","
                + "\"lifecycleOperationId\":\"operation\"}}}");
        assertCode(ClientProtocolCodec.ErrorCode.INVALID_IDENTITY,
            "{\"protocolVersion\":1,\"messageId\":\"one\",\"type\":\"client.hello\","
                + "\"payload\":{\"identity\":{\"clientNonce\":\"nonce\"},"
                + "\"executionTarget\":\"client:web\",\"capabilities\":[],"
                + "\"session\":{\"hostInstanceId\":\"host\",\"clientExecutionId\":\"client\"}}}");
        assertCode(ClientProtocolCodec.ErrorCode.INVALID_IDENTITY,
            "{\"protocolVersion\":1,\"messageId\":\"one\",\"type\":\"client.call\","
                + "\"payload\":{\"call\":{\"session\":{\"hostInstanceId\":\"host\","
                + "\"clientExecutionId\":\"client\"},\"expectedViewRevision\":\"0\","
                + "\"registrationIdentity\":9007199254740992},\"contributionKind\":\"tool\","
                + "\"contributionId\":{\"providerInstanceId\":\"provider\",\"localName\":\"read\"},"
                + "\"input\":null}}");
    }

    @Test
    void enforcesExactUtf8EnvelopeByteLimit() {
        var empty = callEnvelope(new LiteralValue.StringValue(""));
        var baseLength = codec.encode(empty).getBytes(StandardCharsets.UTF_8).length;
        var filler = "a".repeat(ClientProtocolCodec.MAX_ENVELOPE_BYTES - baseLength);
        var exact = callEnvelope(new LiteralValue.StringValue(filler));
        var exactWire = codec.encode(exact);

        assertEquals(ClientProtocolCodec.MAX_ENVELOPE_BYTES, exactWire.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(exact, codec.decode(exactWire));
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE, exactWire + " ");
        assertProtocolCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            () -> codec.encode(callEnvelope(new LiteralValue.StringValue(filler + "a"))));

        var oversizedResourceList = snapshotWithResources(java.util.stream.IntStream.range(0, 20_000)
            .mapToObj(index -> new ClientMessage.ResourceDescriptor("asset-" + index + ".js",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", 0))
            .toList(), "asset-0.js");
        assertProtocolCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            () -> codec.encode(oversizedResourceList));
    }

    @Test
    void roundTripsTaggedArbitraryPrecisionNumbersAndRejectsNativeNumbers() {
        for (var value : List.of("0.12345678901234567890123456789", "1E-400", "1E+400",
            "9223372036854775808")) {
            var envelope = callEnvelope(new LiteralValue.NumberValue(new BigDecimal(value)));
            var wire = codec.encode(envelope);
            assertEquals(envelope, codec.decode(wire));
            assertTrue(wire.contains("\"kind\":\"NUMBER\""));
            var number = (LiteralValue.NumberValue) ((ClientMessage.Call) envelope.message()).input();
            assertTrue(wire.contains("\"value\":\"" + number.value() + "\""));
        }
        var nullInput = codec.encode(callEnvelope(LiteralValue.NullValue.INSTANCE));
        for (var value : List.of("0", "0.1", "1E+400", "9223372036854775808")) {
            assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
                nullInput.replace("\"input\":null", "\"input\":" + value));
        }
        for (var input : List.of(
            "{\"kind\":\"NUMBER\",\"value\":1}",
            "{\"kind\":\"NUMBER\",\"value\":\"1.0\"}",
            "{\"kind\":\"NUMBER\",\"value\":\"not-a-number\"}",
            "{\"kind\":\"NUMBER\",\"value\":\"1\",\"extra\":true}",
            "{\"kind\":\"OBJECT\",\"values\":[]}",
            "{\"kind\":\"OBJECT\",\"values\":{},\"extra\":true}",
            "{\"kind\":\"UNKNOWN\",\"values\":{}}")) {
            assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
                nullInput.replace("\"input\":null", "\"input\":" + input));
        }

        var businessObject = new LiteralValue.ObjectValue(java.util.Map.of(
            "kind", new LiteralValue.StringValue("business"),
            "value", new LiteralValue.NumberValue(new BigDecimal("1E+400"))));
        var businessEnvelope = callEnvelope(businessObject);
        assertEquals(businessEnvelope, codec.decode(codec.encode(businessEnvelope)));
    }

    @Test
    void enforcesTaggedDecimalCharacterLimitBeforeParsing() {
        var boundary = "1".repeat(ClientProtocolCodec.MAX_DECIMAL_CHARACTERS);
        var boundaryEnvelope = callEnvelope(new LiteralValue.NumberValue(new BigDecimal(boundary)));
        assertEquals(boundaryEnvelope, codec.decode(codec.encode(boundaryEnvelope)));

        var tooLong = "1".repeat(ClientProtocolCodec.MAX_DECIMAL_CHARACTERS + 1);
        assertProtocolCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            () -> codec.encode(callEnvelope(new LiteralValue.NumberValue(new BigDecimal(tooLong)))));

        var nullInput = codec.encode(callEnvelope(LiteralValue.NullValue.INSTANCE));
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            taggedNumberWire(nullInput, tooLong));
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            taggedNumberWire(nullInput, "1" + "0".repeat(100_000)));
    }

    @Test
    void enforcesLiteralDepthBeforeTreeConversion() {
        var ordinary = callEnvelope(nestedList(4));
        assertEquals(ordinary, codec.decode(codec.encode(ordinary)));
        var boundary = callEnvelope(nestedList(62));
        assertEquals(boundary, codec.decode(codec.encode(boundary)));
        assertProtocolCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            () -> codec.encode(callEnvelope(nestedList(63))));

        var wire = codec.encode(callEnvelope(LiteralValue.NullValue.INSTANCE));
        var tooDeep = "[".repeat(63) + "null" + "]".repeat(63);
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            wire.replace("\"input\":null", "\"input\":" + tooDeep));

        var resultBoundary = callResultEnvelope(nestedList(61));
        assertEquals(resultBoundary, codec.decode(codec.encode(resultBoundary)));
        assertProtocolCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            () -> codec.encode(callResultEnvelope(nestedList(62))));
        var resultWire = codec.encode(callResultEnvelope(LiteralValue.NullValue.INSTANCE));
        var tooDeepResult = "[".repeat(62) + "null" + "]".repeat(62);
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            resultWire.replace("\"value\":null", "\"value\":" + tooDeepResult));
    }

    @Test
    void encodesIdentityIntegersAsCanonicalLongStrings() {
        var maximum = Long.MAX_VALUE;
        var lifecycle = new ClientEnvelope(1, "prepare", "host.prepare",
            new ClientMessage.Prepare(new LifecycleFence(new SessionFence("host", "client"), maximum,
                "runtime", "operation")));
        var session = new SessionFence("host", "client");
        var digest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        var snapshot = new ClientEnvelope(1, "snapshot", "host.snapshot", new ClientMessage.Snapshot(
            session, "0", maximum, digest, List.of(), List.of(new ClientMessage.Contribution("tool",
                new ClientMessage.ContributionId("provider", "read"), maximum))));
        var call = new ClientEnvelope(1, "call", "client.call", new ClientMessage.Call(
            new CallFence(session, "0", maximum), "tool", new ClientMessage.ContributionId("provider", "read"),
            LiteralValue.NullValue.INSTANCE));
        var observed = new ClientEnvelope(1, "observed", "client.observed", new ClientMessage.Observed(session,
            List.of(new ClientMessage.ExecutionObservation(maximum, "runtime", "operation",
                ClientMessage.ObservedState.ACTIVE, null))));
        for (var envelope : List.of(lifecycle, snapshot, call, observed)) {
            var wire = codec.encode(envelope);
            assertEquals(envelope, codec.decode(wire));
            assertTrue(wire.contains("\"9223372036854775807\""));
            assertFalse(wire.contains(":9223372036854775807"));
        }
        assertThrows(IllegalArgumentException.class,
            () -> new LifecycleFence(new SessionFence("host", "client"), 0, "runtime", "operation"));

        for (var invalid : List.of("1", "-1", "\"-1\"", "\"01\"", "\"9223372036854775808\"")) {
            var wire = codec.encode(lifecycle).replace("\"unitTargetRevision\":\"9223372036854775807\"",
                "\"unitTargetRevision\":" + invalid);
            assertCode(ClientProtocolCodec.ErrorCode.INVALID_IDENTITY, wire);
        }
    }

    @Test
    void keepsGlobalSnapshotRevisionAndHardCutsUnitRevisionFields() {
        var session = new SessionFence("host", "client");
        var lifecycle = new ClientEnvelope(1, "prepare", "host.prepare",
            new ClientMessage.Prepare(new LifecycleFence(session, 10, "runtime", "operation")));
        var observed = new ClientEnvelope(1, "observed", "client.observed", new ClientMessage.Observed(session,
            List.of(new ClientMessage.ExecutionObservation(9, "runtime", "operation",
                ClientMessage.ObservedState.ACTIVE, null))));
        var snapshot = new ClientEnvelope(1, "snapshot", "host.snapshot", new ClientMessage.Snapshot(
            session, "0", 11, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            List.of(), List.of()));

        var lifecycleWire = codec.encode(lifecycle);
        var observedWire = codec.encode(observed);
        var snapshotWire = codec.encode(snapshot);
        assertTrue(lifecycleWire.contains("\"unitTargetRevision\":\"10\""));
        assertTrue(observedWire.contains("\"unitTargetRevision\":\"9\""));
        assertTrue(snapshotWire.contains("\"targetRevision\":\"11\""));
        assertEquals(lifecycle, codec.decode(lifecycleWire));
        assertEquals(observed, codec.decode(observedWire));
        assertEquals(snapshot, codec.decode(snapshotWire));

        assertCode(ClientProtocolCodec.ErrorCode.INVALID_IDENTITY,
            lifecycleWire.replace("\"unitTargetRevision\"", "\"targetRevision\""));
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            observedWire.replace("\"unitTargetRevision\"", "\"targetRevision\""));
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            snapshotWire.replace("\"targetRevision\"", "\"unitTargetRevision\""));
    }

    @Test
    void assignmentsPreservePerEntryDefinitionAndResolvedConfigForSharedFacets() {
        var digest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        var resource = new ClientMessage.ResourceDescriptor("index.js", digest, 0);
        var first = new ClientMessage.Assignment("plugin", "client", "first", "main",
            "runtime-first", 5, "client:web", LiteralValue.of(java.util.Map.of("mode", "one")),
            "index.js", digest, List.of(), List.of(resource));
        var second = new ClientMessage.Assignment("plugin", "client", "second", "main",
            "runtime-second", 5, "client:web", LiteralValue.of(java.util.Map.of("mode", "two")),
            "index.js", digest, List.of(), List.of(resource));
        var envelope = new ClientEnvelope(1, "snapshot", "host.snapshot",
            new ClientMessage.Snapshot(new SessionFence("host", "client"), "0", 5, digest,
                List.of(first, second), List.of()));

        assertEquals(envelope, codec.decode(codec.encode(envelope)));
        var assignments = ((ClientMessage.Snapshot) codec.decode(codec.encode(envelope)).message())
            .assignments();
        assertEquals(List.of("first", "second"), assignments.stream()
            .map(ClientMessage.Assignment::desiredEntryId).toList());
        assertEquals(List.of("one", "two"), assignments.stream()
            .map(ClientMessage.Assignment::config)
            .map(value -> ((java.util.Map<?, ?>) value.toJava()).get("mode").toString()).toList());
    }

    @Test
    void roundTripsResourceDescriptorsAndRejectsControlDataPlaneLeakage() {
        var zero = snapshotWithResourceLength(0);
        var maximum = snapshotWithResourceLength(Long.MAX_VALUE);
        for (var envelope : List.of(zero, maximum)) {
            var wire = codec.encode(envelope);
            assertEquals(envelope, codec.decode(wire));
            assertTrue(wire.contains("\"byteLength\":\""));
            assertFalse(wire.contains("\"content\""));
            assertFalse(wire.contains("\"url\""));
            assertFalse(wire.contains("\"base64\""));
        }
        assertTrue(codec.encode(maximum).contains("\"byteLength\":\"9223372036854775807\""));

        var wire = codec.encode(zero);
        for (var replacement : List.of("-1", "0", "1.0", "1e2", "\"-1\"", "\"-0\"",
            "\"00\"", "\"01\"", "\"+1\"", "\"1.0\"", "\"1e2\"", "\"\"", "\" \"",
            "\"9223372036854775808\"")) {
            assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
                wire.replace("\"byteLength\":\"0\"", "\"byteLength\":" + replacement));
        }
        for (var leakedField : List.of(
            "\"content\":{\"kind\":\"BYTES\",\"base64\":\"\"}",
            "\"url\":\"https://example.test/index.js\"",
            "\"base64\":\"AA==\"")) {
            assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
                wire.replace("\"byteLength\":\"0\"", "\"byteLength\":\"0\"," + leakedField));
        }
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            wire.replace(",\"byteLength\":\"0\"", ""));
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            wire.replace("\"byteLength\":\"0\"", "\"byteLength\":\"0\",\"byteLength\":\"0\""));
        for (var leakedField : List.of("origin", "credential")) {
            assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
                wire.replace("\"byteLength\":\"0\"",
                    "\"byteLength\":\"0\",\"" + leakedField + "\":\"forbidden\""));
        }
        assertThrows(IllegalArgumentException.class,
            () -> new ClientMessage.ResourceDescriptor("index.js",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", -1));
    }

    @Test
    void requiresEntryModuleToReferenceAResourceAndRejectsHostPaths() {
        var wire = codec.encode(snapshotWithResourceLength(1));
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            wire.replace("\"entryModule\":\"index.js\"", "\"entryModule\":\"missing.js\""));
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            wire.replaceFirst("\"resources\":\\[\\{[^]]*}]", "\"resources\":[]"));

        var resource = "{\"path\":\"index.js\",\"digest\":"
            + "\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\","
            + "\"byteLength\":\"1\"}";
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            wire.replace("\"resources\":[" + resource + "]", "\"resources\":[" + resource + "," + resource + "]"));
        assertThrows(IllegalArgumentException.class,
            () -> snapshotWithResources(List.of(
                new ClientMessage.ResourceDescriptor("index.js",
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", 1),
                new ClientMessage.ResourceDescriptor("index.js",
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", 1)),
                "index.js"));

        for (var path : List.of("/tmp/index.js", "C:/tmp/index.js", "file:/tmp/index.js",
            "https://example.test/index.js",
            "../index.js", "assets/../index.js", "./index.js", "assets//index.js")) {
            assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
                wire.replace("\"entryModule\":\"index.js\"", "\"entryModule\":\"" + path + "\"")
                    .replace("\"path\":\"index.js\"", "\"path\":\"" + path + "\""));
        }
        assertThrows(IllegalArgumentException.class,
            () -> new ClientMessage.ResourceDescriptor("C:\\tmp\\index.js",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", 1));
    }

    @Test
    void roundTripsEverySharedV1FixtureAndCoversEveryType() throws IOException {
        var fixtures = getClass().getResourceAsStream("v1-fixtures.json");
        var messages = codec.decodeFixtures(new String(fixtures.readAllBytes(), StandardCharsets.UTF_8));

        assertEquals(12, messages.size());
        assertEquals(Set.of("client.hello", "host.welcome", "host.snapshot", "host.prepare",
            "host.activate", "host.drain", "host.stop", "client.lifecycle-result", "client.observed",
            "client.call", "host.call-result", "client.detach"),
            messages.stream().map(ClientEnvelope::type).collect(java.util.stream.Collectors.toSet()));
        for (var message : messages) assertEquals(message, codec.decode(codec.encode(message)));

        var snapshot = assertInstanceOf(ClientMessage.Snapshot.class, messages.stream()
            .filter(message -> message.type().equals("host.snapshot")).findFirst()
            .orElseThrow().message());
        assertEquals(5, snapshot.targetRevision());
        assertEquals(List.of("entry-1", "entry-2"), snapshot.assignments().stream()
            .map(ClientMessage.Assignment::desiredEntryId).toList());
        assertEquals(List.of(4L, 5L), snapshot.assignments().stream()
            .map(ClientMessage.Assignment::unitTargetRevision).toList());

        var call = assertInstanceOf(ClientMessage.Call.class, messages.stream()
            .filter(message -> message.type().equals("client.call")).findFirst().orElseThrow().message());
        assertEquals("0", call.call().expectedViewRevision());
        assertEquals(7L, call.call().registrationIdentity());
        assertEquals(new ClientMessage.ContributionId("provider-1", "read"), call.contributionId());
        assertEquals(new LiteralValue.ObjectValue(java.util.Map.of("path", new LiteralValue.StringValue("notes.txt"))),
            call.input());

        var result = assertInstanceOf(ClientMessage.CallResult.class, messages.stream()
            .filter(message -> message.type().equals("host.call-result")).findFirst().orElseThrow().message());
        assertInstanceOf(ClientMessage.CallOutcome.Success.class, result.outcome());
        var failure = assertInstanceOf(ClientMessage.LifecycleOutcome.Failed.class, messages.stream()
            .filter(message -> message.type().equals("client.lifecycle-result")).findFirst().orElseThrow().message()
            instanceof ClientMessage.LifecycleResult lifecycle ? lifecycle.outcome() : null);
        assertEquals("LOAD_FAILED", failure.failure().code());

        var failedResult = new ClientEnvelope(1, "failed-call", "host.call-result",
            new ClientMessage.CallResult(new CallFence(new SessionFence("host-1", "client-1"), "0", 7),
                "tool", new ClientMessage.ContributionId("provider-1", "read"),
                new ClientMessage.CallOutcome.Failed(new ClientMessage.Failure("DENIED", "denied", java.util.Map.of()))));
        assertEquals(failedResult, codec.decode(codec.encode(failedResult)));
    }

    private void assertCode(ClientProtocolCodec.ErrorCode expected, String message) {
        assertProtocolCode(expected, () -> codec.decode(message));
    }

    private void assertProtocolCode(ClientProtocolCodec.ErrorCode expected, Runnable action) {
        var exception = assertThrows(ClientProtocolCodec.ProtocolException.class, action::run);
        assertEquals(expected, exception.code());
    }

    private static ClientEnvelope callEnvelope(LiteralValue input) {
        return new ClientEnvelope(1, "call", "client.call",
            new ClientMessage.Call(new CallFence(new SessionFence("host", "client"), "0", 1), "tool",
                new ClientMessage.ContributionId("provider", "read"), input));
    }

    private static ClientEnvelope callResultEnvelope(LiteralValue value) {
        return new ClientEnvelope(1, "result", "host.call-result",
            new ClientMessage.CallResult(new CallFence(new SessionFence("host", "client"), "0", 1), "tool",
                new ClientMessage.ContributionId("provider", "read"),
                new ClientMessage.CallOutcome.Success(value)));
    }

    private static String taggedNumberWire(String nullInputWire, String value) {
        return nullInputWire.replace("\"input\":null",
            "\"input\":{\"kind\":\"NUMBER\",\"value\":\"" + value + "\"}");
    }

    private static ClientEnvelope snapshotWithResourceLength(long byteLength) {
        var digest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        return snapshotWithResources(List.of(new ClientMessage.ResourceDescriptor("index.js", digest, byteLength)),
            "index.js");
    }

    private static ClientEnvelope snapshotWithResources(List<ClientMessage.ResourceDescriptor> resources,
                                                        String entryModule) {
        var digest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        var assignment = new ClientMessage.Assignment("plugin", "facet", "entry", "definition",
            "runtime", 1, "client:web", LiteralValue.of(java.util.Map.of("mode", "one")),
            entryModule, digest, List.of(), resources);
        return new ClientEnvelope(1, "snapshot", "host.snapshot",
            new ClientMessage.Snapshot(new SessionFence("host", "client"), "0", 1, digest,
                List.of(assignment), List.of()));
    }

    private static LiteralValue nestedList(int depth) {
        LiteralValue value = LiteralValue.NullValue.INSTANCE;
        for (var index = 0; index < depth; index++) value = new LiteralValue.ListValue(List.of(value));
        return value;
    }
}
