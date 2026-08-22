package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.*;
import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.EventOptions;
import com.sstlfsj.fibra.event.EventTarget;
import com.sstlfsj.fibra.event.Next;
import com.sstlfsj.fibra.event.CoreEvents;
import com.sstlfsj.fibra.logging.FibraLogger;
import com.sstlfsj.fibra.logging.LoggerService;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.ArrayList;
import java.util.List;

public final class DefaultContext implements Context {
    private final DefaultContext parent;
    private final DefaultContext root;
    private final LifecycleDispatcher lifecycle;
    private final ReflectRegistry reflect;
    private final PluginStore pluginStore;
    private final DefaultPluginRegistry registry;
    private final EventBus events;
    private final DefaultLoggerService logging;
    private final DefaultFibra fibra;
    private final Map<String, Object> isolates = new HashMap<>();
    private final Map<String, Object> intercepts = new HashMap<>();
    private final Map<String, Object> metadata = new LinkedHashMap<>();
    private final AtomicBoolean closed;

    private long uidCounter;

    public DefaultContext() {
        this.parent = null;
        this.root = this;
        this.lifecycle = new LifecycleDispatcher("fibra-lifecycle");
        this.pluginStore = new PluginStore();
        this.registry = new DefaultPluginRegistry(pluginStore, lifecycle);
        this.events = new EventBus(lifecycle);
        this.closed = new AtomicBoolean();
        this.reflect = new ReflectRegistry(this, lifecycle);
        this.fibra = DefaultFibra.root(this);
        this.logging = new DefaultLoggerService(this);
    }

    private DefaultContext(DefaultContext parent, DefaultFibra fibra) {
        this.parent = Objects.requireNonNull(parent, "parent");
        this.root = parent.root;
        this.lifecycle = parent.lifecycle;
        this.pluginStore = parent.pluginStore;
        this.registry = parent.registry;
        this.events = parent.events;
        this.logging = parent.logging;
        this.closed = parent.closed;
        this.reflect = parent.reflect;
        this.fibra = Objects.requireNonNull(fibra, "fibra");
    }

    DefaultContext child(DefaultFibra fibra, Map<ServiceKey<?>, Object> dependencies) {
        var child = new DefaultContext(this, fibra);
        dependencies.forEach((key, config) -> {
            if (config != null) {
                child.intercepts.put(key.name(), config);
            }
        });
        return child;
    }

    public Context root() {
        return root;
    }

    public Fibra fibra() {
        return fibra;
    }

    DefaultFibra fibraImpl() {
        return fibra;
    }

    public Context extend() {
        return extend(Map.of());
    }

    public Context extend(Map<String, ?> values) {
        var child = new DefaultContext(this, fibra);
        child.metadata.putAll(values);
        return child;
    }

    public Object metadata(String name) {
        for (DefaultContext current = this; current != null; current = current.parent) {
            if (current.metadata.containsKey(name)) {
                return current.metadata.get(name);
            }
        }
        return null;
    }

    public <T> Context isolate(ServiceKey<T> key) {
        return isolate(key, new Object());
    }

