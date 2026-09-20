package verification.distribution;

import com.sstlfsj.fibra.client.protocol.ClientMessage;
import com.sstlfsj.fibra.client.protocol.ClientProtocolCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ClientProtocolConsumerTest {
    @Test
    void roundTripsThePublishedTransportNeutralProtocol() {
        var codec = new ClientProtocolCodec();
        var envelope = codec.decode("""
            {"protocolVersion":1,"messageId":"consumer-hello","type":"client.hello",
             "payload":{"identity":{"clientNonce":"consumer"},
             "executionTarget":"client:web","capabilities":["dom"]}}
            """);

        var hello = assertInstanceOf(ClientMessage.Hello.class, envelope.message());
        assertEquals("consumer", hello.identity().clientNonce());
        assertEquals(envelope, codec.decode(codec.encode(envelope)));
    }
}
