package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.event.AggregateEventException;
import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.EventMode;
import com.sstlfsj.fibra.event.EventOptions;
import com.sstlfsj.fibra.event.EventTarget;
import com.sstlfsj.fibra.event.Next;
import com.sstlfsj.fibra.runtime.RuntimeDomainSnapshot;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class EventBus {
    private final DefaultRuntimeDomain domain;
    private final Map<String, Contract> contracts = new HashMap<>();
    private final Map<String, List<Hook<?>>> hooks = new HashMap<>();

    EventBus(DefaultRuntimeDomain domain) {
        this.domain = domain;
    }

    <L> Disposable on(DefaultContext context, EventKey<L> key, L listener,
                      EventOptions options, boolean once) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(options, "options");
        if (!key.listenerType().isInstance(listener)) {
            throw new IllegalArgumentException("listener is not a " + key.listenerType().getName());
        }
        return domain.runtime().lifecycle().call(() -> {
            declare(key);
            var hook = new Hook<>(context, listener, options, once);
            var eventHooks = hooks.computeIfAbsent(key.name(), ignored -> new ArrayList<>());
            if (options.isPrepend()) {
                eventHooks.addFirst(hook);
            } else {
                eventHooks.add(hook);
            }
            try {
                return new OwnedResource(context.owner(),
                    Mono.just(() -> domain.runtime().lifecycle().run(() -> unregister(key, hook))),
                    "events.on(\"" + key.name() + "\")");
            } catch (RuntimeException | Error failure) {
                unregister(key, hook);
                throw failure;
            }
        });
    }

    <L> void emit(EventTarget target, EventKey<L> key, Consumer<? super L> invocation) {
        Objects.requireNonNull(invocation, "invocation");
        domain.runtime().lifecycle().call(() -> {
            for (var hook : resolve(target, key, EventMode.EMIT)) {
                beforeInvoke(key, hook);
                invocation.accept(hook.listener());
            }
            return null;
        });
    }

    <L> Mono<Void> parallel(EventTarget target, EventKey<L> key,
                            Function<? super L, ? extends Publisher<?>> invocation) {
        Objects.requireNonNull(invocation, "invocation");
        var snapshot = domain.runtime().lifecycle().call(() ->
            resolve(target, key, EventMode.PARALLEL));
        return Mono.defer(() -> {
            var failures = new ConcurrentLinkedQueue<Throwable>();
            return Flux.fromIterable(snapshot)
                .flatMap(hook -> Mono.defer(() -> {
                        domain.runtime().lifecycle().call(() -> {
                            beforeInvoke(key, hook);
                            return null;
                        });
                        return Flux.from(Objects.requireNonNull(invocation.apply(hook.listener()),
                            "event invocation returned null")).then();
                    }).onErrorResume(error -> {
                        failures.add(error);
                        return Mono.empty();
                    }))
                .then(Mono.defer(() -> failures.isEmpty()
                    ? Mono.empty()
                    : Mono.error(new AggregateEventException(List.copyOf(failures)))));
        });
    }

    <L, R> Mono<R> serial(EventTarget target, EventKey<L> key,
                          Function<? super L, ? extends Publisher<R>> invocation) {
        Objects.requireNonNull(invocation, "invocation");
        var snapshot = domain.runtime().lifecycle().call(() ->
            resolve(target, key, EventMode.SERIAL));
        return Flux.fromIterable(snapshot)
            .concatMap(hook -> Mono.defer(() -> {
                domain.runtime().lifecycle().call(() -> {
                    beforeInvoke(key, hook);
                    return null;
                });
                return Mono.from(Objects.requireNonNull(invocation.apply(hook.listener()),
                    "event invocation returned null"));
            }).filter(EventBus::isBailed), 1)
            .next();
    }

    <L, R> R bail(EventTarget target, EventKey<L> key,
                  Function<? super L, ? extends R> invocation) {
        Objects.requireNonNull(invocation, "invocation");
        return domain.runtime().lifecycle().call(() -> {
            for (var hook : resolve(target, key, EventMode.BAIL)) {
                beforeInvoke(key, hook);
                var result = invocation.apply(hook.listener());
                if (isBailed(result)) {
                    return result;
                }
            }
            return null;
        });
    }

    <L, R> R waterfall(EventTarget target, EventKey<L> key,
                       BiFunction<? super L, Next<R>, ? extends R> invocation,
                       Supplier<? extends R> inner) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(inner, "inner");
        return domain.runtime().lifecycle().call(() -> waterfall(
            resolve(target, key, EventMode.WATERFALL), key, invocation, inner, 0));
    }

    private <L, R> R waterfall(List<Hook<L>> snapshot, EventKey<L> key,
                               BiFunction<? super L, Next<R>, ? extends R> invocation,
                               Supplier<? extends R> inner, int index) {
        if (index == snapshot.size()) {
            return inner.get();
        }
        var hook = snapshot.get(index);
        beforeInvoke(key, hook);
        return invocation.apply(hook.listener(),
            () -> waterfall(snapshot, key, invocation, inner, index + 1));
    }

    @SuppressWarnings("unchecked")
    private <L> List<Hook<L>> resolve(EventTarget target, EventKey<L> key,
                                      EventMode expectedMode) {
        if (target instanceof EventTarget.BoundContext bound
            && (!(bound.context() instanceof DefaultContext context)
                || context.domain() != domain)) {
            throw new IllegalArgumentException(
                "event target belongs to another runtime domain");
        }
        if (key.mode() != expectedMode) {
            throw new IllegalArgumentException("event \"" + key.name() + "\" declares "
                + key.mode() + " but was dispatched as " + expectedMode);
        }
        declare(key);
        return hooks.getOrDefault(key.name(), List.of()).stream()
            .filter(hook -> hook.options().isGlobal()
                || target == null
                || target.accepts(hook.context()))
            .map(hook -> (Hook<L>) hook)
            .toList();
    }

    private void beforeInvoke(EventKey<?> key, Hook<?> hook) {
        if (hook.once()) {
            unregister(key, hook);
        }
    }

    private void unregister(EventKey<?> key, Hook<?> hook) {
        var eventHooks = hooks.get(key.name());
        if (eventHooks == null) {
            return;
        }
        eventHooks.removeIf(candidate -> candidate == hook);
        if (eventHooks.isEmpty()) {
            hooks.remove(key.name());
        }
    }

    private void declare(EventKey<?> key) {
        var contract = new Contract(key.listenerType(), key.mode());
        var previous = contracts.putIfAbsent(key.name(), contract);
        if (previous != null && !previous.equals(contract)) {
            throw new IllegalArgumentException("event \"" + key.name()
                + "\" has conflicting contracts");
        }
    }

    List<RuntimeDomainSnapshot.Event> diagnosticSnapshot() {
        return contracts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> {
                var contract = entry.getValue();
                var listeners = hooks.getOrDefault(entry.getKey(), List.of());
                var observed = new ArrayList<RuntimeDomainSnapshot.Listener>();
                for (var index = 0; index < listeners.size(); index++) {
                    var hook = listeners.get(index);
                    observed.add(new RuntimeDomainSnapshot.Listener(
                        DefaultRuntimeDomain.ownerIdentity(
                            ((DefaultContext) hook.context()).owner()), index,
                        hook.once(), hook.options().isGlobal()));
                }
                return new RuntimeDomainSnapshot.Event(entry.getKey(), contract.mode(),
                    contract.listenerType().getName(), observed);
            })
            .toList();
    }

    private static boolean isBailed(Object value) {
        return value != null && !Boolean.FALSE.equals(value);
    }

    private record Hook<L>(Context context, L listener, EventOptions options, boolean once) {
    }

    private record Contract(Class<?> listenerType, EventMode mode) {
    }
}
