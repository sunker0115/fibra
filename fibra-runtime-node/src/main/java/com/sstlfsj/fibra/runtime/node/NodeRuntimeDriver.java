package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.Scope;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.bridge.ContributionBinding;
import com.sstlfsj.fibra.bridge.ContributionAdmission;
import com.sstlfsj.fibra.bridge.ContributionCodec;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.engine.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Node facet 的唯一 owner；prepare 只持有静态 payload lease，不启动 sidecar。 */
public final class NodeRuntimeDriver implements RuntimeDriver {
    private final RuntimeHostServices services;
    private final NodeRuntimeOptions options;
    private final NodeFacetDescriptorReader descriptors = new NodeFacetDescriptorReader();
    private final Map<PayloadKey, Payload> payloads = new LinkedHashMap<>();
    private final Map<ExecutionUnitKey, RuntimeUnitGeneration> published = new ConcurrentHashMap<>();
    private Mono<Void> close;

    NodeRuntimeDriver(RuntimeHostServices services, NodeRuntimeOptions options) {
        this.services = Objects.requireNonNull(services, "services");
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override public RuntimeId id() { return NodeRuntimeProvider.RUNTIME_ID; }
    @Override public Mono<RuntimeArtifactInspection> probe(PluginFacetSource source) {
        return Mono.fromCallable(() -> inspectFacet(Objects.requireNonNull(source, "source").facet()));
    }
    @Override public Mono<RuntimeArtifactInspection> inspect(ManagedFacet facet) {
        return Mono.fromCallable(() -> inspectFacet(facet));
    }
    @Override public synchronized RuntimeCandidate createCandidate(RuntimeTargetSlice target) {
        open();
        if (!id().equals(Objects.requireNonNull(target, "target").runtimeId())) throw new IllegalArgumentException("Node runtime target identity mismatch");
        return new Candidate(target);
    }
    @Override public synchronized RuntimeDriverSnapshot snapshot() {
        var values = new LinkedHashMap<ExecutionUnitKey, ExecutionObservation>();
        published.forEach((key, unit) -> values.put(key, unit.snapshot()));
        return new RuntimeDriverSnapshot(id(), values);
    }
    @Override public synchronized Mono<Void> closeAsync() {
        if (close == null) close = Mono.<Void>fromRunnable(() -> {
            synchronized (NodeRuntimeDriver.this) {
                if (payloads.values().stream().anyMatch(value -> value.references != 0)) throw new IllegalStateException("Node runtime driver still has leased payloads");
                payloads.clear(); published.clear();
            }
        }).cache();
        return close;
    }

    private RuntimeArtifactInspection inspectFacet(ManagedFacet facet) {
        open();
        if (!id().equals(Objects.requireNonNull(facet, "facet").facet().runtimeId())) throw new IllegalArgumentException("facet runtime is not node");
        descriptors.read(facet);
        return new RuntimeArtifactInspection(id(), facet.artifactId());
    }
    private synchronized void open() { if (close != null) throw new IllegalStateException("Node runtime driver is closed"); }
    private synchronized PayloadLease acquire(PluginFacetSource source,
                                              Map<ArtifactId, PluginFacetSource> sources) {
        return acquire(source, sources, new LinkedHashSet<>());
    }
    private PayloadLease acquire(PluginFacetSource source,
                                 Map<ArtifactId, PluginFacetSource> sources,
                                 Set<ArtifactId> visiting) {
        var facet = source.facet();
        if (!visiting.add(facet.artifactId())) {
            throw new IllegalArgumentException(
                "Node static dependency cycle at " + facet.artifactId());
        }
        var dependencies = new ArrayList<PayloadLease>();
        try {
            for (var dependency : source.dependencies()) {
                var local = sources.get(dependency.artifactId());
                if (local != null) {
                    dependencies.add(acquire(local, sources, visiting));
                }
            }
            var dependencyKeys = dependencies.stream()
                .map(lease -> lease.payload.key.identity()).sorted().toList();
            var key = new PayloadKey(facet.artifactId(), facet.packageRevision(),
                facet.facet().facetId().value(), facet.facet().payloadDigest(),
                dependencyKeys, NodeRuntimeProvider.CONTRACT_IDENTITY);
            var payload = payloads.get(key);
            if (payload == null) {
                payload = new Payload(key, facet, descriptors.read(facet));
                validate(payload);
                payloads.put(key, payload);
            }
            payload.references++;
            return new PayloadLease(payload, dependencies);
        } catch (RuntimeException failure) {
            releaseAll(dependencies, failure);
            throw failure;
        } finally {
            visiting.remove(facet.artifactId());
        }
    }
    private void validate(Payload payload) {
        for (var endpoint : payload.descriptor.contributions()) {
            codec(endpoint, payload.facet.artifactId()).decodeDescriptor(
                com.sstlfsj.fibra.value.LiteralValue.of(endpoint.descriptor()));
        }
    }
    private synchronized void release(Payload payload) {
        if (payload.references < 1) throw new IllegalStateException("Node payload lease underflow");
        if (--payload.references == 0) payloads.remove(payload.key, payload);
    }

    private final class Candidate implements RuntimeCandidate {
        private final RuntimeTargetSlice target;
        private final Mono<Void> preparation = Mono.<Void>fromRunnable(this::prepare).cache();
        private boolean started, sealed, closed;
        private RuntimePlan plan;
        private Map<ExecutionUnitKey, Prepared> prepared = Map.of();
        private Mono<Void> close;
        private Candidate(RuntimeTargetSlice target) { this.target = target; }
        @Override public Mono<Void> prepareAsync() {
            synchronized (this) { if (closed) return Mono.error(new IllegalStateException("Node candidate is closed")); started = true; }
            return preparation;
        }
        @Override public synchronized RuntimePlan preparedPlan() {
            if (plan == null) throw new IllegalStateException("Node candidate is not prepared");
            return plan;
        }
        @Override public synchronized PreparedRuntimeGeneration seal(CompiledRuntimeSlice slice) {
            if (closed || sealed || plan == null) throw new IllegalStateException("Node candidate cannot seal");
            var compiled = Objects.requireNonNull(slice, "slice");
            if (!id().equals(compiled.runtimeId()) || compiled.plan() != plan) throw new IllegalArgumentException("compiled Node slice does not match prepared plan");
            sealed = true;
            var generation = new Generation(compiled, prepared,
                target.target().targetRevision(),
                target.capabilities().availableNames());
            prepared = Map.of();
            return generation;
        }
        @Override public synchronized Mono<Void> closeAsync() {
            if (close == null) close = Mono.defer(() -> {
                synchronized (Candidate.this) { closed = true; }
                return started ? preparation.onErrorResume(ignored -> Mono.empty()) : Mono.empty();
            }).then(Mono.<Void>fromRunnable(() -> {
                synchronized (Candidate.this) { if (!sealed) releasePrepared(prepared); prepared = Map.of(); plan = null; }
            })).cache();
            return close;
        }
        private void prepare() {
            var sources = index(target.facets());
            var values = new LinkedHashMap<ExecutionUnitKey, Prepared>();
            try {
                for (var entryId : target.affectedEntryIds().stream().sorted().toList()) {
                    var resolved = target.desired().require(entryId);
                    if (!(resolved.input() instanceof DesiredInputEntry entry) || !resolved.effective().enabled()) throw new IllegalArgumentException("affected Node entry is not enabled: " + entryId);
                    var source = source(entry.definitionRef(), sources);
                    var lease = acquire(source, sources);
                    try {
                        if (!lease.payload.descriptor.definitionId().equals(entry.definitionRef().definitionId())) throw new IllegalArgumentException("Node definition id does not match " + entryId);
                        var key = new ExecutionUnitKey(entryId);
                        var dependencies = target.unitDependencies().get(key);
                        if (dependencies == null) throw new IllegalArgumentException("missing Node unit dependencies for " + key);
                        var unit = ExecutionUnitPlan.builder(key, id(), source.facet().facet().executionTarget()).artifactId(source.facet().artifactId())
                            .provenance(source.facet().pluginId().value(), source.facet().facet().facetId().value(), source.facet().packageRevision()).dependencies(dependencies).build();
                        values.put(key, new Prepared(unit, entry.definitionRef(), entry.publicationRequirement(), resolved.resolvedConfig().orElseThrow(() -> new IllegalArgumentException("missing resolved config for " + entryId)), lease));
                    } catch (RuntimeException failure) { try { lease.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); } throw failure; }
                }
                var definitions = values.entrySet().stream().map(value -> DefinitionBindingPlan.builder(value.getValue().definition, value.getKey().value())
                    .unitKey(value.getKey()).publicationRequirement(value.getValue().publication).build()).toList();
                synchronized (this) { if (closed) throw new IllegalStateException("Node candidate is closed"); prepared = Map.copyOf(values); plan = RuntimePlan.of(id(), values.values().stream().map(Prepared::plan).toList(), definitions); }
            } catch (RuntimeException failure) { releasePrepared(values); throw failure; }
        }
    }

