package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Effects;
import com.sstlfsj.fibra.Events;
import com.sstlfsj.fibra.Plugins;
import com.sstlfsj.fibra.Properties;
import com.sstlfsj.fibra.Scope;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.Services;
import com.sstlfsj.fibra.logging.FibraLogger;
import com.sstlfsj.fibra.logging.LoggerIntercept;
import com.sstlfsj.fibra.logging.LoggerService;

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
    private final Properties properties;

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
        properties = new DefaultProperties(this);
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
    public Properties properties() {
        return properties;
    }

    @Override
    public LoggerService logging() {
        return new BoundLoggerService(scope.runtime().logging(), this);
    }

    @Override
    public FibraLogger logger() {
        return scope.runtime().logging().logger(this, null, ownerId());
    }

    @Override
    public FibraLogger logger(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("logger name must not be blank");
        }
        return scope.runtime().logging().logger(this, name, ownerId());
    }

    @Override
    public FibraLogger loggerForService(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("service name must not be blank");
        }
        return scope.runtime().logging().logger(this, null, serviceName);
    }

    @Override
    public Object metadata(String name) {
        validateName(name, "metadata name");
        return metadataValue(name);
    }

    @Override
    public Context withMetadata(String name, Object value) {
        validateName(name, "metadata name");
        return new DefaultContext(scope, owner, this, Map.of(name, value), Map.of(), Map.of());
    }

    @Override
    public Context withRealm(ServiceKey<?> key, Object label) {
        Objects.requireNonNull(key, "key");
        return withRealm(key.name(), label);
    }

    @Override
    public Context withRealm(String serviceName, Object label) {
        validateName(serviceName, "service name");
        Objects.requireNonNull(label, "label");
        return new DefaultContext(scope, owner, this, Map.of(), Map.of(serviceName, label), Map.of());
    }

    @Override
    public Context withIntercept(ServiceKey<?> key, Object value) {
        Objects.requireNonNull(key, "key");
        return withIntercept(key.name(), value);
    }

    @Override
    public Context withIntercept(String serviceName, Object value) {
        validateName(serviceName, "service name");
        Objects.requireNonNull(value, "value");
        return new DefaultContext(scope, owner, this, Map.of(), Map.of(), Map.of(serviceName, value));
    }

    @Override
    public Context withLogger(LoggerIntercept value) {
        return new DefaultContext(scope, owner, this, Map.of(), Map.of(),
            Map.of("logger", Objects.requireNonNull(value, "value")));
    }

    @Override
    public Object intercept(ServiceKey<?> key) {
        Objects.requireNonNull(key, "key");
        return intercept(key.name());
    }

    DefaultFibraRuntime runtime() {
        return scope.runtime();
    }

    DefaultRuntimeDomain domain() {
        return scope.domain();
    }

    DefaultScope scopeImpl() {
        return scope;
    }

    ResourceOwner owner() {
        return owner;
    }

    void assertReadable() {
        if (owner instanceof PluginInstanceImpl<?> instance
            && instance.stateUnsafe()
                == com.sstlfsj.fibra.PluginInstanceState.DISPOSED) {
            throw new IllegalStateException(
                "disposed plugin context cannot access services");
        }
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

    Object metadataValue(String name) {
        for (var current = this; current != null; current = current.parent) {
            if (current.metadata.containsKey(name)) {
                return current.metadata.get(name);
            }
        }
        return null;
    }

    LoggerIntercept loggerIntercept() {
        for (var current = this; current != null; current = current.parent) {
            var value = current.intercepts.get("logger");
            if (value instanceof LoggerIntercept intercept) {
                return intercept;
            }
        }
        return null;
    }

    private String ownerId() {
        var name = owner.ownerName();
        var separator = name.indexOf(':');
        return separator < 0 ? name : name.substring(separator + 1);
    }

    private static void validateName(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}
