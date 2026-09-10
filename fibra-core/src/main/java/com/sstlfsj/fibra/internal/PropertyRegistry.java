package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.FibraException;
import com.sstlfsj.fibra.PropertyAccessor;
import com.sstlfsj.fibra.PropertyKey;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class PropertyRegistry {
    private final DefaultRuntimeDomain domain;
    private final Map<PropertyKey<?, ?>, Binding<?, ?>> bindings =
        new LinkedHashMap<>();

    PropertyRegistry(DefaultRuntimeDomain domain) {
        this.domain = domain;
    }

    <R, T> Disposable register(DefaultContext context, PropertyKey<R, T> key,
                               PropertyAccessor<R, T> accessor) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(accessor, "accessor");
        return domain.runtime().lifecycle().call(() -> {
            var owner = context.owner();
            if (!owner.acceptsResources()) {
                throw new FibraException(FibraException.SCOPE_CLOSED,
                    "owner \"" + owner.ownerName()
                        + "\" does not accept properties");
            }
            var binding = new Binding<>(key, accessor);
            if (bindings.putIfAbsent(key, binding) != null) {
                throw new IllegalArgumentException(
                    "property is already registered: " + key.name());
            }
            try {
                return new OwnedResource(owner,
                    Mono.just(() -> revoke(key, binding)),
                    "properties.register(\"" + key.name() + "\")");
            } catch (RuntimeException | Error failure) {
                bindings.remove(key, binding);
                throw failure;
            }
        });
    }

    <R, T> T get(DefaultContext caller, PropertyKey<R, T> key, R receiver) {
        var binding = binding(key, receiver);
        var value = binding.accessor().get(caller, receiver);
        if (value != null && !key.valueType().isInstance(value)) {
            throw new IllegalArgumentException(
                "property value is not a " + key.valueType().getName());
        }
        return value;
    }

    <R, T> void set(DefaultContext caller, PropertyKey<R, T> key,
                    R receiver, T value) {
        if (value != null && !key.valueType().isInstance(value)) {
            throw new IllegalArgumentException(
                "property value is not a " + key.valueType().getName());
        }
        binding(key, receiver).accessor().set(caller, receiver, value);
    }

    @SuppressWarnings("unchecked")
    private <R, T> Binding<R, T> binding(PropertyKey<R, T> key, R receiver) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(receiver, "receiver");
        if (!key.receiverType().isInstance(receiver)) {
            throw new IllegalArgumentException(
                "property receiver is not a " + key.receiverType().getName());
        }
        return domain.runtime().lifecycle().call(() -> {
            var binding = bindings.get(key);
            if (binding == null) {
                throw new IllegalStateException(
                    "property is not registered: " + key.name());
            }
            return (Binding<R, T>) binding;
        });
    }

    private Mono<Void> revoke(PropertyKey<?, ?> key, Binding<?, ?> binding) {
        return domain.runtime().lifecycle().mono(() -> {
            bindings.remove(key, binding);
            return null;
        });
    }

    private record Binding<R, T>(PropertyKey<R, T> key,
                                 PropertyAccessor<R, T> accessor) {
    }
}
