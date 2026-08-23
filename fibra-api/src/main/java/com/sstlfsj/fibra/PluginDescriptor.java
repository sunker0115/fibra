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
    private final Map<String, Object> namedDependencies;
    private final ConfigValidator<C> validator;
    private final Set<ServiceKey<?>> providedServices;

    private PluginDescriptor(Builder<C> builder) {
        this.name = builder.name;
        this.dependencies = Collections.unmodifiableMap(new LinkedHashMap<>(builder.dependencies));
        this.namedDependencies = Collections.unmodifiableMap(
            new LinkedHashMap<>(builder.namedDependencies));
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

    public Map<String, Object> namedDependencies() {
        return namedDependencies;
    }

    public C validate(C config) {
        return validator == null ? config : validator.validate(config);
    }

    public Set<ServiceKey<?>> providedServices() {
        return providedServices;
    }

    public PluginDescriptor<C> withRequirements(Map<String, ?> requirements) {
        Objects.requireNonNull(requirements, "requirements");
        if (requirements.isEmpty()) {
            return this;
        }
        var builder = new Builder<C>(name);
        builder.dependencies.putAll(dependencies);
        builder.namedDependencies.putAll(namedDependencies);
        builder.validator = validator;
        builder.providedServices.addAll(providedServices);
        requirements.forEach((serviceName, intercept) -> {
            Builder.validateServiceName(serviceName);
            var typed = builder.dependencies.keySet().stream()
                .filter(key -> key.name().equals(serviceName))
                .findFirst();
            if (typed.isPresent()) {
                builder.dependencies.put(typed.get(), intercept);
            } else {
                builder.namedDependencies.put(serviceName, intercept);
            }
        });
        return builder.build();
    }

    public static final class Builder<C> {
        private final String name;
        private final Map<ServiceKey<?>, Object> dependencies = new LinkedHashMap<>();
        private final Map<String, Object> namedDependencies = new LinkedHashMap<>();
        private ConfigValidator<C> validator;
        private final Set<ServiceKey<?>> providedServices = new LinkedHashSet<>();

        private Builder(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("plugin name must not be blank");
            }
            this.name = name;
        }

        public Builder<C> require(ServiceKey<?> key) {
            return require(key, null);
        }

        public Builder<C> require(ServiceKey<?> key, Object intercept) {
            Objects.requireNonNull(key, "key");
            if (namedDependencies.containsKey(key.name())) {
                throw new IllegalArgumentException(
                    "service dependency \"" + key.name() + "\" is already declared by name");
            }
            dependencies.put(key, intercept);
            return this;
        }

        public Builder<C> require(String serviceName) {
            return require(serviceName, null);
        }

        public Builder<C> require(String serviceName, Object intercept) {
            validateServiceName(serviceName);
            if (dependencies.keySet().stream().anyMatch(key -> key.name().equals(serviceName))) {
                throw new IllegalArgumentException(
                    "service dependency \"" + serviceName + "\" is already declared by key");
            }
            namedDependencies.put(serviceName, intercept);
            return this;
        }

        private static void validateServiceName(String serviceName) {
            if (serviceName == null || serviceName.isBlank()) {
                throw new IllegalArgumentException("service name must not be blank");
            }
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
                    require(toKey(annotation, null));
                }
                for (var field : current.getDeclaredFields()) {
                    var annotation = field.getAnnotation(InjectService.class);
                    if (annotation != null) {
                        require(toKey(annotation, field.getType()));
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
