package com.sstlfsj.fibra;

import java.util.Objects;

/** 保留调用方 Context 的关联对象视图。 */
public final class Associated<R> {
    private final Context caller;
    private final R receiver;

    Associated(Context caller, R receiver) {
        this.caller = Objects.requireNonNull(caller, "caller");
        this.receiver = Objects.requireNonNull(receiver, "receiver");
    }

    public R value() {
        return receiver;
    }

    public Context caller() {
        return caller;
    }

    public <T> T get(PropertyKey<R, T> key) {
        return caller.get(key, receiver);
    }

    public <T> void set(PropertyKey<R, T> key, T value) {
        caller.set(key, receiver, value);
    }
}