    private final class Generation implements PreparedRuntimeGeneration {
        private final Map<ExecutionUnitKey, RuntimeUnitGeneration> units;
        private final List<Unit> reverse;
        private Mono<Void> abort, retire;
        private Generation(CompiledRuntimeSlice slice, Map<ExecutionUnitKey, Prepared> prepared,
                           long revision, Set<String> capabilities) {
            var values = new LinkedHashMap<ExecutionUnitKey, RuntimeUnitGeneration>();
            for (var key : slice.dependencyFirst()) values.put(key, new Unit(
                Objects.requireNonNull(prepared.get(key), "missing Node prepared unit " + key),
                revision, capabilities));
            units = Collections.unmodifiableMap(values);
            reverse = slice.reverseDependency().stream().map(key -> (Unit) values.get(key)).toList();
        }
        @Override public Map<ExecutionUnitKey, RuntimeUnitGeneration> units() { return units; }
        @Override public synchronized Mono<Void> abortAsync() { if (abort == null) abort = Mono.<Void>fromRunnable(() -> release(false)).cache(); return abort; }
        @Override public synchronized Mono<Void> retireAsync() { if (retire == null) retire = Mono.<Void>fromRunnable(() -> release(true)).cache(); return retire; }
        private void release(boolean stopped) {
            RuntimeException failure = null;
            for (var unit : reverse) unit.closeAdmission();
            for (var unit : reverse) try { if (stopped) unit.retire(); else unit.abort(); } catch (RuntimeException cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
            if (failure != null) throw failure;
        }
    }

