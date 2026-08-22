package com.sstlfsj.fibra;

import java.util.Objects;

/** Java 对 Cordis 动态属性名的类型安全替代。 */
public record PropertyKey<R, T>(String name, Class<R> receiverType, Class<T> valueType) {
    public PropertyKey {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("property name must not be blank");
        }
        Objects.requireNonNull(receiverType, "receiverType");
        Objects.requireNonNull(valueType, "valueType");
    }

    public static <R, T> PropertyKey<R, T> of(String name, Class<R> receiverType,
                                               Class<T> valueType) {
        return new PropertyKey<>(name, receiverType, valueType);
    }
}
