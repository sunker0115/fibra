package com.sstlfsj.fibra;

import java.util.Objects;
import java.util.function.BiFunction;

public final class BoundService<T> {
    private final Context caller;
    private final ServiceKey<T> key;

    BoundService(Context caller, ServiceKey<T> key) {
        this.caller = Objects.requireNonNull(caller, "caller");
        this.key = Objects.requireNonNull(key, "key");
    }

    public <R> R invoke(BiFunction<? super InvocationContext, ? super T, ? extends R> invocation) {
        Objects.requireNonNull(invocation, "invocation");
        var service = caller.get(key);
        if (service == null) {
            throw new FibraException(FibraException.SERVICE_INACTIVE,
                "required service \"" + key.name() + "\" is inactive");
        }
        return invocation.apply(new InvocationContext(caller, key.name()), service);
    }

    public T value() {
        return caller.get(key);
    }
}