    private final class Unit implements RuntimeUnitGeneration {
        private final ExecutionUnitPlan plan; private final long revision;
        private final Set<String> capabilities;
        private Prepared prepared;
        private boolean admissionClosed, drained, released;
        private Scope scope; private NodeSidecar sidecar;
        private ContributionAdmission contributions;
        private String instanceId, reconcileId, drainId, stopId;
        private ExecutionObservation.State state = ExecutionObservation.State.PENDING;
        private Throwable failure;
        private Throwable startupTermination;
        private Unit(Prepared prepared, long revision, Set<String> capabilities) {
            this.prepared = Objects.requireNonNull(prepared, "prepared");
            plan = prepared.plan;
            this.revision = revision;
            this.capabilities = Set.copyOf(capabilities);
            instanceId = services.nextIdentity("node:" + plan.key().value());
        }
        @Override public ExecutionUnitPlan plan() { return plan; }
        @Override public RuntimeUnitFence fence() {
            return RuntimeUnitFence.builder(id(), plan.key())
                .unitTargetRevision(revision).runtimeInstanceId(instanceId)
                .build();
        }
        @Override public Mono<ExecutionObservation> reconcileAsync(String operation) {
            operation = operation(operation);
            final Scope created;
            try {
                synchronized (this) {
                    if (admissionClosed || released) return Mono.error(new IllegalStateException("Node unit admission is closed"));
                    if (sidecar != null && state == ExecutionObservation.State.ACTIVE) return operation.equals(reconcileId) ? Mono.just(snapshot()) : Mono.error(new IllegalStateException("stale Node reconcile operation"));
                    if (reconcileId != null && !operation.equals(reconcileId)) return Mono.error(new IllegalStateException("stale Node reconcile operation"));
                    reconcileId = operation;
                    created = services.scope().openChild("node:" + plan().key().value());
                    scope = created;
                    contributions = services.openContributionAdmission(plan.key());
                }
            } catch (RuntimeException startFailure) {
                return failStart(startFailure);
            }
            var entry = prepared.lease.payload.facet.facet().payload().resolve(prepared.lease.payload.descriptor.entrypoint());
            var disableRequest = RuntimeUnitDisableRequest.of(fence(),
                "node-fibra-disable");
            return NodeSidecar.start(entry, options,
                    () -> services.requestDisable(disableRequest))
                .flatMap(session -> { synchronized (Unit.this) { sidecar = session; }
                    observeTermination(session);
                    var parameters = new LinkedHashMap<String, Object>();
                    parameters.put("protocol",
                        prepared.lease.payload.descriptor.protocol());
                    parameters.put("config", prepared.config.toJava());
                    return session.request("fibra.start", parameters,
                            options.defaultRequestTimeout())
                        .then(register(created, session))
                        .then(Mono.fromSupplier(() -> {
                            synchronized (Unit.this) {
                                if (startupTermination != null) {
                                    throw reactor.core.Exceptions.propagate(
                                        startupTermination);
                                }
                                state = ExecutionObservation.State.ACTIVE;
                                failure = null;
                                published.put(plan().key(), Unit.this);
                                return snapshot();
                            }
                        }));
                }).onErrorResume(this::failStart);
        }
        @Override public void closeAdmission() {
            final ContributionAdmission closing;
            synchronized (this) {
                admissionClosed = true;
                closing = contributions;
            }
            if (closing != null) closing.closeAdmission();
        }
        @Override public Mono<ExecutionObservation> drainAsync(String operation, Instant deadline) {
            Objects.requireNonNull(deadline, "deadline"); operation = operation(operation);
            final ContributionAdmission closing;
            synchronized (this) {
                if (!admissionClosed) return Mono.error(new IllegalStateException("Node unit admission is still open"));
                if (drainId != null && !operation.equals(drainId)) return Mono.error(new IllegalStateException("stale Node drain operation"));
                if (drained) return Mono.just(snapshot());
                drainId = operation;
                closing = contributions;
            }
            return (closing == null ? Mono.<Void>empty() : closing.drainAsync())
                .then(Mono.fromSupplier(() -> {
                    synchronized (Unit.this) {
                        drained = true;
                        return snapshot();
                    }
                }));
        }
        @Override public Mono<ExecutionObservation> stopAsync(String operation, Instant deadline) {
            Objects.requireNonNull(deadline, "deadline"); operation = operation(operation);
            final NodeSidecar stopping; final Scope owner; final boolean failedRuntime;
            synchronized (this) {
                if (!admissionClosed) return Mono.error(new IllegalStateException("Node unit admission is still open"));
                if (!drained) return Mono.error(new IllegalStateException("Node unit has not drained"));
                if (stopId != null && !operation.equals(stopId)) return Mono.error(new IllegalStateException("stale Node stop operation"));
                if (released) return Mono.just(snapshot());
                stopId = operation; stopping = sidecar; owner = scope;
                failedRuntime = state == ExecutionObservation.State.FAILED;
            }
            var shutdown = stopping == null ? Mono.<Void>empty()
                : stopSidecar(stopping, !failedRuntime && stopping.isAlive());
            var closeScope = owner == null ? Mono.<Void>empty() : closeScope(owner);
            return Mono.whenDelayError(shutdown, closeScope)
                .then(Mono.fromSupplier(() -> { synchronized (Unit.this) { releaseLease(); sidecar = null; scope = null; contributions = null; state = ExecutionObservation.State.PENDING; failure = null; startupTermination = null; published.remove(plan().key(), Unit.this); return snapshot(); } }));
        }
        @Override public synchronized ExecutionObservation snapshot() {
            var details = state == ExecutionObservation.State.PENDING && sidecar == null ? List.<ExecutionObservation.Detail>of() : List.of(ExecutionObservation.Detail.builder()
                .unitTargetRevision(revision).executionId(plan().key().value()).runtimeInstanceId(instanceId == null ? "unstarted" : instanceId)
                .lifecycleOperationId(reconcileId == null ? "unstarted" : reconcileId)
                .capabilities(capabilities).state(state)
                .failure(failure == null ? null : new ExecutionObservation.Failure("NODE_RUNTIME", failure.toString(), Map.of("unit", plan().key().value()))).build());
            return ExecutionObservation.of(plan().pluginId(), plan().facetId(), plan().runtimeId(), plan().executionTarget(), details);
        }
        private Mono<Void> register(Scope owner, NodeSidecar session) {
            var bindings = bindings(prepared.lease.payload.descriptor, session);
            return bindings.isEmpty() ? Mono.empty() : contributions
                .registerAll(owner.context(), bindings,
                    () -> Mono.empty())
                .then();
        }
        private Mono<ExecutionObservation> failStart(Throwable start) {
            closeAdmission();
            final Scope failedScope; final NodeSidecar failedSidecar;
            synchronized (this) {
                state = ExecutionObservation.State.FAILED;
                failure = start;
                failedScope = scope;
                failedSidecar = sidecar;
                published.put(plan().key(), this);
            }
            var closeScope = failedScope == null ? Mono.<Void>empty()
                : closeScope(failedScope);
            var closeSidecar = failedSidecar == null ? Mono.<Void>empty()
                : closeSidecar(failedSidecar);
            return Mono.whenDelayError(closeScope, closeSidecar)
                .onErrorResume(cleanup -> {
                    if (cleanup != start) start.addSuppressed(cleanup);
                    synchronized (Unit.this) { failure = start; }
                    return Mono.empty();
                })
                .then(Mono.fromSupplier(this::snapshot));
        }
        private void observeTermination(NodeSidecar session) {
            session.termination().subscribe(
                ignored -> { },
                error -> onUnexpectedTermination(error, session),
                () -> onUnexpectedTermination(new IllegalStateException(
                    "Node sidecar terminated while active"), session));
        }
        private void onUnexpectedTermination(Throwable terminated,
                                             NodeSidecar terminatedSession) {
            final boolean active;
            synchronized (this) {
                if (admissionClosed || sidecar != terminatedSession) return;
                active = state == ExecutionObservation.State.ACTIVE;
                if (!active && state != ExecutionObservation.State.PENDING) return;
                if (active) {
                    state = ExecutionObservation.State.FAILED;
                    failure = terminated;
                    published.put(plan().key(), this);
                } else {
                    startupTermination = terminated;
                }
            }
            closeAdmission();
            if (!active) return;
            services.requestReconcile(Set.of(fence()),
                "node-sidecar-terminated");
        }
        private Mono<Void> stopSidecar(NodeSidecar stopping, boolean graceful) {
            if (!graceful) return closeSidecar(stopping);
            var stop = Mono.defer(() -> stopping.request("fibra.stop", Map.of(),
                    Duration.ofMillis(Math.min(1000,
                        options.defaultRequestTimeout().toMillis())))
                .then());
            return stop.onErrorResume(requestFailure -> closeSidecar(stopping)
                    .onErrorMap(cleanupFailure -> {
                        if (cleanupFailure != requestFailure
                            && java.util.Arrays.stream(cleanupFailure.getSuppressed())
                                .noneMatch(suppressed -> suppressed == requestFailure)) {
                            cleanupFailure.addSuppressed(requestFailure);
                        }
                        return cleanupFailure;
                    })
                    .then(Mono.error(requestFailure)))
                .then(closeSidecar(stopping));
        }
        private Mono<Void> closeSidecar(NodeSidecar closing) {
            return Mono.defer(closing::closeAsync).doOnSuccess(ignored -> {
                synchronized (Unit.this) {
                    if (sidecar == closing) sidecar = null;
                }
            });
        }
        private Mono<Void> closeScope(Scope closing) {
            return services.releaseScope(closing).doOnSuccess(ignored -> {
                synchronized (Unit.this) {
                    if (scope == closing) {
                        scope = null;
                        contributions = null;
                    }
                }
            });
        }
        private synchronized void abort() {
            if (sidecar != null || scope != null || state != ExecutionObservation.State.PENDING) throw new IllegalStateException("started Node unit cannot abort");
            releaseLease(); published.remove(plan().key(), this);
        }
        private synchronized void retire() { if (!released) throw new IllegalStateException("Node unit must stop before retirement"); published.remove(plan().key(), this); }
        private void releaseLease() {
            if (!released) {
                prepared.lease.close();
                prepared = null;
                released = true;
            }
        }
    }

