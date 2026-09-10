package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Associated;
import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.Properties;
import com.sstlfsj.fibra.PropertyAccessor;
import com.sstlfsj.fibra.PropertyKey;

final class DefaultProperties implements Properties {
    private final DefaultContext context;

    DefaultProperties(DefaultContext context) {
        this.context = context;
    }

    @Override
    public <R, T> Disposable register(PropertyKey<R, T> key,
                                      PropertyAccessor<R, T> accessor) {
        return context.domain().properties().register(context, key, accessor);
    }

    @Override
    public <R> Associated<R> associate(R receiver) {
        return new Associated<>(this, context, receiver);
    }

    @Override
    public <R, T> T get(PropertyKey<R, T> key, R receiver) {
        return context.domain().properties().get(context, key, receiver);
    }

    @Override
    public <R, T> void set(PropertyKey<R, T> key, R receiver, T value) {
        context.domain().properties().set(context, key, receiver, value);
    }
}
