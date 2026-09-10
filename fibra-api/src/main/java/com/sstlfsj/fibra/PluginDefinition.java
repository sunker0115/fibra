package com.sstlfsj.fibra;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.sstlfsj.fibra.annotation.InjectService;

public final class PluginDefinition<C> {
    private final String name;
    private final Class<C> configType;
    private final ConfigValidator<C> validator;
    private final Map<ServiceKey<?>, Object> requires;
    private final Set<ServiceKey<?>> provides;
    private final PluginFactory<C> factory;
    private final Class<?> injectionType;

    private PluginDefinition(Builder<C> builder) {
        name = builder.name;
        configType = builder.configType;
        validator = builder.validator;
        requires = Collections.unmodifiableMap(new LinkedHashMap<>(builder.requires));
        provides = Collections.unmodifiableSet(new LinkedHashSet<>(builder.provides));
        factory = builder.factory;
        injectionType = builder.injectionType;
    }

    public static <C> Builder<C> builder(String name, Class<C> configType,
                                         PluginFactory<C> factory) {
        return new Builder<>(name, configType, factory);
    }

    public String name() {
        return name;
    }

    public Class<C> configType() {
        return configType;
    }

    public Map<ServiceKey<?>, Object> requires() {
        return requires;
    }

    public Set<ServiceKey<?>> provides() {
        return provides;
    }

    public PluginFactory<C> factory() {
        return factory;
    }

    public Class<?> injectionType() {
        return injectionType;
    }

    public C validate(C config) {
        if (config != null && !configType.isInstance(config)) {
            throw new IllegalArgumentException("plugin config is not a " + configType.getName());
        }
        return validator == null ? config : validator.validate(config);
    }

    public static final class Builder<C> {
        private final String name;
        private final Class<C> configType;
        private final PluginFactory<C> factory;
        private final Map<ServiceKey<?>, Object> requires = new LinkedHashMap<>();
        private final Set<ServiceKey<?>> provides = new LinkedHashSet<>();
        private ConfigValidator<C> validator;
        private Class<?> injectionType;

        private Builder(String name, Class<C> configType, PluginFactory<C> factory) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("plugin name must not be blank");
            }
            this.name = name;
            this.configType = Objects.requireNonNull(configType, "configType");
            this.factory = Objects.requireNonNull(factory, "factory");
        }

        public Builder<C> require(ServiceKey<?> key) {
            return require(key, null);
        }

        public Builder<C> require(ServiceKey<?> key, Object intercept) {
            requires.put(Objects.requireNonNull(key, "key"), intercept);
            return this;
        }

        public Builder<C> provide(ServiceKey<?> key) {
            provides.add(Objects.requireNonNull(key, "key"));
            return this;
        }

        public Builder<C> inject(Class<?> type) {
            injectionType = Objects.requireNonNull(type, "type");
            for (var current = type; current != null && current != Object.class;
                 current = current.getSuperclass()) {
                for (var annotation : current.getAnnotationsByType(InjectService.class)) {
                    require(injectionKey(annotation, null));
                }
                for (var field : current.getDeclaredFields()) {
                    var annotation = field.getAnnotation(InjectService.class);
                    if (annotation != null) {
                        require(injectionKey(annotation, field.getType()));
                    }
                }
            }
            return this;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static ServiceKey<?> injectionKey(InjectService annotation,
                                                   Class<?> inferredType) {
            var type = annotation.type() == Void.class
                ? inferredType : annotation.type();
            if (type == null) {
                throw new IllegalArgumentException(
                    "@InjectService on a type must declare type");
            }
            return ServiceKey.of(annotation.value(), (Class) type);
        }

        public Builder<C> validator(ConfigValidator<C> validator) {
            this.validator = Objects.requireNonNull(validator, "validator");
            return this;
        }

        public PluginDefinition<C> build() {
            return new PluginDefinition<>(this);
        }
    }
}
