package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.FibraException;
import com.sstlfsj.fibra.DrainingDisposable;
import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstance;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.runtime.RuntimeDomainSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

final class PluginInstanceImpl<C> implements PluginInstance<C>, ResourceOwner, DrainingDisposable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PluginInstanceImpl.class);

    private final DefaultFibraRuntime runtime;
    private final DefaultRuntimeDomain domain;
    private final DefaultScope scope;
    private final String id;
    private final long identity;
    private final Long parentIdentity;
    private final PluginDefinition<C> definition;
    private final Plugin<C> plugin;
    private final DefaultContext context;
    private final Map<String, ServiceKey<?>> requirements = new LinkedHashMap<>();
    private final Map<String, ServiceRegistry.Binding<?>> candidates = new LinkedHashMap<>();
    private final IdentityList<OwnedEffect> resources = new IdentityList<>();
    private final Sinks.One<Void> disposed = Sinks.one();
    private final Sinks.Many<PluginInstanceState> states = Sinks.many().replay().latest();

    private volatile PluginInstanceState state = PluginInstanceState.PENDING;
    private C validatedConfig;
    private volatile C config;
    private Map<String, ServiceRegistry.Binding<?>> serviceSnapshot = Map.of();
    private ActivationEpoch currentEpoch;
    private ActivationEpoch targetEpoch;
    private long configRevision;
    private boolean transitioning;
    private boolean disposeRequested;
    private volatile Throwable error;
    private Sinks.One<PluginInstance<C>> stable = completedSignal();
    private ResourceDrain drain;
    private boolean ownershipFrozen;
    private Sinks.One<Void> activationCleanup;

    PluginInstanceImpl(DefaultContext parentContext, String id,
                       PluginDefinition.Prepared<C> prepared) {
        runtime = parentContext.runtime();
        domain = parentContext.domain();
        scope = parentContext.scopeImpl();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("plugin instance id must not be blank");
        }
        this.id = id;
        identity = runtime.nextSequence();
        var parent = parentContext.owner().pluginInstance();
        parentIdentity = parent == null ? null : parent.identity();
        this.definition = Objects.requireNonNull(prepared, "prepared").definition();
        validatedConfig = prepared.config();
        plugin = Objects.requireNonNull(definition.factory().create(),
            "plugin factory returned null");
        definition.requires().keySet().forEach(key -> requirements.put(key.name(), key));
        context = parentContext.forOwner(this, definition.requires());
        states.tryEmitNext(state);
    }

    void initialize() {
        domain.addInstance(this);
        refreshDependencies();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public long identity() {
        return identity;
    }

    @Override
    public DefaultRuntimeDomain domain() { return domain; }

    @Override
    public DefaultScope scope() { return scope; }

    @Override
    public PluginDefinition<C> definition() {
        return definition;
    }

    @Override
    public com.sstlfsj.fibra.Context context() {
        return context;
    }

    @Override
    public PluginInstanceState state() {
        return state;
    }

    @Override
    public Flux<PluginInstanceState> states() {
        return states.asFlux();
    }

    PluginInstanceState stateUnsafe() {
        return state;
    }

    @Override
    public C config() {
        return config;
    }

    @Override
    public Optional<Throwable> failure() {
        return Optional.ofNullable(error);
    }

    @Override
    public Mono<PluginInstance<C>> settled() {
        if (state == PluginInstanceState.DISPOSED) {
            return Mono.just(this);
        }
        return Mono.defer(() -> lifecycle().mono(() -> {
            if (transitioning) {
                return stable.asMono();
            }
            if (error != null) {
                return Mono.<PluginInstance<C>>error(error);
            }
            return Mono.just(this);
        }).flatMap(result -> result));
    }

    @Override
    public Mono<PluginInstance<C>> update(C nextConfig) {
        return update(() -> definition.prepare(nextConfig));
    }

    @Override
    public Mono<PluginInstance<C>> updatePrepared(PluginDefinition.Prepared<C> prepared) {
        return update(() -> prepared);
    }

    private Mono<PluginInstance<C>> update(Supplier<PluginDefinition.Prepared<C>> prepared) {
        return domain.update(this, prepared);
    }

    void validateBatchUpdate(PluginDefinition.Prepared<?> prepared) {
        validateUpdateAvailable();
        if (Objects.requireNonNull(prepared, "prepared").definition() != definition) {
            throw new IllegalArgumentException(
                "prepared config belongs to another plugin definition");
        }
    }

    void validateUpdateAvailable() {
        if (disposeRequested || state == PluginInstanceState.DISPOSED) {
            throw new FibraException(FibraException.PLUGIN_DISPOSED,
                "plugin instance \"" + id + "\" is disposed");
        }
    }

    @SuppressWarnings("unchecked")
    void registerBatchUpdate(PluginDefinition.Prepared<?> prepared) {
        var typed = (PluginDefinition.Prepared<C>) prepared;
        validatedConfig = typed.config();
        configRevision++;
        error = null;
        if (state == PluginInstanceState.FAILED) {
            state = PluginInstanceState.PENDING;
            currentEpoch = null;
        }
        domain.diagnosticChanged();
    }

    void convergeBatchUpdate() {
        refreshDependencies();
    }

    @Override
    public Mono<Void> dispose() {
        if (state == PluginInstanceState.DISPOSED) {
            return disposed.asMono();
        }
        lifecycle().call(() -> {
            if (disposeRequested || state == PluginInstanceState.DISPOSED) {
                return null;
            }
            disposeRequested = true;
            targetEpoch = null;
            if (!transitioning) {
                beginTransition();
                if (state == PluginInstanceState.ACTIVE || !resources.snapshot().isEmpty()) {
                    startStop();
                } else {
                    finishDisposed(null);
                }
            }
            return null;
        });
        return disposed.asMono();
    }

    @Override
    public LifecycleDispatcher lifecycle() {
        return runtime.lifecycle();
    }

    @Override
    public boolean acceptsResources() {
        return drain == null && (state == PluginInstanceState.STARTING
            || state == PluginInstanceState.ACTIVE);
    }

    @Override
    public Mono<Void> drain() {
        return lifecycle().call(() -> {
            if (drain == null) {
                var barrier = new ResourceDrain(lifecycle());
                freezeResources(barrier);
                barrier.start();
            }
            return drain.completion();
        });
    }

    void freeze(ResourceDrain barrier) {
        ownershipFrozen = true;
        targetEpoch = null;
        freezeResources(barrier);
    }

    private void freezeResources(ResourceDrain barrier) {
        if (drain != null) {
            if (drain != barrier) {
                barrier.await(drain.completion());
            }
            return;
        }
        drain = barrier;
        resources.snapshot().forEach(barrier::include);
    }

    Mono<Void> cleanupUsing(ResourceOwner provider) {
        if (activationCleanup == null || serviceSnapshot.values().stream()
            .noneMatch(binding -> binding.owner() == provider)) {
            return Mono.empty();
        }
        return activationCleanup.asMono();
    }

    Mono<Void> cleanupUsing(ServiceRegistry.Binding<?> binding) {
        if (activationCleanup == null || serviceSnapshot.get(binding.key().name()) != binding) {
            return Mono.empty();
        }
        return activationCleanup.asMono();
    }

    @Override
    public String ownerName() {
        return "plugin:" + id;
    }

    @Override
    public void addResource(OwnedEffect resource) {
        if (!acceptsResources()) {
            throw new FibraException(FibraException.EFFECT_INACTIVE,
                "plugin instance \"" + id + "\" does not accept resources");
        }
        resources.add(resource);
    }

    @Override
    public void removeResource(OwnedEffect resource) {
        resources.remove(resource);
    }

    @Override
    public void resourceFailed(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (disposeRequested || state == PluginInstanceState.DISPOSED
            || state == PluginInstanceState.FAILED
            || state == PluginInstanceState.STOPPING) {
            return;
        }
        if (!transitioning) {
            beginTransition();
        }
        startFailed(failure);
    }

    @Override
    public PluginInstanceImpl<?> pluginInstance() {
        return this;
    }

    DefaultContext contextImpl() {
        return context;
    }

    boolean dependsOn(String serviceName) {
        return requirements.containsKey(serviceName);
    }

    boolean provides(ServiceKey<?> key) {
        return definition.provides().contains(key);
    }

    ServiceRegistry.Binding<?> serviceSnapshot(String serviceName) {
        return serviceSnapshot.get(serviceName);
    }

    RuntimeDomainSnapshot.Plugin diagnosticSnapshot() {
        var dependencies = new java.util.ArrayList<RuntimeDomainSnapshot.Dependency>();
        requirements.forEach((name, key) -> {
            var binding = domain.services().lookupActive(context, key);
            dependencies.add(new RuntimeDomainSnapshot.Dependency(
                new RuntimeDomainSnapshot.ServiceIdentity(
                    name, key.type().getName(), String.valueOf(context.realm(name))),
                binding == null ? null
                    : DefaultRuntimeDomain.ownerIdentity(binding.owner())));
        });
        return new RuntimeDomainSnapshot.Plugin(identity, parentIdentity, id, definition.name(), state,
            dependencies, error == null ? null : error.toString());
    }

    boolean isSettledForDomain() {
        return switch (state) {
            case PENDING -> !transitioning && targetEpoch == null;
            case ACTIVE -> !transitioning && Objects.equals(currentEpoch, targetEpoch);
            case FAILED, DISPOSED -> !transitioning;
            case STARTING, STOPPING -> false;
        };
    }

    void dependencyChanged(String serviceName) {
        if (dependsOn(serviceName)) {
            refreshDependencies();
        }
    }

    private void refreshDependencies() {
        if (disposeRequested || ownershipFrozen) {
            targetEpoch = null;
        } else {
            candidates.clear();
            var providers = new java.util.ArrayList<ResourceOwner>();
            for (var requirement : requirements.values()) {
                var binding = domain.services().lookupActive(context, requirement);
                if (binding == null) {
                    targetEpoch = null;
                    scheduleConvergence();
                    return;
                }
                candidates.put(requirement.name(), binding);
                providers.add(binding.owner());
            }
            targetEpoch = new ActivationEpoch(providers, configRevision);
        }
        scheduleConvergence();
    }

    private void scheduleConvergence() {
        if (state == PluginInstanceState.FAILED || transitioning
            || Objects.equals(currentEpoch, targetEpoch)) {
            return;
        }
        beginTransition();
        if (state == PluginInstanceState.ACTIVE || !resources.snapshot().isEmpty()) {
            startStop();
        } else if (disposeRequested) {
            finishDisposed(null);
        } else if (targetEpoch == null) {
            finishPending();
        } else {
            startPlugin(targetEpoch);
        }
    }

    private void beginTransition() {
        transitioning = true;
        stable = Sinks.one();
    }

    private void startPlugin(ActivationEpoch loadingEpoch) {
        drain = null;
        activationCleanup = Sinks.one();
        setState(PluginInstanceState.STARTING);
        serviceSnapshot = Map.copyOf(candidates);
        try {
            config = validatedConfig;
            InjectProcessor.prepare(plugin, context, definition.injectionType());
        } catch (RuntimeException | Error failure) {
            startFailed(failure);
            return;
        }
        lifecycle().tick()
            .then(Mono.defer(() -> Objects.requireNonNull(plugin.start(context, config),
                "plugin start returned null")))
            .publishOn(lifecycle().scheduler())
            .subscribe(ignored -> { }, this::startFailed, () -> startCompleted(loadingEpoch));
    }

    private void startCompleted(ActivationEpoch loadingEpoch) {
        if (state != PluginInstanceState.STARTING) {
            return;
        }
        if (Objects.equals(targetEpoch, loadingEpoch)) {
            currentEpoch = loadingEpoch;
            error = null;
            var completed = completeTransition(PluginInstanceState.ACTIVE);
            domain.services().ownerStateChanged(this);
            completed.tryEmitValue(this);
            return;
        }
        startStop();
    }

    private void startFailed(Throwable failure) {
        if (state != PluginInstanceState.STARTING
            && state != PluginInstanceState.ACTIVE) {
            return;
        }
        var consumers = domain.services().consumerCleanup(this);
        var drained = stopDrain();
        drained.then(consumers).then(Cleanup.allSettled(Cleanup.reversed(resources.snapshot())))
            .publishOn(lifecycle().scheduler())
            .subscribe(ignored -> { }, cleanupFailure -> {
                failure.addSuppressed(cleanupFailure);
                if (disposeRequested) {
                    stopFailed(cleanupFailure);
                } else {
                    activationCleanup.tryEmitError(cleanupFailure);
                    finishFailed(failure);
                }
            }, () -> {
                if (disposeRequested) {
                    stopCompleted();
                } else {
                    activationCleanup.tryEmitEmpty();
                    finishFailed(failure);
                }
            });
    }

    private void startStop() {
        var consumers = domain.services().consumerCleanup(this);
        var drained = stopDrain();
        lifecycle().tick()
            .then(drained)
            .then(consumers)
            .then(Cleanup.allSettled(Cleanup.reversed(resources.snapshot())))
            .publishOn(lifecycle().scheduler())
            .subscribe(ignored -> { }, this::stopFailed, this::stopCompleted);
    }

    private Mono<Void> stopDrain() {
        ResourceDrain starting = null;
        if (drain == null) {
            starting = new ResourceDrain(lifecycle());
            freezeResources(starting);
        }
        setState(PluginInstanceState.STOPPING);
        if (starting != null) {
            starting.start();
        }
        return drain.completion();
    }

    private void stopCompleted() {
        if (activationCleanup != null) {
            activationCleanup.tryEmitEmpty();
        }
        serviceSnapshot = Map.of();
        currentEpoch = null;
        if (disposeRequested) {
            finishDisposed(null);
        } else if (targetEpoch == null) {
            finishPending();
        } else {
            startPlugin(targetEpoch);
        }
    }

    private void stopFailed(Throwable failure) {
        if (activationCleanup != null) {
            activationCleanup.tryEmitError(failure);
        }
        if (failure instanceof ResourceDrain.Failure) {
            finishFailed(failure);
            if (disposeRequested) {
                disposed.tryEmitError(failure);
            }
            return;
        }
        currentEpoch = null;
        if (disposeRequested) {
            LOGGER.warn("plugin instance \"{}\" cleanup failed during disposal", id, failure);
            finishDisposed(null);
        } else {
            finishFailed(failure);
        }
    }

    private void finishPending() {
        var completed = completeTransition(PluginInstanceState.PENDING);
        completed.tryEmitValue(this);
    }

    private void finishFailed(Throwable failure) {
        error = failure;
        var completed = completeTransition(PluginInstanceState.FAILED);
        domain.services().ownerStateChanged(this);
        completed.tryEmitError(failure);
    }

    private void finishDisposed(Throwable failure) {
        var completed = completeTransition(PluginInstanceState.DISPOSED);
        scope.removePlugin(this);
        domain.removeInstance(this);
        domain.services().ownerStateChanged(this);
        if (failure == null) {
            completed.tryEmitValue(this);
            disposed.tryEmitEmpty();
        } else {
            completed.tryEmitError(failure);
            disposed.tryEmitError(failure);
        }
        states.tryEmitComplete();
    }

    private Sinks.One<PluginInstance<C>> completeTransition(PluginInstanceState nextState) {
        var completed = stable;
        transitioning = false;
        state = nextState;
        states.tryEmitNext(nextState);
        return completed;
    }

    private void setState(PluginInstanceState nextState) {
        if (state == nextState) {
            return;
        }
        state = nextState;
        states.tryEmitNext(nextState);
        domain.services().ownerStateChanged(this);
    }

    private Sinks.One<PluginInstance<C>> completedSignal() {
        var signal = Sinks.<PluginInstance<C>>one();
        signal.tryEmitValue(this);
        return signal;
    }

    private record ActivationEpoch(List<ResourceOwner> providers, long configRevision) {
        private ActivationEpoch {
            providers = List.copyOf(providers);
        }
    }
}
