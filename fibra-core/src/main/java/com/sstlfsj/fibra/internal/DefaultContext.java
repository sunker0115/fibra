package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Effects;
import com.sstlfsj.fibra.Events;
import com.sstlfsj.fibra.Plugins;
import com.sstlfsj.fibra.Scope;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.Services;
import com.sstlfsj.fibra.logging.FibraLogger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class DefaultContext implements Context {
    private final DefaultScope scope;
    private final ResourceOwner owner;
    private final DefaultContext parent;
    private final Map<String, Object> metadata;
    private final Map<String, Object> realms;
    private final Map<String, Object> intercepts;
    private final Services services;
    private final Effects effects;
    private final Events events;
    private final Plugins plugins;

    static DefaultContext root(DefaultScope scope) {
        return new DefaultContext(scope, scope, null, Map.of(), Map.of(), Map.of());
    }

    private DefaultContext(DefaultScope scope, ResourceOwner owner, DefaultContext parent,
                           Map<String, Object> metadata, Map<String, Object> realms,
                           Map<String, Object> intercepts) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.parent = parent;
        this.metadata = Map.copyOf(metadata);
        this.realms = Map.copyOf(realms);
        this.intercepts = Map.copyOf(intercepts);
        services = new DefaultServices(this);
        effects = new DefaultEffects(owner);
        events = new DefaultEvents(this);
        plugins = new DefaultPlugins(this);
    }

    DefaultContext forOwner(ResourceOwner nextOwner, Map<ServiceKey<?>, Object> dependencyIntercepts) {
        var values = new LinkedHashMap<String, Object>();
        dependencyIntercepts.forEach((key, value) -> {
            if (value != null && !hasIntercept(key.name())) {
                values.put(key.name(), value);
            }
        });
        return new DefaultContext(scope, nextOwner, this, Map.of(), Map.of(), values);
    }

    @Override
    public Scope scope() {
        return scope;
    }

    @Override
    public Services services() {
        return services;
    }

    @Override
    public Effects effects() {
        return effects;
    }

    @Override
    public Events events() {
        return events;
    }

    @Override
    public Plugins plugins() {
        return plugins;
    }

    @Override
    public FibraLogger logger() {
        return scope.runtime().logging().logger(this, owner.ownerName());
    }

    @Override
    public Context withMetadata(String name, Object value) {
        validateName(name, "metadata name");
        return new DefaultContext(scope, owner, this, Map.of(name, value), Map.of(), Map.of());
    }

    @Override
    public Context withRealm(ServiceKey<?> key, Object label) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        return new DefaultContext(scope, owner, this, Map.of(), Map.of(key.name(), label), Map.of());
    }

    @Override
    public Context withIntercept(ServiceKey<?> key, Object value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        return new DefaultContext(scope, owner, this, Map.of(), Map.of(), Map.of(key.name(), value));
    }

    @Override
    public Object intercept(ServiceKey<?> key) {
        Objects.requireNonNull(key, "key");
        return intercept(key.name());
    }

    DefaultFibraRuntime runtime() {
        return scope.runtime();
    }

    DefaultScope scopeImpl() {
        return scope;
    }

    ResourceOwner owner() {
        return owner;
    }

    Object realm(String serviceName) {
        for (var current = this; current != null; current = current.parent) {
            if (current.realms.containsKey(serviceName)) {
                return current.realms.get(serviceName);
            }
        }
        return ServiceRegistry.DEFAULT_REALM;
    }

    Object intercept(String serviceName) {
        for (var current = this; current != null; current = current.parent) {
            if (current.intercepts.containsKey(serviceName)) {
                return current.intercepts.get(serviceName);
            }
        }
        return null;
    }

    private boolean hasIntercept(String serviceName) {
        for (var current = this; current != null; current = current.parent) {
            if (current.intercepts.containsKey(serviceName)) {
                return true;
            }
        }
        return false;
    }

    Object metadata(String name) {
        for (var current = this; current != null; current = current.parent) {
            if (current.metadata.containsKey(name)) {
                return current.metadata.get(name);
            }
        }
        return null;
    }

    private static void validateName(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}
