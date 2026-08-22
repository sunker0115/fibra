package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.ServiceKey;

import java.util.Objects;

final class ServiceImpl<T> {
    private final ServiceKey<T> key;
    private final DefaultFibra fibra;
    private final Object isolateToken;
    private T value;

    public ServiceImpl(ServiceKey<T> key, T value, DefaultFibra fibra, Object isolateToken) {
        this.key = Objects.requireNonNull(key, "key");
        this.value = Objects.requireNonNull(value, "value");
        this.fibra = Objects.requireNonNull(fibra, "fibra");
        this.isolateToken = Objects.requireNonNull(isolateToken, "isolateToken");
    }

    public ServiceKey<T> key() {
        return key;
    }

    public T value() {
        return value;
    }

    public void value(T value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public DefaultFibra fibra() {
        return fibra;
    }

    public Object isolateToken() {
        return isolateToken;
    }
}
