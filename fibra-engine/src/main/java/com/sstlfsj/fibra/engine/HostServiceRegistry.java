package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.ServiceRegistration;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 在 Engine 启动前收集宿主服务；启动时冻结并发布到长期 RuntimeDomain。 */
public final class HostServiceRegistry {
    private final Map<ServiceKey<?>, Binding<?>> bindings = new LinkedHashMap<>();
    private boolean frozen;

    public synchronized <T> ServiceRegistration<T> register(ServiceKey<T> key, T service) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(service, "service");
        if (frozen) {
            throw new IllegalStateException("host services are frozen");
        }
        if (!key.type().isInstance(service)) {
            throw new IllegalArgumentException(
                "host service is not a " + key.type().getName());
        }
        var binding = new Binding<>(key, service);
        if (bindings.putIfAbsent(key, binding) != null) {
            throw new IllegalArgumentException("duplicate host service " + key.name());
        }
        return new Registration<>(this, binding);
    }

    synchronized List<Binding<?>> freeze() {
        frozen = true;
        return List.copyOf(bindings.values());
    }

    private synchronized void remove(Binding<?> binding) {
        bindings.remove(binding.key(), binding);
    }

    record Binding<T>(ServiceKey<T> key, T value) {
    }

    private record Registration<T>(HostServiceRegistry registry, Binding<T> binding)
        implements ServiceRegistration<T> {
        @Override
        public ServiceKey<T> key() {
            return binding.key();
        }

        @Override
        public T value() {
            return binding.value();
        }

        @Override
        public Mono<Void> dispose() {
            return Mono.fromRunnable(() -> registry.remove(binding));
        }
    }
}
