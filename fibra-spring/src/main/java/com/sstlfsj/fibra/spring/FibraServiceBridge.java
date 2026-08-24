package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.ServiceRegistration;

import java.util.Objects;

/** 把宿主对象通过显式 ServiceKey 注册到 Fibra root。 */
public final class FibraServiceBridge {
    private final Context root;

    public FibraServiceBridge(Context root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    public <T> ServiceRegistration<T> register(ServiceKey<T> key, T service) {
        return root.provide(Objects.requireNonNull(key, "key"),
            Objects.requireNonNull(service, "service"));
    }
}
