package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.ServiceRef;
import com.sstlfsj.fibra.ServiceRegistration;
import com.sstlfsj.fibra.Services;

import java.util.Optional;

final class DefaultServices implements Services {
    private final DefaultContext context;

    DefaultServices(DefaultContext context) {
        this.context = context;
    }

    @Override
    public <T> ServiceRegistration<T> provide(ServiceKey<T> key, T value) {
        return context.runtime().services().provide(context, key, value);
    }

    @Override
    public <T> Optional<T> find(ServiceKey<T> key) {
        return context.runtime().services().find(context, key);
    }

    @Override
    public <T> T require(ServiceKey<T> key) {
        return context.runtime().services().require(context, key);
    }

    @Override
    public <T> ServiceRef<T> reference(ServiceKey<T> key) {
        return new ServiceRef<>(context, key);
    }
}
