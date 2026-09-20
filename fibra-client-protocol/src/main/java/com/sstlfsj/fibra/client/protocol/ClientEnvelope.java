package com.sstlfsj.fibra.client.protocol;

import java.util.Objects;

/** 传输无关的 v1 wire envelope。 */
public record ClientEnvelope(int protocolVersion, String messageId, String type,
                             ClientMessage message) {
    public ClientEnvelope {
        if (protocolVersion != ClientProtocolCodec.VERSION) {
            throw new IllegalArgumentException("unsupported protocol version");
        }
        messageId = required(messageId, "messageId");
        type = required(type, "type");
        message = Objects.requireNonNull(message, "message");
        if (!type.equals(message.type())) {
            throw new IllegalArgumentException("type does not match message");
        }
    }

    private static String required(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
