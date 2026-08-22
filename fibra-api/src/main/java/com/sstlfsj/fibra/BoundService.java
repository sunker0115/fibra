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

    /**
     * 重新解析当前 ACTIVE provider，并在调用方线程执行 invocation。
     * invocation 获得的原始 provider 只对本次调用有效，不应跨调用缓存。
     */
    public <R> R invoke(BiFunction<? super InvocationContext, ? super T, ? extends R> invocation) {
        Objects.requireNonNull(invocation, "invocation");
        var service = caller.get(key);
        if (service == null) {
            throw new FibraException(FibraException.SERVICE_INACTIVE,
                "required service \"" + key.name() + "\" is inactive");
        }
        return invocation.apply(new InvocationContext(caller, key.name()), service);
    }

    /**
     * 重新解析并返回当前 ACTIVE provider；返回值不会随 provider reload 自动更新。
     */
    public T value() {
        return caller.get(key);
    }
}
