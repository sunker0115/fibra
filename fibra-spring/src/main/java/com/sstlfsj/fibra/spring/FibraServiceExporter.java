package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.ServiceRegistration;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.context.SmartLifecycle;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class FibraServiceExporter
    implements DestructionAwareBeanPostProcessor, SmartLifecycle {
    private final Supplier<FibraServiceBridge> bridge;
    private final Map<Object, ServiceRegistration<?>> registrations =
        new IdentityHashMap<>();
    private volatile boolean running;

    public FibraServiceExporter(FibraServiceBridge bridge) {
        this(() -> bridge);
    }

    public FibraServiceExporter(Supplier<FibraServiceBridge> bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName)
        throws BeansException {
        var type = AopUtils.getTargetClass(bean);
        var annotation = AnnotationUtils.findAnnotation(type, FibraService.class);
        if (annotation == null) {
            return bean;
        }
        if (!annotation.type().isInstance(bean)) {
            throw new IllegalStateException("@FibraService bean " + beanName
                + " does not implement " + annotation.type().getName());
        }
        registrations.put(bean, register(annotation, bean));
        return bean;
    }

    @Override
    public void postProcessBeforeDestruction(Object bean, String beanName)
        throws BeansException {
        var registration = registrations.remove(bean);
        if (registration != null) {
            registration.dispose().block();
        }
    }

    @Override
    public boolean requiresDestruction(Object bean) {
        return registrations.containsKey(bean);
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        var current = java.util.List.copyOf(registrations.values());
        registrations.clear();
        reactor.core.publisher.Flux.fromIterable(current)
            .concatMap(ServiceRegistration::dispose)
            .then().block();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ServiceRegistration<?> register(FibraService annotation, Object bean) {
        return bridge.get().register(ServiceKey.of(annotation.name(),
            (Class) annotation.type()), bean);
    }
}
