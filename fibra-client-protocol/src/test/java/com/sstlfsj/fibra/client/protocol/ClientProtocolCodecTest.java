package com.sstlfsj.fibra.client.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientProtocolCodecTest {
    private final ClientProtocolCodec codec = new ClientProtocolCodec();

    @Test
    void rejectsMalformedEnvelopeShapes() {
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            "{\"protocolVersion\":1,\"messageId\":\"one\",\"type\":\"client.detach\","
                + "\"payload\":{\"session\":{\"hostInstanceId\":\"host\","
                + "\"clientExecutionId\":\"client\"},\"unexpected\":true}}" );
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            "{\"protocolVersion\":1,\"protocolVersion\":1,\"messageId\":\"one\","
                + "\"type\":\"client.detach\",\"payload\":{\"session\":{"
                + "\"hostInstanceId\":\"host\",\"clientExecutionId\":\"client\"}}}");
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            "{\"protocolVersion\":1,\"messageId\":\"one\",\"type\":\"client.detach\","
                + "\"payload\":{\"session\":{\"hostInstanceId\":\"host\","
                + "\"clientExecutionId\":\"client\"}}} true");
    }

    @Test
    void rejectsUnsupportedVersionsAndUnknownTypes() {
        assertCode(ClientProtocolCodec.ErrorCode.UNSUPPORTED_PROTOCOL,
            "{\"protocolVersion\":2,\"messageId\":\"one\",\"type\":\"client.hello\","
                + "\"payload\":{\"identity\":{\"clientNonce\":\"nonce\"},"
                + "\"executionTarget\":\"client:web\",\"capabilities\":[]}}");
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            "{\"protocolVersion\":1,\"messageId\":\"one\",\"type\":\"client.future\","
                + "\"payload\":{}}");
        assertCode(ClientProtocolCodec.ErrorCode.MALFORMED_MESSAGE,
            "{\"protocolVersion\":1,\"messageId\":\"one\",\"type\":\"client.observed\","
                + "\"payload\":{\"session\":{\"hostInstanceId\":\"host\","
                + "\"clientExecutionId\":\"client\"},\"state\":\"UNKNOWN\"}}");
    }

    @Test
    void rejectsMissingWrongAndMixedPhaseIdentities() {
        assertCode(ClientProtocolCodec.ErrorCode.INVALID_IDENTITY,
            "{\"protocolVersion\":1,\"messageId\":\"one\",\"type\":\"client.hello\","
                + "\"payload\":{\"executionTarget\":\"client:web\",\"capabilities\":[]}}");
        assertCode(ClientProtocolCodec.ErrorCode.INVALID_IDENTITY,
            "{\"protocolVersion\":1,\"messageId\":\"one\",\"type\":\"host.prepare\","
                + "\"payload\":{\"session\":{\"hostInstanceId\":\"host\","
                + "\"clientExecutionId\":\"client\"}}}");
        assertCode(ClientProtocolCodec.ErrorCode.INVALID_IDENTITY,
            "{\"protocolVersion\":1,\"messageId\":\"one\",\"type\":\"client.hello\","
                + "\"payload\":{\"identity\":{\"clientNonce\":\"nonce\"},"
                + "\"executionTarget\":\"client:web\",\"capabilities\":[],"
                + "\"session\":{\"hostInstanceId\":\"host\",\"clientExecutionId\":\"client\"}}}");
    }

    @Test
    void readsEverySharedV1Fixture() throws IOException {
        var fixtures = getClass().getResourceAsStream("v1-fixtures.json");
        var text = new String(fixtures.readAllBytes(), StandardCharsets.UTF_8);
        var messages = codec.decodeFixtures(text);

        assertEquals(12, messages.size());
        assertInstanceOf(ClientMessage.Hello.class, messages.getFirst().message());
        assertEquals("client.detach", messages.getLast().type());
    }

    private void assertCode(ClientProtocolCodec.ErrorCode expected, String message) {
        var exception = assertThrows(ClientProtocolCodec.ProtocolException.class,
            () -> codec.decode(message));
        assertEquals(expected, exception.code());
    }
}
