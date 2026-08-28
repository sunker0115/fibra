package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.*;
import com.sstlfsj.fibra.event.CoreEvents;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

final class DefaultFibra implements Fibra {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultFibra.class);
    private static final String INACTIVE = "__INACTIVE__";

    private final DefaultContext context;
    private final DefaultContext parentContext;
    private final LifecycleDispatcher lifecycle;
    private final PluginDescriptor<Object> descriptor;
    private final Plugin<Object> plugin;
    private final Object pluginIdentity;
    private final IdentityList<EffectHandleImpl> effects = new IdentityList<>();
    private final Map<String, Dependency> dependencies = new LinkedHashMap<>();
    private final Map<String, ServiceImpl<?>> candidates = new LinkedHashMap<>();
    private final Map<String, ServiceImpl<?>> ownedServices = new LinkedHashMap<>();
    private final boolean root;

    private Long uid;
    private FibraState state;
    private Map<String, ServiceImpl<?>> store;
    private Object rawConfig;
    private Object config;
    private String epoch;
    private boolean transitioning;
    private Sinks.One<Void> stable = Sinks.one();
    private Throwable error;
    private EffectHandle parentHandle;

    private DefaultFibra(DefaultContext rootContext) {
        this.context = rootContext;
        this.parentContext = rootContext;
        this.lifecycle = rootContext.lifecycle();
        this.descriptor = null;
        this.plugin = null;
        this.pluginIdentity = this;
        this.root = true;
        this.uid = 0L;
        this.state = FibraState.ACTIVE;
        this.store = new LinkedHashMap<>();
        this.epoch = "";
        stable.tryEmitEmpty();
    }

    @SuppressWarnings("unchecked")
    private <C> DefaultFibra(DefaultContext parent, PluginDescriptor<C> descriptor, Plugin<C> plugin,
                      Object pluginIdentity, C config, long uid) {
        this.parentContext = parent;
        this.lifecycle = parent.lifecycle();
        this.descriptor = (PluginDescriptor<Object>) Objects.requireNonNull(descriptor, "descriptor");
        this.plugin = (Plugin<Object>) Objects.requireNonNull(plugin, "plugin");
        this.pluginIdentity = Objects.requireNonNull(pluginIdentity, "pluginIdentity");
        this.root = false;
        this.uid = uid;
        this.state = FibraState.PENDING;
        this.rawConfig = config;
        this.epoch = INACTIVE;
        descriptor.dependencies().forEach((key, intercept) ->
            this.dependencies.put(key.name(), new Dependency(key.name(), key, intercept)));
        descriptor.namedDependencies().forEach((name, intercept) ->
            this.dependencies.put(name, new Dependency(name, null, intercept)));
        var dependencyIntercepts = new LinkedHashMap<String, Object>();
        dependencies.forEach((name, dependency) ->
            dependencyIntercepts.put(name, dependency.intercept()));
        this.context = parent.child(this, dependencyIntercepts);
    }

    public static DefaultFibra root(DefaultContext context) {
        return new DefaultFibra(context);
    }

    public static <C> DefaultFibra plugin(DefaultContext parent, PluginDescriptor<C> descriptor, Plugin<C> plugin,
                                   Object pluginIdentity, C config) {
        var fibra = new DefaultFibra(parent, descriptor, plugin, pluginIdentity, config, parent.nextUid());
        parent.pluginStore().add(pluginIdentity, fibra);
        fibra.parentHandle = parent.fibra().effect(() -> fibra::disposeInternal, "ctx.plugin()");
        fibra.context.emit(CoreEvents.PLUGIN, listener -> listener.onPlugin(fibra));
        for (var dependency : fibra.dependencies.values()) {
            fibra.checkDependency(dependency);
        }
        fibra.refresh();
        return fibra;
    }

    public Context context() {
        return context;
    }

    DefaultContext contextImpl() {
        return context;
    }

    public Context parentContext() {
        return parentContext;
    }

    DefaultContext parentContextImpl() {
        return parentContext;
    }

    public LifecycleDispatcher lifecycle() {
        return lifecycle;
    }

    public String name() {
        if (descriptor != null) {
            return descriptor.name();
        }
        return "root";
    }

    public Long uid() {
        return lifecycle.call(() -> uid);
    }

    public FibraState state() {
        return lifecycle.call(() -> state);
    }

    public FibraState stateUnsafe() {
        return state;
    }

    public Object config() {
        return lifecycle.call(() -> config);
    }

    public boolean isRoot() {
        return root;
    }

    public EffectHandle effect(Supplier<? extends Disposable> source, String label) {
        Objects.requireNonNull(source, "source");
        return lifecycle.call(() -> {
            assertCanCreateEffect();
            return new EffectHandleImpl(this,
                Mono.just(Objects.requireNonNull(source.get(), "effect source returned null")), label);
        });
    }

    public EffectHandle effectSync(SyncEffect source, String label) {
        Objects.requireNonNull(source, "source");
        return lifecycle.call(() -> {
            assertCanCreateEffect();
            var disposables = new ArrayList<Disposable>();
            try {
                source.apply(disposable -> disposables.add(
                    Objects.requireNonNull(disposable, "effect sink received null")));
            } catch (RuntimeException | Error failure) {
                for (int index = disposables.size() - 1; index >= 0; index--) {
                    try {
                        Objects.requireNonNull(disposables.get(index).dispose(),
                            "disposer returned null").block();
                    } catch (RuntimeException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
            return new EffectHandleImpl(this, Flux.fromIterable(disposables), label);
        });
    }

    public EffectHandle effectMany(Iterable<? extends Disposable> source, String label) {
        Objects.requireNonNull(source, "source");
        return effect(Flux.fromIterable(source), label);
    }

    public EffectHandle effect(Publisher<? extends Disposable> source, String label) {
        return lifecycle.call(() -> {
            assertCanCreateEffect();
            return new EffectHandleImpl(this, source, label == null ? null : label);
        });
    }

    public List<EffectMetadata> effects() {
        return lifecycle.call(() -> effects.snapshot().stream()
            .filter(EffectHandleImpl::hasMetadata)
            .map(EffectHandleImpl::metadata)
            .toList());
    }

    public Mono<Fibra> await() {
        return Mono.defer(() -> lifecycle.mono(() -> {
            if (transitioning) {
                return stable.asMono().then(Mono.defer(this::await));
            }
            if (error != null) {
                return Mono.<Fibra>error(error);
            }
            return Mono.just(this);
        }).flatMap(result -> result));
    }

    public Mono<Fibra> ready() {
        return await();
    }

    public Mono<Void> dispose() {
        if (root) {
            return restart().then();
        }
        return parentHandle.dispose();
    }

    public Mono<Fibra> restart() {
        lifecycle.call(() -> {
            assertActive();
            setEpoch(INACTIVE);
            refresh();
            return null;
        });
        return await();
    }

    public <C> Mono<Fibra> update(C newConfig) {
        return update(newConfig, false);
    }

    public <C> Mono<Fibra> update(C newConfig, boolean noSave) {
        return lifecycle.call(() -> {
            assertActive();
            rawConfig = newConfig;
            if (state != FibraState.ACTIVE) {
                error = null;
                setEpoch(INACTIVE);
                refresh();
                return await();
            }
            var validated = descriptor.validate(newConfig);
            Publisher<Fibra> result =
                context.<CoreEvents.UpdateListener, Publisher<Fibra>>waterfall(CoreEvents.UPDATE,
                (listener, next) -> listener.onUpdate(this, validated, noSave, next),
                () -> {
                    config = validated;
                    error = null;
                    setEpoch(INACTIVE);
                    refresh();
                    return await();
                });
            return Mono.from(Objects.requireNonNull(result, "internal/update returned null"));
        });
    }

    public boolean requires(String name) {
        return descriptor != null && dependencies.containsKey(name);
    }

    public void require(ServiceKey<?> key) {
        require(key, null);
    }

    public void require(ServiceKey<?> key, Object intercept) {
        Objects.requireNonNull(key, "key");
        require(key.name(), new Dependency(key.name(), key, intercept));
    }

    public void require(String serviceName) {
        require(serviceName, (Object) null);
    }

    public void require(String serviceName, Object intercept) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("service name must not be blank");
        }
        require(serviceName, new Dependency(serviceName, null, intercept));
    }

    private void require(String serviceName, Dependency dependency) {
        lifecycle.call(() -> {
            if (state != FibraState.PENDING || transitioning) {
                throw new IllegalStateException("dependencies can only be added while fibra is pending");
            }
            if (dependencies.containsKey(serviceName)) {
                throw new IllegalArgumentException(
                    "service dependency \"" + serviceName + "\" is already declared");
            }
            dependencies.put(serviceName, dependency);
            if (dependency.intercept() != null) {
                context.putIntercept(serviceName, dependency.intercept());
            }
            checkDependency(dependency);
            refresh();
            return null;
        });
    }

    public ServiceImpl<?> serviceSnapshot(String name) {
        return store == null ? null : store.get(name);
    }

    public void putOwnedService(ServiceImpl<?> impl) {
        ownedServices.put(impl.key().name(), impl);
        if (store != null) {
            store.put(impl.key().name(), impl);
        }
    }

    public void removeOwnedService(ServiceImpl<?> impl) {
        ownedServices.remove(impl.key().name(), impl);
        if (store != null) {
            store.remove(impl.key().name(), impl);
        }
    }

    public List<String> ownedServiceNames() {
        return List.copyOf(ownedServices.keySet());
    }

    public void addEffect(EffectHandleImpl handle) {
        effects.add(handle);
    }

    public void removeEffect(EffectHandleImpl handle) {
        effects.remove(handle);
    }

    public void dependencyChanged(String name) {
        if (!requires(name)) {
            return;
        }
        checkDependency(dependencies.get(name));
        refresh();
    }

    private void checkDependency(Dependency dependency) {
        var key = dependency.key();
        var impl = key == null
            ? context.reflect().lookupBinding(context, dependency.name(), true)
            : context.reflect().lookupBinding(context, key, true);
        var name = dependency.name();
        if (impl == null) {
            candidates.remove(name);
        } else {
            candidates.put(name, impl);
        }
    }

    private void refresh() {
        if (root) {
            setEpoch("");
            return;
        }
        var builder = new StringBuilder();
        for (var name : dependencies.keySet()) {
            var impl = candidates.get(name);
            if (impl == null) {
                setEpoch(INACTIVE);
                return;
            }
            builder.append(':').append(impl.fibra().uid);
        }
        setEpoch(builder.toString());
    }

    private record Dependency(String name, ServiceKey<?> key, Object intercept) {
    }

    private void setEpoch(String nextEpoch) {
        if (Objects.equals(epoch, nextEpoch)) {
            return;
        }
        var previous = epoch;
        epoch = nextEpoch;
        if (transitioning) {
            return;
        }
        transitioning = true;
        stable = Sinks.one();
        if (!INACTIVE.equals(nextEpoch) && INACTIVE.equals(previous)) {
            startReload(nextEpoch);
        } else {
            startUnload();
        }
    }

    private void startReload(String loadingEpoch) {
        setState(FibraState.LOADING);
        store = new LinkedHashMap<>(candidates);
        store.putAll(ownedServices);

        lifecycle.tick()
            .then(Mono.defer(() -> {
                if (!Objects.equals(epoch, loadingEpoch)) {
                    return Mono.empty();
                }
                if (root) {
                    return Mono.empty();
                }
                config = descriptor.validate(rawConfig);
                Publisher<? extends Disposable> result = plugin.apply(context, config);
                if (result == null) {
                    result = Mono.empty();
                }
                return effect(result, null).ready().then();
            }))
            .publishOn(lifecycle.scheduler())
            .subscribe(
                ignored -> {
                },
                this::reloadFailed,
                () -> reloadCompleted(loadingEpoch)
            );
    }

    private void reloadCompleted(String loadingEpoch) {
        if (Objects.equals(epoch, loadingEpoch)) {
            error = null;
            transitioning = false;
            setState(FibraState.ACTIVE);
            stable.tryEmitEmpty();
        } else {
            startUnload();
        }
    }

    private void reloadFailed(Throwable failure) {
        LOGGER.atError()
            .setCause(failure)
            .log("event=fibra.core.entry.load_failed entryId={}", name());
        error = failure;
        epoch = INACTIVE;
        startUnload();
    }

    private void startUnload() {
        setState(FibraState.UNLOADING);
        var handles = effects.drainReverse();
        var disposals = handles.stream()
            .map(handle -> handle.dispose().onErrorResume(failure -> {
                LOGGER.atError()
                    .setCause(failure)
                    .log("event=fibra.core.entry.effect_cleanup_failed entryId={}", name());
                return Mono.empty();
            }))
            .toList();

        lifecycle.tick()
            .then(Mono.when(disposals))
            .publishOn(lifecycle.scheduler())
            .subscribe(
                ignored -> {
                },
                failure -> {
                    LOGGER.atError()
                        .setCause(failure)
                        .log("event=fibra.core.entry.unload_failed entryId={}", name());
                    unloadCompleted();
                },
                this::unloadCompleted
            );
    }

    private void unloadCompleted() {
        store = null;
        if (INACTIVE.equals(epoch)) {
            transitioning = false;
            if (uid == null) {
                setState(FibraState.DISPOSED);
            } else if (error != null) {
                setState(FibraState.FAILED);
            } else {
                setState(FibraState.PENDING);
            }
            stable.tryEmitEmpty();
        } else {
            startReload(epoch);
        }
    }

    private Mono<Void> disposeInternal() {
        return lifecycle.run(() -> {
                if (uid == null) {
                    return;
                }
                context.pluginStore().remove(pluginIdentity, this);
                uid = null;
                setEpoch(INACTIVE);
                if (!transitioning && !effects.snapshot().isEmpty()) {
                    transitioning = true;
                    stable = Sinks.one();
                    startUnload();
                }
            })
            .then(await().onErrorResume(failure -> Mono.just(this)))
            .then();
    }

    private void setState(FibraState next) {
        var previous = state;
        state = next;
        if (previous != next) {
            context.emit(CoreEvents.STATUS, listener -> listener.onStatus(this, previous));
        }
        if ((previous == FibraState.ACTIVE) != (next == FibraState.ACTIVE)) {
            context.reflect().providerStateChanged(this);
        }
    }

    private void assertCanCreateEffect() {
        assertActive();
        if (state == FibraState.UNLOADING) {
            throw new FibraException(FibraException.EFFECT_INACTIVE,
                "cannot create effect on inactive context");
        }
    }

    private void assertActive() {
        if (uid == null) {
            throw new FibraException(FibraException.EFFECT_INACTIVE,
                "cannot create effect on inactive context");
        }
    }
}