    public <T> Context isolate(ServiceKey<T> key, Object label) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        var child = new DefaultContext(this, fibra);
        child.isolates.put(key.name(), label);
        return child;
    }

    public <T> Context intercept(ServiceKey<T> key, Object config) {
        Objects.requireNonNull(key, "key");
        var child = new DefaultContext(this, fibra);
        child.intercepts.put(key.name(), config);
        return child;
    }

    public Context intercept(String name, Object config) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("intercept name must not be blank");
        }
        var child = new DefaultContext(this, fibra);
        child.intercepts.put(name, config);
        return child;
    }

    public Object intercept(ServiceKey<?> key) {
        for (DefaultContext current = this; current != null; current = current.parent) {
            if (current.intercepts.containsKey(key.name())) {
                return current.intercepts.get(key.name());
            }
        }
        return null;
    }

    public List<Object> interceptValues(String name) {
        var values = new ArrayList<Object>();
        for (DefaultContext current = this; current != null; current = current.parent) {
            if (current.intercepts.containsKey(name)) {
                values.add(0, current.intercepts.get(name));
            }
        }
        return List.copyOf(values);
    }

    public BoundLoggerService logging() {
        return new BoundLoggerService(logging, this);
    }

    public FibraLogger logger() {
        return logging.logger(this, null, null);
    }

    public FibraLogger logger(String name) {
        return logging.logger(this, Objects.requireNonNull(name, "name"), null);
    }

    FibraLogger loggerDerived(String name) {
        return logging.logger(this, null, name);
    }

    public <T> ServiceRegistration<T> provide(ServiceKey<T> key, T value) {
        ensureOpen();
        return reflect.provide(this, key, value);
    }

    public <T> T get(ServiceKey<T> key) {
        return get(key, true);
    }

    public <T> T get(ServiceKey<T> key, boolean strict) {
        ensureOpen();
        return key.type().cast(this.<CoreEvents.GetListener, Object>waterfall(CoreEvents.GET,
            (listener, next) -> listener.onGet(this, key, next),
            () -> reflect.getDirect(this, key, strict)));
    }

    public <T> void set(ServiceKey<T> key, T value) {
        ensureOpen();
        waterfall(CoreEvents.SET,
            (listener, next) -> listener.onSet(this, key, value, next),
            () -> {
                reflect.setDirect(this, key, value);
                return true;
            });
    }

    public <R, T> EffectHandle accessor(PropertyKey<R, T> key,
                                         PropertyAccessor<R, T> accessor) {
        ensureOpen();
        return reflect.accessor(this, key, accessor);
    }

    public <R, T> T get(PropertyKey<R, T> key, R receiver) {
        ensureOpen();
        return reflect.getProperty(this, key, receiver);
    }

    public <R, T> void set(PropertyKey<R, T> key, R receiver, T value) {
        ensureOpen();
        reflect.setProperty(this, key, receiver, value);
    }

    public EffectHandle effect(Supplier<? extends Disposable> source) {
        return effect(source, "anonymous");
    }

    public EffectHandle effect(Supplier<? extends Disposable> source, String label) {
        ensureOpen();
        return fibra.effect(source, label);
    }

    public EffectHandle effectSync(SyncEffect source) {
        return effectSync(source, "anonymous");
    }

    public EffectHandle effectSync(SyncEffect source, String label) {
        ensureOpen();
        return fibra.effectSync(source, label);
    }

    public EffectHandle effectMany(Iterable<? extends Disposable> source) {
        return effectMany(source, "anonymous");
    }

    public EffectHandle effectMany(Iterable<? extends Disposable> source, String label) {
        ensureOpen();
        return fibra.effectMany(source, label);
    }

    public EffectHandle effect(Publisher<? extends Disposable> source) {
        return effect(source, "anonymous");
    }

    public EffectHandle effect(Publisher<? extends Disposable> source, String label) {
        ensureOpen();
        return fibra.effect(source, label);
    }

    public <C> Fibra plugin(PluginDescriptor<C> descriptor, Plugin<C> plugin, C config) {
        ensureOpen();
        return lifecycle.call(() -> DefaultFibra.plugin(this, descriptor, plugin, plugin, config));
    }

    public <C> Fibra plugin(String name, Plugin<C> plugin, C config) {
        return plugin(PluginDescriptor.<C>builder(name).build(), plugin, config);
    }

    public <C, P> Fibra plugin(PluginDescriptor<C> descriptor,
                               PluginFactory<C, P> factory,
                               PluginInitializer<P> initializer,
                               C config) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(initializer, "initializer");
        Plugin<C> adapter = (pluginContext, pluginConfig) -> Flux.defer(() -> {
            var instance = factory.create(pluginContext, pluginConfig);
            InjectProcessor.prepare(instance, pluginContext);
            return Flux.from(initializer.initialize(instance)).handle((value, sink) -> {
                if (value instanceof Disposable disposable) {
                    sink.next(disposable);
                } else if (value != null) {
                    sink.error(new IllegalArgumentException(
                        "plugin initializer emitted a non-disposable value"));
                }
            });
        });
        ensureOpen();
        return lifecycle.call(() -> DefaultFibra.plugin(this, descriptor, adapter, factory, config));
    }

    public <L> Disposable on(EventKey<L> key, L listener) {
        return on(key, listener, EventOptions.defaults());
    }

    public <L> Disposable on(EventKey<L> key, L listener, EventOptions options) {
        ensureOpen();
        return events.on(this, key, listener, options, false);
    }

    public <L> Disposable once(EventKey<L> key, L listener) {
        return once(key, listener, EventOptions.defaults());
    }

    public <L> Disposable once(EventKey<L> key, L listener, EventOptions options) {
        ensureOpen();
        return events.on(this, key, listener, options, true);
    }

    public <L> void emit(EventKey<L> key, Consumer<? super L> invocation) {
        emit(null, key, invocation);
    }

    public <L> void emit(EventTarget target, EventKey<L> key, Consumer<? super L> invocation) {
        if (!key.name().startsWith("internal/")) {
            ensureOpen();
        }
        events.emit(target, key, invocation);
    }

    public <L> Mono<Void> parallel(EventKey<L> key,
                                   Function<? super L, ? extends Publisher<?>> invocation) {
        return parallel(null, key, invocation);
    }

    public <L> Mono<Void> parallel(EventTarget target, EventKey<L> key,
                                   Function<? super L, ? extends Publisher<?>> invocation) {
        ensureOpen();
        return events.parallel(target, key, invocation);
    }

    public <L, R> Mono<R> serial(EventKey<L> key,
                                 Function<? super L, ? extends Publisher<R>> invocation) {
        return serial(null, key, invocation);
    }

    public <L, R> Mono<R> serial(EventTarget target, EventKey<L> key,
                                 Function<? super L, ? extends Publisher<R>> invocation) {
        ensureOpen();
        return events.serial(target, key, invocation);
    }

    public <L, R> R bail(EventKey<L> key, Function<? super L, ? extends R> invocation) {
        return bail(null, key, invocation);
    }

    public <L, R> R bail(EventTarget target, EventKey<L> key,
                         Function<? super L, ? extends R> invocation) {
        ensureOpen();
        return events.bail(target, key, invocation);
    }

    public <L, R> R waterfall(EventKey<L> key,
                              BiFunction<? super L, Next<R>, ? extends R> invocation,
                              Supplier<? extends R> inner) {
        return waterfall(null, key, invocation, inner);
    }

    public <L, R> R waterfall(EventTarget target, EventKey<L> key,
                              BiFunction<? super L, Next<R>, ? extends R> invocation,
                              Supplier<? extends R> inner) {
        ensureOpen();
        return events.waterfall(target, key, invocation, inner);
    }

    public Mono<Void> closeAsync() {
        if (this != root) {
            return root.closeAsync();
        }
        if (!closed.compareAndSet(false, true)) {
            return Mono.empty();
        }
        return fibra.restart()
            .onErrorResume(error -> Mono.empty())
            .then()
            .doFinally(signal -> lifecycle.shutdown());
    }

    LifecycleDispatcher lifecycle() {
        return lifecycle;
    }

    ReflectRegistry reflect() {
        return reflect;
    }

    PluginStore pluginStore() {
        return pluginStore;
    }

    public com.sstlfsj.fibra.PluginRegistry registry() {
        return registry;
    }

    long nextUid() {
        return ++root.uidCounter;
    }

    Object isolateToken(String name) {
        for (DefaultContext current = this; current != null; current = current.parent) {
            if (current.isolates.containsKey(name)) {
                return current.isolates.get(name);
            }
        }
        return null;
    }

    void ensureServiceToken(String name) {
        root.isolates.computeIfAbsent(name, ignored -> new Object());
    }

    void putIntercept(String name, Object value) {
        intercepts.put(name, value);
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new FibraException(FibraException.CONTEXT_CLOSED, "context is closed");
        }
    }
}
