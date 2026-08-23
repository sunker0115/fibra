package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.ServiceRegistration;

import java.util.Objects;

/**
 * 把宿主 Spring 单例经类型化 {@link ServiceKey} 暴露给 Fibra 插件的通用机制。
 * 注册归 root Context 的 Fibra effect 所有，返回可等待撤销的 registration。
 * 不做按类型自动装配；桥接哪个 bean 由宿主显式决定。
 */
public final class FibraServiceBridge {
    private final Context root;

    public FibraServiceBridge(Context root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    public <T> ServiceRegistration<T> register(ServiceKey<T> key, T service) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(service, "service");
        return root.provide(key, service);
    }
}
