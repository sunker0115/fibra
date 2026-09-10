package com.sstlfsj.fibra;

import java.util.Objects;
import java.util.function.BiFunction;

public interface PropertyAccessor<R, T> {
    T get(Context caller, R receiver);

    default void set(Context caller, R receiver, T value) {
        throw new UnsupportedOperationException("property is read-only");
    }

    static <R, T> PropertyAccessor<R, T> readOnly(
        BiFunction<? super Context, ? super R, ? extends T> getter) {
        Objects.requireNonNull(getter, "getter");
        return new PropertyAccessor<>() {
            @Override
            public T get(Context caller, R receiver) {
                return getter.apply(caller, receiver);
            }
        };
    }
}
