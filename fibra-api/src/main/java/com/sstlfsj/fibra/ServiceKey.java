package com.sstlfsj.fibra;

import java.util.Objects;

public record ServiceKey<T>(String name, Class<T> type) {
    public ServiceKey {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("service name must not be blank");
        }
        Objects.requireNonNull(type, "type");
    }

    public static <T> ServiceKey<T> of(String name, Class<T> type) {
        return new ServiceKey<>(name, type);
    }
}
