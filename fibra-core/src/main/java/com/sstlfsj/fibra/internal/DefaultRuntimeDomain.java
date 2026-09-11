package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Scope;
import com.sstlfsj.fibra.runtime.RuntimeDomainSnapshot;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DefaultRuntimeDomain {
    private final DefaultFibraRuntime runtime;
    private final String name;
    private final boolean rootDomain;
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Sinks.One<Void> closedSignal = Sinks.one();
    private final List<PluginInstanceImpl<?>> instances = new ArrayList<>();
    private final Map<PluginInstanceImpl<?>, reactor.core.Disposable> observations = new IdentityHashMap<>();
    private final List<RuntimeDomainSnapshot.CleanupFailure> cleanupFailures = new ArrayList<>();
    // 诊断只投影文字；实际失败句柄仍由域持有，不能随实例退出而丢弃待清理资源。
    private final IdentityList<OwnedEffect> failedResources = new IdentityList<>();
    private final Sinks.Many<RuntimeDomainSnapshot> snapshots = Sinks.many().replay().latest();
    private final Sinks.Many<Long> convergenceChanges = Sinks.many().replay().latest();
    private RuntimeDomainSnapshot lastSnapshot;
    private boolean snapshotPending;
    private long convergenceRevision;
    private final ServiceRegistry services = new ServiceRegistry(this);
    private final EventBus events = new EventBus(this);
    private final PropertyRegistry properties = new PropertyRegistry(this);
    private final DefaultScope rootScope;

    DefaultRuntimeDomain(DefaultFibraRuntime runtime, String name, boolean rootDomain) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("runtime domain name must not be blank");
        }
        this.name = name;
        this.rootDomain = rootDomain;
        rootScope = new DefaultScope(this, null, name);
        lastSnapshot = RuntimeDomainSnapshot.builder().name(name).build();
        snapshots.tryEmitNext(lastSnapshot);
    }

    public String name() {
        return name;
    }

    public Scope rootScope() {
        return rootScope;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public RuntimeDomainSnapshot snapshot() {
        if (closed.get()) return lastSnapshot;
        return runtime.lifecycle().call(() -> {
            var observed = instances.stream()
                .map(PluginInstanceImpl::diagnosticSnapshot).toList();
            return RuntimeDomainSnapshot.builder().name(name).plugins(observed)
                .services(services.diagnosticSnapshot()).events(events.diagnosticSnapshot())
                .cleanupFailures(cleanupFailures).build();
        });
    }

    public List<RuntimeDomainSnapshot.CleanupFailure> cleanupFailures(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        if (!(scope instanceof DefaultScope selected) || selected.domain() != this) {
            throw new IllegalArgumentException("scope does not belong to runtime domain \"" + name + "\"");
        }
        if (closed.get()) {
            return scopedCleanupFailures(selected);
        }
        return runtime.lifecycle().call(() -> scopedCleanupFailures(selected));
    }

    private List<RuntimeDomainSnapshot.CleanupFailure> scopedCleanupFailures(DefaultScope scope) {
        var owners = new java.util.HashSet<Long>();
        failedResources.snapshot().forEach(resource -> {
            var owner = resource.owner();
            if (owner.scope().isWithin(scope)) {
                owners.add(owner.identity());
            }
        });
        return cleanupFailures.stream()
            .filter(failure -> owners.contains(failure.ownerIdentity())).toList();
    }

    void cleanupFailed(OwnedEffect resource, ResourceOwner owner, String label, Throwable failure) {
        failedResources.add(resource);
        cleanupFailures.add(new RuntimeDomainSnapshot.CleanupFailure(owner.identity(), label, failure.toString()));
        diagnosticChanged();
    }

    public Flux<RuntimeDomainSnapshot> snapshots() {
        return snapshots.asFlux().onBackpressureLatest().publishOn(Schedulers.boundedElastic(), 1);
    }

    public Mono<Void> settled() {
        return Mono.defer(this::awaitSettlement);
    }

    void diagnosticChanged() {
        if (!snapshotPending && !closed.get()) {
            snapshotPending = true;
            runtime.lifecycle().scheduler().schedule(this::publishSnapshot);
        }
    }

    private void publishSnapshot() {
        snapshotPending = false;
        if (closed.get()) return;
        var next = snapshot();
        if (!next.equals(lastSnapshot)) {
            lastSnapshot = next;
            snapshots.tryEmitNext(next);
        }
    }

    static RuntimeDomainSnapshot.OwnerIdentity ownerIdentity(ResourceOwner owner) {
        var name = owner.ownerName();
        var separator = name.indexOf(':');
        return separator < 0
            ? new RuntimeDomainSnapshot.OwnerIdentity("resource", name)
            : new RuntimeDomainSnapshot.OwnerIdentity(
                name.substring(0, separator), name.substring(separator + 1));
    }

    public Mono<Void> closeAsync() {
        if (rootDomain && !runtime.closeRequested()) {
            return runtime.closeAsync();
        }
        return closeFromRuntime();
    }

    Mono<Void> closeFromRuntime() {
        if (closeRequested.compareAndSet(false, true)) {
            rootScope.closeFromDomain()
                .subscribe(ignored -> { }, this::finish, () -> finish(null));
        }
        return closedSignal.asMono();
    }

    DefaultFibraRuntime runtime() {
        return runtime;
    }

    ServiceRegistry services() {
        return services;
    }

    EventBus events() {
        return events;
    }

    PropertyRegistry properties() {
        return properties;
    }

    boolean closeRequested() {
        return closeRequested.get();
    }

    void addInstance(PluginInstanceImpl<?> instance) {
        instances.add(instance);
        observations.put(instance, instance.states().subscribe(ignored -> {
            convergenceChanged();
            diagnosticChanged();
        }));
    }

    void removeInstance(PluginInstanceImpl<?> instance) {
        instances.remove(instance);
        observations.remove(instance).dispose();
        convergenceChanged();
        diagnosticChanged();
    }

    List<PluginInstanceImpl<?>> instancesSnapshot() {
        return List.copyOf(instances);
    }

    List<PluginInstanceImpl<?>> cleanupConsumersSnapshot() {
        var consumers = new IdentityList<PluginInstanceImpl<?>>();
        instances.forEach(consumers::add);
        failedResources.snapshot().forEach(resource -> {
            var instance = resource.owner().pluginInstance();
            if (instance != null) {
                consumers.add(instance);
            }
        });
        return consumers.snapshot();
    }

    private Mono<Void> awaitSettlement() {
        return sampleSettlement().flatMap(sample -> {
            if (!sample.settled()) {
                return convergenceChanges.asFlux()
                    .filter(revision -> revision > sample.revision()).next()
                    .then(Mono.defer(this::awaitSettlement));
            }
            return runtime.lifecycle().tick().then(sampleSettlement()).flatMap(afterTick ->
                afterTick.settled() ? Mono.empty() : awaitSettlement());
        });
    }

    private Mono<Settlement> sampleSettlement() {
        return runtime.lifecycle().mono(() -> new Settlement(convergenceRevision,
            instances.stream().allMatch(PluginInstanceImpl::isSettledForDomain)));
    }

    private void convergenceChanged() {
        convergenceChanges.tryEmitNext(++convergenceRevision);
    }

    private record Settlement(long revision, boolean settled) {
    }

    private void finish(Throwable error) {
        runtime.lifecycle().call(() -> {
            publishSnapshot();
            if (error instanceof ResourceDrain.Failure) {
                closedSignal.tryEmitError(error);
                return null;
            }
            closed.set(true);
            runtime.removeDomain(this);
            snapshots.tryEmitComplete();
            if (error == null) closedSignal.tryEmitEmpty();
            else closedSignal.tryEmitError(error);
            return null;
        });
    }
}
