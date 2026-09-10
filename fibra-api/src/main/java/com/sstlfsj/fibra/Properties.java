package com.sstlfsj.fibra;

public interface Properties {
    <R, T> Disposable register(PropertyKey<R, T> key,
                               PropertyAccessor<R, T> accessor);

    <R> Associated<R> associate(R receiver);

    <R, T> T get(PropertyKey<R, T> key, R receiver);

    <R, T> void set(PropertyKey<R, T> key, R receiver, T value);
}
