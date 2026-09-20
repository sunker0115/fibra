package com.sstlfsj.fibra.client.protocol;

import java.util.Objects;

/** 仅用于首次握手的 client 自生成身份。 */
public record HelloIdentity(String clientNonce) {
    public HelloIdentity {
        clientNonce = required(clientNonce, "clientNonce");
    }

    private static String required(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
