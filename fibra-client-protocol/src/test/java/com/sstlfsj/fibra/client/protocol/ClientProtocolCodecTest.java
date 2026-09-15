package com.sstlfsj.fibra.client.protocol;

import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                + "\"clientExecutionId\":\"client\"},\"executions\":[{\"targetRevision\":1,"
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
                + "\"viewRevision\":\"0\",\"targetRevision\":1,\"targetDigest\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\","
                + "\"assignments\":[{\"pluginId\":\"plugin\",\"facetId\":\"facet\",\"runtimeInstanceId\":\"runtime\",\"executionTarget\":\"client:web\",\"entryModule\":\"index.js\","
                + "\"payloadDigest\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\",\"requiredCapabilities\":[],\"resources\":[],\"unknown\":true}],\"contributions\":[]}}");
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
                + "\"targetRevision\":1,\"runtimeInstanceId\":\"runtime\","
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
                + "\"registrationIdentity\":9223372036854775808},\"contributionKind\":\"tool\","
                + "\"contributionId\":{\"providerInstanceId\":\"provider\",\"localName\":\"read\"},"
                + "\"input\":null}}");
    }

    @Test
    void rejectsUtf8EnvelopeLargerThanOneMiB() {
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            "你".repeat(ClientProtocolCodec.MAX_ENVELOPE_BYTES / 2));
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
        var exception = assertThrows(ClientProtocolCodec.ProtocolException.class,
            () -> codec.decode(message));
        assertEquals(expected, exception.code());
    }
}
