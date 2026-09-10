package com.sstlfsj.fibra;

import java.util.Objects;

/** 运行域内类型化关联属性的稳定身份。 */
public record PropertyKey<R, T>(String name, Class<R> receiverType,
                                Class<T> valueType) {
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
