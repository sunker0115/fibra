package com.sstlfsj.fibra;

import com.sstlfsj.fibra.annotation.InjectService;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;

public final class PluginDescriptor<C> {
    private final String name;
    private final Map<ServiceKey<?>, Object> dependencies;
    private final ConfigValidator<C> validator;
    private final Set<ServiceKey<?>> providedServices;

    private PluginDescriptor(Builder<C> builder) {
        this.name = builder.name;
        this.dependencies = Collections.unmodifiableMap(new LinkedHashMap<>(builder.dependencies));
        this.validator = builder.validator;
        this.providedServices = Collections.unmodifiableSet(new LinkedHashSet<>(builder.providedServices));
    }

    public static <C> Builder<C> builder(String name) {
        return new Builder<>(name);
    }

    public String name() {
        return name;
    }

    public Map<ServiceKey<?>, Object> dependencies() {
        return dependencies;
    }

    public C validate(C config) {
        return validator == null ? config : validator.validate(config);
    }

    public Set<ServiceKey<?>> providedServices() {
        return providedServices;
    }

    public static final class Builder<C> {
        private final String name;
        private final Map<ServiceKey<?>, Object> dependencies = new LinkedHashMap<>();
        private ConfigValidator<C> validator;
        private final Set<ServiceKey<?>> providedServices = new LinkedHashSet<>();

        private Builder(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("plugin name must not be blank");
            }
            this.name = name;
        }

        public Builder<C> require(ServiceKey<?> key) {
            dependencies.put(Objects.requireNonNull(key, "key"), null);
            return this;
        }

        public Builder<C> require(ServiceKey<?> key, Object intercept) {
            dependencies.put(Objects.requireNonNull(key, "key"), intercept);
            return this;
        }

        public Builder<C> validator(ConfigValidator<C> validator) {
            this.validator = Objects.requireNonNull(validator, "validator");
            return this;
        }

        public Builder<C> provide(ServiceKey<?> key) {
            providedServices.add(Objects.requireNonNull(key, "key"));
            return this;
        }

        public Builder<C> inject(Class<?> type) {
            Objects.requireNonNull(type, "type");
            for (var current = type; current != null && current != Object.class;
                 current = current.getSuperclass()) {
                for (var annotation : current.getAnnotationsByType(InjectService.class)) {
                    dependencies.put(toKey(annotation, null), null);
                }
                for (var field : current.getDeclaredFields()) {
                    var annotation = field.getAnnotation(InjectService.class);
                    if (annotation != null) {
                        dependencies.put(toKey(annotation, field.getType()), null);
                    }
                }
            }
            return this;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static ServiceKey<?> toKey(InjectService annotation, Class<?> inferredType) {
            var type = annotation.type() == Void.class ? inferredType : annotation.type();
            if (type == null) {
                throw new IllegalArgumentException("@InjectService must declare a type");
            }
            return ServiceKey.of(annotation.value(), (Class) type);
        }

        public PluginDescriptor<C> build() {
            return new PluginDescriptor<>(this);
        }
    }
}
