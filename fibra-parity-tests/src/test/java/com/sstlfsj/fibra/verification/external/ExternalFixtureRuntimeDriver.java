package com.sstlfsj.fibra.verification.external;

import com.sstlfsj.fibra.Scope;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.bridge.ContributionAdmission;
import com.sstlfsj.fibra.bridge.ContributionBinding;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.engine.CompiledRuntimeSlice;
import com.sstlfsj.fibra.engine.DefinitionBindingPlan;
import com.sstlfsj.fibra.engine.ExecutionObservation;
import com.sstlfsj.fibra.engine.ExecutionUnitKey;
import com.sstlfsj.fibra.engine.ExecutionUnitPlan;
import com.sstlfsj.fibra.engine.PluginFacetSource;
import com.sstlfsj.fibra.engine.PreparedRuntimeGeneration;
import com.sstlfsj.fibra.engine.RuntimeArtifactInspection;
import com.sstlfsj.fibra.engine.RuntimeCandidate;
import com.sstlfsj.fibra.engine.RuntimeDriver;
import com.sstlfsj.fibra.engine.RuntimeDriverSnapshot;
import com.sstlfsj.fibra.engine.RuntimeHostServices;
import com.sstlfsj.fibra.engine.RuntimePlan;
import com.sstlfsj.fibra.engine.RuntimeTargetSlice;
import com.sstlfsj.fibra.engine.RuntimeUnitFence;
import com.sstlfsj.fibra.engine.RuntimeUnitGeneration;
import com.sstlfsj.fibra.value.LiteralValue;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 不发布的 external execution 验证实现。它不模拟浏览器、URL、session 或 transport；
 * 在线时唯一的执行副作用是使用所属 admission 登记固定 remote contribution。
 */
public final class ExternalFixtureRuntimeDriver implements RuntimeDriver {
    private final RuntimeHostServices services;
    private final Map<ExecutionUnitKey, Unit> managed = new ConcurrentHashMap<>();
    private final Map<ExecutionUnitKey, RuntimeUnitGeneration> current = new ConcurrentHashMap<>();
    private final Map<ResourceKey, Resource> resources = new LinkedHashMap<>();
    private final Object resourceMonitor = new Object();
    private final Object controlMonitor = new Object();
    private final CopyOnWriteArrayList<ExternalFixtureEvent> events = new CopyOnWriteArrayList<>();
    private final AtomicLong eventSequence = new AtomicLong();
    private final AtomicInteger preparations = new AtomicInteger();
    private final AtomicInteger seals = new AtomicInteger();
    private final AtomicInteger reconciliations = new AtomicInteger();
    private final AtomicInteger activations = new AtomicInteger();
    private final AtomicInteger drains = new AtomicInteger();
    private final AtomicInteger stops = new AtomicInteger();
    private final AtomicInteger aborts = new AtomicInteger();
    private final AtomicInteger retires = new AtomicInteger();
    private volatile boolean online;
    private volatile boolean closed;