    private List<ContributionBinding<?, ?, ?>> bindings(NodeFacetDescriptor descriptor, NodeSidecar sidecar) {
        var values = new ArrayList<ContributionBinding<?, ?, ?>>();
        descriptor.contributions().forEach(endpoint -> values.add(binding(endpoint, sidecar)));
        return List.copyOf(values);
    }
    private <D, I, O> ContributionBinding<D, I, O> binding(NodeEndpointManifest endpoint, NodeSidecar sidecar) {
        @SuppressWarnings("unchecked") var kind = (ContributionKind<D, I, O>)
            services.contributionKinds().find(endpoint.kind()).orElseThrow(() ->
                new NodeRuntimeException(null,
                    "unknown contribution kind " + endpoint.kind(), null));
        ContributionCodec<D, I, O> codec = codec(endpoint, null);
        D descriptor = codec.decodeDescriptor(
            com.sstlfsj.fibra.value.LiteralValue.of(endpoint.descriptor()));
        return new ContributionBinding<>(kind, endpoint.name(), descriptor, (invocation, input) -> Mono.defer(() -> {
            var cancellation = codec.cancellationToken(input);
            if (cancellation.isCancelled()) return Mono.error(codec.cancellationException());
            var parameters = new LinkedHashMap<String, Object>();
            parameters.put("schemaVersion", codec.schemaVersion());
            parameters.put("input", codec.encodeInput(input).toJava());
            var request = sidecar.beginRequest(endpoint.method(), parameters,
                cancellation);
            var ownership = invocation.effects().add(request);
            return ownership.ready().then(request.result())
                .flatMap(response -> response.value() == null
                    ? Mono.error(new NodeRpcException(NodeRpcPhase.PROTOCOL,
                        "Node response contains a null result: " + endpoint.method()))
                    : Mono.just(response.value()))
                .flatMap(result -> cancellation.isCancelled()
                    ? Mono.error(codec.cancellationException())
                    : Mono.fromCallable(() -> codec.decodeOutput(
                        com.sstlfsj.fibra.value.LiteralValue.of(result))));
        }));
    }
    @SuppressWarnings("unchecked") private <D, I, O> ContributionCodec<D, I, O> codec(NodeEndpointManifest endpoint, ArtifactId artifact) {
        var kind = services.contributionKinds().find(endpoint.kind()).orElseThrow(() -> new NodeRuntimeException(artifact, "unknown contribution kind " + endpoint.kind(), null));
        var codec = kind.codec().orElseThrow(() -> new NodeRuntimeException(artifact, "contribution kind is not remote: " + endpoint.kind(), null));
        if (codec.schemaVersion() != endpoint.schemaVersion()) throw new NodeRuntimeException(artifact, "schema version mismatch for contribution " + endpoint.name(), null);
        return (ContributionCodec<D, I, O>) codec;
    }
    private static Map<ArtifactId, PluginFacetSource> index(List<PluginFacetSource> sources) {
        var values = new LinkedHashMap<ArtifactId, PluginFacetSource>();
        for (var source : sources) if (values.putIfAbsent(source.facet().artifactId(), source) != null) throw new IllegalArgumentException("duplicate Node facet artifact " + source.facet().artifactId());
        return values;
    }
    private static PluginFacetSource source(PluginDefinitionRef ref, Map<ArtifactId, PluginFacetSource> sources) {
        var matches = sources.values().stream().filter(value -> value.facet().pluginId().value().equals(ref.pluginId()) && value.facet().facet().facetId().value().equals(ref.facetId())).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Node definition must identify exactly one selected facet: " + ref);
        return matches.getFirst();
    }
    private void releasePrepared(Map<ExecutionUnitKey, Prepared> values) {
        RuntimeException failure = null;
        for (var value : new ArrayList<>(values.values()).reversed()) try { value.lease.close(); } catch (RuntimeException cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
        if (failure != null) throw failure;
    }
    private void releaseAll(List<PayloadLease> leases, RuntimeException primary) {
        for (var lease : leases.reversed()) {
            try { lease.close(); } catch (RuntimeException cleanup) {
                primary.addSuppressed(cleanup);
            }
        }
    }
    private static String operation(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("lifecycle operation id must not be blank"); return value; }
    private record Prepared(ExecutionUnitPlan plan, PluginDefinitionRef definition, com.sstlfsj.fibra.config.PublicationRequirement publication, com.sstlfsj.fibra.value.LiteralValue config, PayloadLease lease) { }
    private record PayloadKey(ArtifactId artifactId, String packageRevision,
                              String facetId, String payloadDigest,
                              List<String> dependencies,
                              String contractIdentity) {
        private PayloadKey { dependencies = List.copyOf(dependencies); }
        private String identity() {
            return artifactId + ":" + packageRevision + ":" + facetId + ":"
                + payloadDigest + ":" + dependencies + ":" + contractIdentity;
        }
    }
    private static final class Payload { private final PayloadKey key; private final ManagedFacet facet; private final NodeFacetDescriptor descriptor; private int references; private Payload(PayloadKey key, ManagedFacet facet, NodeFacetDescriptor descriptor) { this.key = key; this.facet = facet; this.descriptor = descriptor; } }
    private final class PayloadLease implements AutoCloseable {
        private final Payload payload;
        private final List<PayloadLease> dependencies;
        private boolean payloadReleased;
        private boolean closed;
        private PayloadLease(Payload payload, List<PayloadLease> dependencies) {
            this.payload = payload;
            this.dependencies = new ArrayList<>(dependencies);
        }
        @Override public synchronized void close() {
            if (closed) return;
            if (!payloadReleased) {
                release(payload);
                payloadReleased = true;
            }
            RuntimeException failure = null;
            for (var index = dependencies.size() - 1; index >= 0; index--) {
                var dependency = dependencies.get(index);
                if (dependency == null) continue;
                try {
                    dependency.close();
                    dependencies.set(index, null);
                } catch (RuntimeException cleanup) {
                    if (failure == null) failure = cleanup;
                    else failure.addSuppressed(cleanup);
                }
            }
            if (failure != null) throw failure;
            closed = true;
        }
    }
}
