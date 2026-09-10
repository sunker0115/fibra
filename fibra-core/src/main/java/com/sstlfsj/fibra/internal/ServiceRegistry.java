package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.FibraException;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.ServiceRegistration;
import com.sstlfsj.fibra.runtime.RuntimeDomainSnapshot;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class ServiceRegistry {
    enum DefaultRealm { INSTANCE }

    static final Object DEFAULT_REALM = DefaultRealm.INSTANCE;

    private final DefaultRuntimeDomain domain;
    private final Map<Slot, Binding<?>> bindings = new LinkedHashMap<>();

    ServiceRegistry(DefaultRuntimeDomain domain) {
        this.domain = domain;
    }

    <T> ServiceRegistration<T> provide(DefaultContext context, ServiceKey<T> key, T value) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (!key.type().isInstance(value)) {
            throw new IllegalArgumentException("service value is not a " + key.type().getName());
        }
        return domain.runtime().lifecycle().call(() -> {
            var owner = context.owner();
            if (!owner.acceptsResources()) {
                throw new FibraException(FibraException.SCOPE_CLOSED,
                    "owner \"" + owner.ownerName() + "\" does not accept services");
            }
            if (owner instanceof PluginInstanceImpl<?> instance
                && !instance.provides(key)) {
                throw new FibraException(FibraException.PLUGIN_UNDECLARED_SERVICE,
                    "plugin instance \"" + instance.id()
                        + "\" did not declare service \"" + key.name() + "\"");
            }
            var slot = new Slot(key.name(), context.realm(key.name()));
            var existing = bindings.get(slot);
            if (existing != null) {
                throw new FibraException(FibraException.SERVICE_DUPLICATE,
                    "service \"" + key.name() + "\" is already registered by "
                        + existing.owner().ownerName());
            }
            var binding = new Binding<>(domain.runtime().nextSequence(), slot, key, value, owner);
            bindings.put(slot, binding);
            OwnedResource resource;
            try {
                resource = new OwnedResource(owner, Mono.just(() -> revoke(binding)),
                    "services.provide(\"" + key.name() + "\")");
            } catch (RuntimeException | Error failure) {
                bindings.remove(slot, binding);
                throw failure;
            }
            notifyChanged(slot);
            return new Registration<>(key, value, resource);
        });
    }

    <T> Optional<T> find(DefaultContext context, ServiceKey<T> key) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(key, "key");
        return domain.runtime().lifecycle().call(() -> {
            var binding = activeBinding(context, key);
            return binding == null ? Optional.empty() : Optional.of(key.type().cast(binding.value()));
        });
    }

    <T> T require(DefaultContext context, ServiceKey<T> key) {
        return find(context, key).orElseThrow(() -> new FibraException(
            FibraException.SERVICE_INACTIVE,
            "required service \"" + key.name() + "\" is inactive"));
    }

    Binding<?> activeBinding(DefaultContext context, ServiceKey<?> key) {
        if (context.owner() instanceof PluginInstanceImpl<?> instance && instance.dependsOn(key.name())) {
            return instance.serviceSnapshot(key.name());
        }
        return lookupActive(context, key);
    }

    Binding<?> lookupActive(DefaultContext context, ServiceKey<?> key) {
        var slot = new Slot(key.name(), context.realm(key.name()));
        var binding = bindings.get(slot);
        if (binding != null && binding.key().type() != key.type()) {
            throw new IllegalArgumentException("service \"" + key.name()
                + "\" in realm \"" + slot.realm() + "\" was declared as both "
                + binding.key().type().getName() + " and " + key.type().getName());
        }
        return binding != null && isActive(binding.owner()) ? binding : null;
    }

    void ownerStateChanged(ResourceOwner owner) {
        bindings.values().stream()
            .filter(binding -> binding.owner() == owner)
            .map(Binding::slot)
            .distinct()
            .forEach(this::notifyChanged);
    }

    List<RuntimeDomainSnapshot.Service> diagnosticSnapshot() {
        return bindings.values().stream()
            .map(binding -> new RuntimeDomainSnapshot.Service(
                new RuntimeDomainSnapshot.ServiceIdentity(binding.slot().name(),
                    binding.key().type().getName(),
                    String.valueOf(binding.slot().realm())),
                DefaultRuntimeDomain.ownerIdentity(binding.owner()), List.of()))
            .toList();
    }

    private Mono<Void> revoke(Binding<?> binding) {
        return domain.runtime().lifecycle().mono(() -> {
                if (!bindings.remove(binding.slot(), binding)) {
                    return List.<PluginInstanceImpl<?>>of();
                }
                var affected = notifyChanged(binding.slot());
                return affected;
            })
            .flatMapMany(Flux::fromIterable)
            .flatMap(instance -> instance.settled().then()
                .onErrorResume(error -> Mono.empty()))
            .then();
    }

    private List<PluginInstanceImpl<?>> notifyChanged(Slot slot) {
        var affected = domain.instancesSnapshot().stream()
            .filter(instance -> instance.dependsOn(slot.name()))
            .filter(instance -> Objects.equals(instance.contextImpl().realm(slot.name()), slot.realm()))
            .toList();
        affected.forEach(instance -> instance.dependencyChanged(slot.name()));
        return affected;
    }

    private boolean isActive(ResourceOwner owner) {
        if (owner instanceof PluginInstanceImpl<?> instance) {
            return instance.stateUnsafe() == PluginInstanceState.ACTIVE;
        }
        return owner.acceptsResources();
    }

    record Slot(String name, Object realm) {
        Slot {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(realm, "realm");
        }
    }

    record Binding<T>(long id, Slot slot, ServiceKey<T> key, T value, ResourceOwner owner) {
    }

    private record Registration<T>(ServiceKey<T> key, T value, OwnedResource resource)
        implements ServiceRegistration<T> {
        @Override
        public Mono<Void> dispose() {
            return resource.dispose();
        }
    }
}
