package com.sstlfsj.fibra;

import java.util.Objects;
import java.util.function.BiFunction;

public final class ServiceRef<T> {
    private final Context caller;
    private final ServiceKey<T> key;

    public ServiceRef(Context caller, ServiceKey<T> key) {
        this.caller = Objects.requireNonNull(caller, "caller");
        this.key = Objects.requireNonNull(key, "key");
    }

    public T value() {
        return caller.services().require(key);
    }

    public <R> R invoke(BiFunction<? super InvocationContext, ? super T, ? extends R> invocation) {
        Objects.requireNonNull(invocation, "invocation");
        return invocation.apply(new InvocationContext(caller, key.name()), value());
    }
}
