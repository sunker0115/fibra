package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.EffectHandle;
import com.sstlfsj.fibra.FibraState;
import com.sstlfsj.fibra.FibraException;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.ServiceRegistration;
import com.sstlfsj.fibra.PropertyAccessor;
import com.sstlfsj.fibra.PropertyKey;
import com.sstlfsj.fibra.event.CoreEvents;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ReflectRegistry {
    private final DefaultContext root;
    private final LifecycleDispatcher lifecycle;
    private final Map<Object, ServiceImpl<?>> bindings = new IdentityHashMap<>();
    private final Map<String, Class<?>> declaredTypes = new HashMap<>();
    private final Map<String, AccessorBinding<?, ?>> accessors = new HashMap<>();

    public ReflectRegistry(DefaultContext root, LifecycleDispatcher lifecycle) {
        this.root = root;
        this.lifecycle = lifecycle;
    }

    public <T> ServiceRegistration<T> provide(DefaultContext context, ServiceKey<T> key, T value) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (!key.type().isInstance(value)) {
            throw new IllegalArgumentException("service value is not a " + key.type().getName());
        }

        return lifecycle.call(() -> {
            declare(key);
            if (accessors.containsKey(key.name())) {
                throw new IllegalStateException("property \"" + key.name()
                    + "\" is already declared as accessor");
            }
            root.ensureServiceToken(key.name());
            var token = context.isolateToken(key.name());
            if (bindings.containsKey(token)) {
                var existing = bindings.get(token);
                throw new FibraException(FibraException.SERVICE_DUPLICATE,
                    "service \"" + key.name() + "\" has been registered at <"
                        + existing.fibra().name() + ">");
            }

            var impl = new ServiceImpl<>(key, value, context.fibraImpl(), token);
            bindings.put(token, impl);
            context.fibraImpl().putOwnedService(impl);
            if (context.fibraImpl().stateUnsafe() == FibraState.ACTIVE) {
                notifyChanged(key.name(), context);
            }

            EffectHandle handle = context.fibraImpl().effect(() -> () -> revoke(impl, context),
                "ctx.provide(\"" + key.name() + "\")");
            return new Registration<>(key, value, handle);
        });
    }

    public <T> T getDirect(DefaultContext context, ServiceKey<T> key, boolean strict) {
        return lifecycle.call(() -> {
            declare(key);
            ServiceImpl<?> impl = context.fibraImpl().isRoot()
                ? lookupBinding(context, key, strict)
                : resolveFromFibra(context, key.name(), strict);
            if (impl == null) {
                return null;
            }
            return key.type().cast(impl.value());
        });
    }

    public <T> void setDirect(DefaultContext context, ServiceKey<T> key, T value) {
        lifecycle.call(() -> {
            declare(key);
            var impl = bindings.get(context.isolateToken(key.name()));
            if (impl == null) {
                throw new IllegalStateException("cannot set service \"" + key.name() + "\" without provide");
            }
            if (impl.fibra() != context.fibraImpl()) {
                throw new IllegalStateException("cannot set service \"" + key.name() + "\" in multiple fibras");
            }
            @SuppressWarnings("unchecked")
            var typed = (ServiceImpl<T>) impl;
            typed.value(key.type().cast(value));
            return null;
        });
    }

    public <R, T> EffectHandle accessor(DefaultContext context, PropertyKey<R, T> key,
                                         PropertyAccessor<R, T> accessor) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(accessor, "accessor");
        return context.effect(() -> {
            var binding = new AccessorBinding<>(key, accessor);
            lifecycle.call(() -> {
                if (declaredTypes.containsKey(key.name())) {
                    throw new IllegalStateException("property \"" + key.name()
                        + "\" is already declared as service");
                }
                if (accessors.putIfAbsent(key.name(), binding) != null) {
                    throw new IllegalStateException("property \"" + key.name()
                        + "\" is already declared as accessor");
                }
                return null;
            });
            return () -> lifecycle.run(() -> accessors.remove(key.name(), binding));
        }, "ctx.accessor(\"" + key.name() + "\")");
    }

    public <R, T> T getProperty(DefaultContext context, PropertyKey<R, T> key, R receiver) {
        return lifecycle.call(() -> binding(key).get(context, receiver));
    }

    public <R, T> void setProperty(DefaultContext context, PropertyKey<R, T> key, R receiver,
                                   T value) {
        lifecycle.call(() -> {
            binding(key).set(context, receiver, value);
            return null;
        });
    }

    public ServiceImpl<?> lookupBinding(DefaultContext context, ServiceKey<?> key, boolean strict) {
        declare(key);
        var impl = bindings.get(context.isolateToken(key.name()));
        if (impl == null) {
            return null;
        }
        if (strict && impl.fibra().stateUnsafe() != FibraState.ACTIVE) {
            return null;
        }
        return impl;
    }

    public void providerStateChanged(DefaultFibra provider) {
        var names = provider.ownedServiceNames();
        if (names.isEmpty()) {
            return;
        }
        for (var name : names) {
            notifyChanged(name, provider.contextImpl());
        }
    }

    private Mono<Void> revoke(ServiceImpl<?> impl, DefaultContext providerContext) {
        return lifecycle.mono(() -> {
                if (bindings.get(impl.isolateToken()) != impl) {
                return List.<DefaultFibra>of();
                }
                bindings.remove(impl.isolateToken());
                return notifyChanged(impl.key().name(), providerContext);
            })
            .flatMapMany(Flux::fromIterable)
            .flatMap(fibra -> fibra.await().onErrorResume(error -> Mono.empty()))
            .then(lifecycle.run(() -> {
                impl.fibra().removeOwnedService(impl);
                var name = impl.key().name();
                var stillBound = bindings.values().stream()
                    .anyMatch(binding -> binding.key().name().equals(name));
                if (!stillBound) {
                    declaredTypes.remove(name, impl.key().type());
                }
            }));
    }

    private ServiceImpl<?> resolveFromFibra(DefaultContext context, String name, boolean strict) {
        var token = context.isolateToken(name);
        var fibra = context.fibraImpl();
        while (true) {
            var impl = fibra.serviceSnapshot(name);
            if (impl != null && impl.isolateToken() == token
                && (!strict || impl.fibra().stateUnsafe() == FibraState.ACTIVE)) {
                return impl;
            }
            if (fibra.requires(name)) {
                return null;
            }
            if (fibra.isRoot()) {
                return null;
            }
            if (fibra.parentContextImpl().isolateToken(name) != token) {
                return null;
            }
            fibra = fibra.parentContextImpl().fibraImpl();
        }
    }

    private List<DefaultFibra> notifyChanged(String name, DefaultContext source) {
        var affected = root.pluginStore().fibras().stream()
            .map(DefaultFibra.class::cast)
            .filter(fibra -> fibra.requires(name))
            .filter(fibra -> fibra.contextImpl().isolateToken(name) == source.isolateToken(name))
            .toList();
        for (var fibra : affected) {
            fibra.dependencyChanged(name);
        }
        ServiceKey<?> key = bindings.values().stream()
            .filter(impl -> impl.key().name().equals(name))
            .filter(impl -> impl.isolateToken() == source.isolateToken(name))
            .<ServiceKey<?>>map(ServiceImpl::key)
            .findFirst()
            .orElseGet(() -> declaredKey(name));
        var value = bindings.get(source.isolateToken(name));
        source.emit(CoreEvents.SERVICE,
            listener -> listener.onService(key, value == null ? null : value.value()));
        return affected;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ServiceKey<?> declaredKey(String name) {
        return ServiceKey.of(name, (Class) declaredTypes.getOrDefault(name, Object.class));
    }

    private void declare(ServiceKey<?> key) {
        var previous = declaredTypes.putIfAbsent(key.name(), key.type());
        if (previous != null && previous != key.type()) {
            throw new IllegalArgumentException("service \"" + key.name()
                + "\" was declared as both " + previous.getName() + " and " + key.type().getName());
        }
    }

    @SuppressWarnings("unchecked")
    private <R, T> AccessorBinding<R, T> binding(PropertyKey<R, T> key) {
        var binding = accessors.get(key.name());
        if (binding == null) {
            throw new IllegalStateException("property \"" + key.name() + "\" is not declared");
        }
        if (binding.key.receiverType() != key.receiverType()
            || binding.key.valueType() != key.valueType()) {
            throw new IllegalArgumentException("property \"" + key.name()
                + "\" was declared with different types");
        }
        return (AccessorBinding<R, T>) binding;
    }

    private record AccessorBinding<R, T>(PropertyKey<R, T> key,
                                          PropertyAccessor<R, T> accessor) {
        T get(DefaultContext context, R receiver) {
            return key.valueType().cast(accessor.get(context, key.receiverType().cast(receiver)));
        }

        void set(DefaultContext context, R receiver, T value) {
            accessor.set(context, key.receiverType().cast(receiver), key.valueType().cast(value));
        }
    }

    private record Registration<T>(ServiceKey<T> key, T value, EffectHandle handle)
        implements ServiceRegistration<T> {
        @Override
        public Mono<Void> dispose() {
            return handle.dispose();
        }
    }
}