    ExternalFixtureRuntimeDriver(RuntimeHostServices services) {
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public RuntimeId id() { return ExternalFixtureRuntimeProvider.RUNTIME_ID; }

    @Override
    public Mono<RuntimeArtifactInspection> probe(PluginFacetSource source) {
        return Mono.fromCallable(() -> inspectSource(Objects.requireNonNull(source, "source")));
    }

    @Override
    public Mono<RuntimeArtifactInspection> inspect(ManagedFacet facet) {
        return Mono.fromCallable(() -> inspectSource(new PluginFacetSource(
            Objects.requireNonNull(facet, "facet"), List.of())));
    }

    @Override
    public synchronized RuntimeCandidate createCandidate(RuntimeTargetSlice target) {
        if (closed) throw new IllegalStateException("external fixture runtime driver is closed");
        if (!id().equals(Objects.requireNonNull(target, "target").runtimeId())) {
            throw new IllegalArgumentException("external fixture candidate requires its runtime slice");
        }
        return new Candidate(target);
    }

    @Override
    public RuntimeDriverSnapshot snapshot() {
        var values = new LinkedHashMap<ExecutionUnitKey, ExecutionObservation>();
        current.forEach((key, unit) -> values.put(key, unit.snapshot()));
        return new RuntimeDriverSnapshot(id(), values);
    }

    @Override
    public synchronized Mono<Void> closeAsync() {
        if (!closed) {
            closed = true;
            managed.values().forEach(Unit::closeAdmission);
            event("DRIVER_CLOSED", "driver", "admissions-closed");
        }
        return Mono.empty();
    }

    void setOnline(boolean nextOnline) {
        final Set<RuntimeUnitFence> fences;
        final String reason;
        synchronized (controlMonitor) {
            if (closed) throw new IllegalStateException("external fixture runtime driver is closed");
            if (online == nextOnline) return;
            online = nextOnline;
            if (!nextOnline) {
                event("OFFLINE", "controller", "routes-closed");
                fences = managed.values().stream().filter(Unit::disconnect)
                    .map(Unit::fence)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
                reason = "external-fixture-disconnected";
            } else {
                fences = managed.values().stream().filter(Unit::reconcilable)
                    .map(Unit::fence)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
                reason = "external-fixture-online";
                event("ONLINE", "controller", "reconcile-requested");
            }
        }
        if (!fences.isEmpty()) {
            // 可用性变化只把失败/待激活事实送回 Engine command lane；不得直接替换或启动 unit。
            services.requestReconcile(fences, reason);
        }
    }

    ExternalFixtureRuntimeSnapshot fixtureSnapshot() {
        int generationCount;
        int leaseCount;
        synchronized (resourceMonitor) {
            generationCount = resources.size();
            leaseCount = resources.values().stream().mapToInt(resource -> resource.references).sum();
        }
        return new ExternalFixtureRuntimeSnapshot(online, new ExternalFixtureCounters(
            preparations.get(), seals.get(), reconciliations.get(), activations.get(),
            drains.get(), stops.get(), aborts.get(), retires.get(), generationCount, leaseCount),
            List.copyOf(events));
    }

    private RuntimeArtifactInspection inspectSource(PluginFacetSource source) {
        requireRuntime(source.facet());
        return new RuntimeArtifactInspection(id(), source.facet().artifactId());
    }

    private static void requireRuntime(ManagedFacet facet) {
        if (!ExternalFixtureRuntimeProvider.RUNTIME_ID.equals(facet.facet().runtimeId())) {
            throw new IllegalArgumentException("facet runtime is not external-fixture");
        }
    }

    private ResourceLease acquire(ManagedFacet facet) {
        var key = new ResourceKey(facet.artifactId(), facet.pluginId().value(),
            facet.facet().facetId().value(), facet.packageRevision());
        synchronized (resourceMonitor) {
            var resource = resources.computeIfAbsent(key, Resource::new);
            resource.references++;
            event("RESOURCE_ACQUIRED", "resource", key.toString());
            return new ResourceLease(resource);
        }
    }

    private void release(Resource resource) {
        synchronized (resourceMonitor) {
            if (--resource.references < 0) {
                throw new IllegalStateException("external fixture resource lease underflow");
            }
            if (resource.references == 0 && resources.remove(resource.key, resource)) {
                event("RESOURCE_RETIRED", "resource", resource.key.toString());
            }
        }
    }

    private void event(String type, String unitKey, String detail) {
        events.add(new ExternalFixtureEvent(eventSequence.incrementAndGet(), type, unitKey, detail));
    }

    private final class Candidate implements RuntimeCandidate {
        private final RuntimeTargetSlice target;
        private final Mono<Void> preparation = Mono.<Void>fromRunnable(this::prepare).cache();
        private Map<ExecutionUnitKey, Prepared> prepared = Map.of();
        private RuntimePlan plan;
        private boolean sealed;
        private boolean candidateClosed;

        private Candidate(RuntimeTargetSlice target) { this.target = target; }

        @Override
        public Mono<Void> prepareAsync() {
            synchronized (this) {
                if (candidateClosed || sealed) {
                    return Mono.error(new IllegalStateException("external fixture candidate is unavailable"));
                }
            }
            return preparation;
        }

        @Override
        public synchronized RuntimePlan preparedPlan() {
            if (plan == null) throw new IllegalStateException("external fixture candidate is not prepared");
            return plan;
        }

        @Override
        public synchronized PreparedRuntimeGeneration seal(CompiledRuntimeSlice slice) {
            if (plan == null || sealed || candidateClosed) {
                throw new IllegalStateException("external fixture candidate cannot seal");
            }
            if (!id().equals(slice.runtimeId()) || slice.plan() != plan) {
                throw new IllegalArgumentException("compiled external slice differs from prepared plan");
            }
            sealed = true;
            seals.incrementAndGet();
            event("SEALED", "candidate", String.valueOf(prepared.size()));
            return new Generation(slice, prepared,
                target.target().targetRevision(),
                target.capabilities().availableNames());
        }

        @Override
        public synchronized Mono<Void> closeAsync() {
            if (!candidateClosed) {
                candidateClosed = true;
                if (!sealed) {
                    prepared.values().forEach(Prepared::release);
                    prepared = Map.of();
                    plan = null;
                }
            }
            return Mono.empty();
        }

        private void prepare() {
            var sources = new LinkedHashMap<String, PluginFacetSource>();
            target.facets().forEach(source -> {
                requireRuntime(source.facet());
                var key = source.facet().pluginId().value() + '/' + source.facet().facet().facetId().value();
                if (sources.putIfAbsent(key, source) != null) {
                    throw new IllegalArgumentException("duplicate external fixture facet " + key);
                }
            });
            var units = new LinkedHashMap<ExecutionUnitKey, ExecutionUnitPlan>();
            var bindings = new LinkedHashMap<String, DefinitionBindingPlan>();
            var values = new LinkedHashMap<ExecutionUnitKey, Prepared>();
            try {
                for (var entryId : target.affectedEntryIds().stream().sorted().toList()) {
                    var resolved = target.desired().entries().get(entryId);
                    if (!(resolved.input() instanceof DesiredInputEntry entry)
                        || !resolved.effective().enabled()) {
                        throw new IllegalArgumentException("affected external entry is not enabled: " + entryId);
                    }
                    var ref = entry.definitionRef();
                    verifyDefinition(ref);
                    var source = sources.get(ref.pluginId() + '/' + ref.facetId());
                    if (source == null) {
                        throw new IllegalArgumentException("external definition does not belong to selected facet: " + entryId);
                    }
                    var key = new ExecutionUnitKey(entryId);
                    var dependencies = Objects.requireNonNull(target.unitDependencies().get(key),
                        "missing external fixture unit dependencies");
                    var facet = source.facet();
                    units.put(key, ExecutionUnitPlan.builder(key, id(), facet.facet().executionTarget())
                        .artifactId(facet.artifactId()).provenance(facet.pluginId().value(),
                            facet.facet().facetId().value(), facet.packageRevision())
                        .dependencies(dependencies).build());
                    bindings.put(entryId, DefinitionBindingPlan.builder(ref, entryId).unitKey(key)
                        .publicationRequirement(entry.publicationRequirement()).build());
                    values.put(key, new Prepared(units.get(key), ref,
                        resolved.resolvedConfig().orElseThrow(() ->
                            new IllegalArgumentException("missing resolved config for " + entryId)),
                        acquire(facet)));
                }
            } catch (RuntimeException failure) {
                values.values().forEach(Prepared::release);
                throw failure;
            }
            synchronized (this) {
                if (candidateClosed) {
                    values.values().forEach(Prepared::release);
                    throw new IllegalStateException("external fixture candidate is closed");
                }
                prepared = Map.copyOf(values);
                plan = RuntimePlan.of(id(), units.values(), bindings.values());
                preparations.incrementAndGet();
                event("PREPARED", "candidate", String.valueOf(prepared.size()));
            }
        }
    }

    private static void verifyDefinition(PluginDefinitionRef ref) {
        if (!ExternalFixtureRuntimeProvider.DEFINITION_ID.equals(ref.definitionId())) {
            throw new IllegalArgumentException("unknown external fixture definition " + ref.definitionId());
        }
    }

    private final class Generation implements PreparedRuntimeGeneration {
        private final Map<ExecutionUnitKey, RuntimeUnitGeneration> units;
        private final List<Unit> reverseDependency;
        private Mono<Void> abort;
        private Mono<Void> retire;

        private Generation(CompiledRuntimeSlice slice, Map<ExecutionUnitKey, Prepared> prepared,
                           long revision, Set<String> capabilities) {
            var values = new LinkedHashMap<ExecutionUnitKey, RuntimeUnitGeneration>();
            for (var key : slice.dependencyFirst()) {
                var unit = new Unit(Objects.requireNonNull(prepared.get(key),
                    "missing external fixture prepared unit " + key), revision,
                    capabilities);
                values.put(key, unit);
            }
            units = Map.copyOf(values);
            reverseDependency = slice.reverseDependency().stream().map(key -> (Unit) values.get(key)).toList();
        }

        @Override public Map<ExecutionUnitKey, RuntimeUnitGeneration> units() { return units; }

        @Override
        public synchronized Mono<Void> abortAsync() {
            if (abort == null) {
                abort = Mono.<Void>fromRunnable(() -> {
                    reverseDependency.forEach(Unit::abort);
                    aborts.incrementAndGet();
                    event("ABORTED", "generation", String.valueOf(units.size()));
                }).cache();
            }
            return abort;
        }

        @Override
        public synchronized Mono<Void> retireAsync() {
            if (retire == null) {
                retire = Mono.<Void>fromRunnable(() -> {
                    reverseDependency.forEach(Unit::retire);
                    retires.incrementAndGet();
                    event("RETIRED", "generation", String.valueOf(units.size()));
                }).cache();
            }
            return retire;
        }
    }

    private final class Unit implements RuntimeUnitGeneration {
        private final Prepared prepared;
        private final long unitTargetRevision;
        private final String runtimeInstanceId;
        private final Set<String> capabilities;
        private final List<ContributionAdmission> admissions = new ArrayList<>();
        private boolean admissionClosed;
        private boolean drained;
        private boolean stopped;
        private boolean released;
        private Scope scope;
        private String reconcileOperation = "unreconciled";
        private String drainOperation;
        private String stopOperation;
        private ExecutionObservation.State state = ExecutionObservation.State.PENDING;
        private Throwable failure;

        private Unit(Prepared prepared, long unitTargetRevision,
                     Set<String> capabilities) {
            this.prepared = prepared;
            this.unitTargetRevision = unitTargetRevision;
            runtimeInstanceId = services.nextIdentity("external:" + prepared.plan.key().value());
            this.capabilities = Set.copyOf(capabilities);
        }

        @Override public ExecutionUnitPlan plan() { return prepared.plan; }

        @Override
        public Mono<ExecutionObservation> reconcileAsync(String lifecycleOperationId) {
            var operation = operation(lifecycleOperationId);
            final Scope owner;
            final ContributionAdmission admission;
            synchronized (this) {
                if (admissionClosed || stopped || released) {
                    return Mono.error(new IllegalStateException("external fixture unit admission is closed"));
                }
                if (state == ExecutionObservation.State.ACTIVE) {
                    return operation.equals(reconcileOperation) ? Mono.just(snapshot())
                        : Mono.error(new IllegalStateException("stale external fixture reconcile operation"));
                }
                if (state == ExecutionObservation.State.FAILED) {
                    return Mono.error(new IllegalStateException("failed external fixture unit requires replacement"));
                }
                reconcileOperation = operation;
                reconciliations.incrementAndGet();
                managed.put(plan().key(), this);
                current.put(plan().key(), this);
                if (!online) {
                    event("PENDING", plan().key().value(), "offline");
                    return Mono.just(snapshot());
                }
                owner = scope == null ? services.scope().openChild(
                    "external:" + plan().key().value()) : scope;
                scope = owner;
                admission = services.openContributionAdmission(plan().key());
                admissions.add(admission);
            }
            return register(admission, owner).then(Mono.fromSupplier(() -> {
                synchronized (Unit.this) {
                    if (admissionClosed || !online) {
                        admission.closeAdmission();
                        state = ExecutionObservation.State.PENDING;
                        event("PENDING", plan().key().value(), "disconnected-during-activation");
                        return snapshot();
                    }
                    state = ExecutionObservation.State.ACTIVE;
                    failure = null;
                    activations.incrementAndGet();
                    event("ACTIVE", plan().key().value(), runtimeInstanceId);
                    return snapshot();
                }
            })).onErrorResume(error -> pendingOrFailed(admission, error));
        }

        @Override
        public void closeAdmission() {
            final List<ContributionAdmission> closing;
            synchronized (this) {
                admissionClosed = true;
                closing = List.copyOf(admissions);
            }
            closing.forEach(ContributionAdmission::closeAdmission);
            event("ADMISSION_CLOSED", plan().key().value(), "all-routes");
        }

        @Override
        public Mono<ExecutionObservation> drainAsync(String lifecycleOperationId, Instant deadline) {
            Objects.requireNonNull(deadline, "deadline");
            var operation = operation(lifecycleOperationId);
            final List<ContributionAdmission> closing;
            synchronized (this) {
                if (!admissionClosed) {
                    return Mono.error(new IllegalStateException("external fixture unit admission is open"));
                }
                if (drainOperation != null && !operation.equals(drainOperation)) {
                    return Mono.error(new IllegalStateException("stale external fixture drain operation"));
                }
                drainOperation = operation;
                closing = List.copyOf(admissions);
            }
            return Flux.fromIterable(closing).concatMap(ContributionAdmission::drainAsync).then(
                Mono.fromSupplier(() -> {
                    synchronized (Unit.this) {
                        drained = true;
                        drains.incrementAndGet();
                        event("DRAINED", plan().key().value(), operation);
                        return snapshot();
                    }
                }));
        }

        @Override
        public Mono<ExecutionObservation> stopAsync(String lifecycleOperationId, Instant deadline) {
            Objects.requireNonNull(deadline, "deadline");
            var operation = operation(lifecycleOperationId);
            final Scope closing;
            synchronized (this) {
                if (!admissionClosed) {
                    return Mono.error(new IllegalStateException("external fixture unit admission is open"));
                }
                if (!drained) {
                    return Mono.error(new IllegalStateException("external fixture unit has not drained"));
                }
                if (stopOperation != null && !operation.equals(stopOperation)) {
                    return Mono.error(new IllegalStateException("stale external fixture stop operation"));
                }
                if (stopped) return Mono.just(snapshot());
                stopOperation = operation;
                closing = scope;
            }
            return (closing == null ? Mono.<Void>empty()
                : services.releaseScope(closing)).then(
                Mono.fromSupplier(() -> {
                    synchronized (Unit.this) {
                        stopped = true;
                        state = ExecutionObservation.State.PENDING;
                        failure = null;
                        current.remove(plan().key(), this);
                        stops.incrementAndGet();
                        event("STOPPED", plan().key().value(), operation);
                        return snapshot();
                    }
                }));
        }

        @Override
        public synchronized ExecutionObservation snapshot() {
            var detail = ExecutionObservation.Detail.builder()
                .unitTargetRevision(unitTargetRevision).executionId(plan().key().value())
                .runtimeInstanceId(runtimeInstanceId).lifecycleOperationId(reconcileOperation)
                .capabilities(capabilities).state(state)
                .failure(failure == null ? null : new ExecutionObservation.Failure(
                    "EXTERNAL_FIXTURE", failure.toString(), Map.of("unit", plan().key().value())))
                .build();
            return ExecutionObservation.of(plan().pluginId(), plan().facetId(), id(),
                plan().executionTarget(), List.of(detail));
        }

        private Mono<Void> register(ContributionAdmission admission, Scope owner) {
            var resolved = services.contributionKinds()
                .find(ExternalFixtureRuntimeProvider.REMOTE_KIND_NAME)
                .orElseThrow(() -> new IllegalStateException(
                    "external fixture remote kind is not registered"));
            if (resolved != ExternalFixtureRuntimeProvider.REMOTE_KIND) {
                return Mono.error(new IllegalStateException(
                    "external fixture must use the registry's exact remote kind instance"));
            }
            ContributionKind<String, String, String> kind = ExternalFixtureRuntimeProvider.REMOTE_KIND;
            var config = prepared.config.canonicalJson();
            var binding = new ContributionBinding<>(kind, "echo", config,
                (ignored, input) -> Mono.just(config + ':' + input));
            return admission.registerAll(owner.context(),
                List.of(binding), () -> Mono.<Void>empty()).then();
        }

        private Mono<ExecutionObservation> pendingOrFailed(ContributionAdmission admission, Throwable error) {
            admission.closeAdmission();
            synchronized (this) {
                if (!online && !admissionClosed) {
                    state = ExecutionObservation.State.PENDING;
                    event("PENDING", plan().key().value(), "offline-registration");
                    return Mono.just(snapshot());
                }
                state = ExecutionObservation.State.FAILED;
                failure = error;
                event("FAILED", plan().key().value(), error.getClass().getSimpleName());
                return Mono.just(snapshot());
            }
        }

        private boolean disconnect() {
            final List<ContributionAdmission> closing;
            synchronized (this) {
                if (released || stopped || state != ExecutionObservation.State.ACTIVE) {
                    return false;
                }
                state = ExecutionObservation.State.FAILED;
                failure = new IllegalStateException(
                    "external execution disconnected while active");
                closing = List.copyOf(admissions);
            }
            closing.forEach(ContributionAdmission::closeAdmission);
            event("DISCONNECTED", plan().key().value(),
                "failed-replacement-requested");
            return true;
        }

        private synchronized boolean reconcilable() {
            return !admissionClosed && !stopped && !released;
        }

        private RuntimeUnitFence fence() {
            return RuntimeUnitFence.builder(id(), plan().key())
                .unitTargetRevision(unitTargetRevision)
                .runtimeInstanceId(runtimeInstanceId).build();
        }

        private synchronized void abort() {
            if (state != ExecutionObservation.State.PENDING || scope != null) {
                throw new IllegalStateException("started external fixture unit cannot abort");
            }
            releaseLease();
            current.remove(plan().key(), this);
            managed.remove(plan().key(), this);
        }

        private synchronized void retire() {
            if (!stopped) throw new IllegalStateException("external fixture unit must stop before retirement");
            releaseLease();
            managed.remove(plan().key(), this);
            current.remove(plan().key(), this);
        }

        private void releaseLease() {
            if (!released) {
                prepared.resource.release();
                released = true;
            }
        }
    }

    private static String operation(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("lifecycle operation id must not be blank");
        }
        return value;
    }

    private record Prepared(ExecutionUnitPlan plan, PluginDefinitionRef definition,
                            LiteralValue config, ResourceLease resource) {
        private Prepared {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(resource, "resource");
        }
        private void release() { resource.release(); }
    }

    private record ResourceKey(ArtifactId artifactId, String pluginId, String facetId,
                               String packageRevision) {
        private ResourceKey {
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(pluginId, "pluginId");
            Objects.requireNonNull(facetId, "facetId");
            Objects.requireNonNull(packageRevision, "packageRevision");
        }
    }

    private static final class Resource {
        private final ResourceKey key;
        private int references;
        private Resource(ResourceKey key) { this.key = key; }
    }

    private final class ResourceLease implements AutoCloseable {
        private final Resource resource;
        private boolean released;
        private ResourceLease(Resource resource) { this.resource = resource; }
        private synchronized void release() {
            if (!released) {
                ExternalFixtureRuntimeDriver.this.release(resource);
                released = true;
            }
        }
        @Override public void close() { release(); }
    }
}
