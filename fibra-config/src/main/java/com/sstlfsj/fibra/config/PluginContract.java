package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.ServiceKey;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

public final class PluginContract {
    private final String name;
    private final Class<?> configType;
    private final Set<ServiceKey<?>> requires;
    private final Set<ServiceKey<?>> provides;
    private final Function<Object, Object> binder;

    private PluginContract(Builder builder) {
        name = builder.name;
        configType = Objects.requireNonNull(builder.configType, "configType");
        requires = Set.copyOf(builder.requires);
        provides = Set.copyOf(builder.provides);
        binder = Objects.requireNonNull(builder.binder, "binder");
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public Class<?> configType() {
        return configType;
    }

    public Set<ServiceKey<?>> requires() {
        return requires;
    }

    public Set<ServiceKey<?>> provides() {
        return provides;
    }

    public Object bind(Object literal) {
        var value = binder.apply(literal);
        if (value != null && !configType.isInstance(value)) {
            throw new IllegalArgumentException("config binder for \"" + name
                + "\" returned " + value.getClass().getName() + " instead of "
                + configType.getName());
        }
        return value;
    }

    public static final class Builder {
        private final String name;
        private final Set<ServiceKey<?>> requires = new LinkedHashSet<>();
        private final Set<ServiceKey<?>> provides = new LinkedHashSet<>();
        private Class<?> configType;
        private Function<Object, Object> binder = Function.identity();

        private Builder(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("plugin contract name must not be blank");
            }
            this.name = name;
        }

        public Builder configType(Class<?> value) {
            configType = Objects.requireNonNull(value, "configType");
            return this;
        }

        public Builder require(ServiceKey<?> key) {
            requires.add(Objects.requireNonNull(key, "key"));
            return this;
        }

        public Builder provide(ServiceKey<?> key) {
            provides.add(Objects.requireNonNull(key, "key"));
            return this;
        }

        public Builder binder(Function<Object, Object> value) {
            binder = Objects.requireNonNull(value, "binder");
            return this;
        }

        public PluginContract build() {
            return new PluginContract(this);
        }
    }
}
