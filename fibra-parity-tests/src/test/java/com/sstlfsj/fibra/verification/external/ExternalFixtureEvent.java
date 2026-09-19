package com.sstlfsj.fibra.verification.external;

import java.util.Objects;

/** 验证夹具的不可变生命周期事实；不承载任何产品 transport 语义。 */
public record ExternalFixtureEvent(long sequence, String type, String unitKey,
                                   String detail) {
    public ExternalFixtureEvent {
        if (sequence < 1) {
            throw new IllegalArgumentException("event sequence must be positive");
        }
        type = required(type, "type");
        unitKey = required(unitKey, "unit key");
        detail = required(detail, "detail");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
