package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.ServiceRegistration;
import com.sstlfsj.fibra.engine.HostServiceRegistry;

import java.util.Objects;

/** 在 Engine 启动前把宿主对象登记为显式 host binding。 */
public final class FibraServiceBridge {
    private final HostServiceRegistry hostServices;

    public FibraServiceBridge(HostServiceRegistry hostServices) {
        this.hostServices = Objects.requireNonNull(hostServices, "hostServices");
    }

    public <T> ServiceRegistration<T> register(ServiceKey<T> key, T service) {
        return hostServices.register(Objects.requireNonNull(key, "key"),
            Objects.requireNonNull(service, "service"));
    }
}
