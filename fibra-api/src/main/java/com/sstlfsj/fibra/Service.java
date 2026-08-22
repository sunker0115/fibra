package com.sstlfsj.fibra;

import java.util.Objects;

public abstract class Service<T> {
    private final Context registrationContext;
    private final ServiceRegistration<T> registration;

    protected Service(Context context, ServiceKey<T> key) {
        this.registrationContext = Objects.requireNonNull(context, "context");
        this.registration = context.provide(key, key.type().cast(this));
    }

    protected final Context registrationContext() {
        return registrationContext;
    }

    public final ServiceRegistration<T> registration() {
        return registration;
    }
}
