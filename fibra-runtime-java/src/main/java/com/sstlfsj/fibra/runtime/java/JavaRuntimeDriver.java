package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.FibraException;
import com.sstlfsj.fibra.ManagedPluginControl;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstance;
import com.sstlfsj.fibra.Scope;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.bridge.ContributionAdmission;
import com.sstlfsj.fibra.bridge.ContributionServices;
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
import com.sstlfsj.fibra.engine.RuntimeUnitDisableRequest;
import com.sstlfsj.fibra.engine.RuntimeUnitGeneration;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.MalformedURLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Java facet 的唯一 runtime owner。静态 wiring 与实例生命周期均不泄漏到 Engine。 */
public final class JavaRuntimeDriver implements RuntimeDriver {
    private static final List<String> DEFAULT_PARENT_PACKAGES = List.of(
        "java.", "javax.", "jdk.", "sun.", "com.sstlfsj.fibra.",
        "reactor.", "org.reactivestreams.", "org.slf4j.");

    private final RuntimeHostServices services;
    private final List<JavaBuiltInPackage> builtIns;
    private final ClassLoader parent;
    private final List<String> parentPackages;
    private final LoaderCloser closer;
    private final JavaFacetDescriptorReader descriptors = new JavaFacetDescriptorReader();
    private final Map<WiringKey, StaticWiring> wiringPool = new LinkedHashMap<>();
    private final Map<ExecutionUnitKey, RuntimeUnitGeneration> publishedUnits = new ConcurrentHashMap<>();
    private Mono<Void> close;

    JavaRuntimeDriver(RuntimeHostServices services, List<JavaBuiltInPackage> builtIns) {
        this(services, builtIns, JavaRuntimeDriver.class.getClassLoader(), DEFAULT_PARENT_PACKAGES,
            PluginClassLoader::close);
    }

    JavaRuntimeDriver(RuntimeHostServices services, List<JavaBuiltInPackage> builtIns,
                      ClassLoader parent,
                      List<String> parentPackages, LoaderCloser closer) {
        this.services = Objects.requireNonNull(services, "services");
        this.builtIns = List.copyOf(Objects.requireNonNull(builtIns, "builtIns"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.parentPackages = List.copyOf(parentPackages);
        this.closer = Objects.requireNonNull(closer, "closer");
    }

    @Override public RuntimeId id() { return JavaRuntimeProvider.RUNTIME_ID; }

    @Override
    public Mono<RuntimeArtifactInspection> probe(PluginFacetSource source) {
        Objects.requireNonNull(source, "source");
        return Mono.fromCallable(() -> inspectFacet(source.facet()));
    }

    @Override
    public Mono<RuntimeArtifactInspection> inspect(ManagedFacet facet) {
        return Mono.fromCallable(() -> inspectFacet(facet));
    }

    @Override
    public synchronized RuntimeCandidate createCandidate(RuntimeTargetSlice target) {
        requireOpen();
        if (!id().equals(Objects.requireNonNull(target, "target").runtimeId())) {
            throw new IllegalArgumentException("Java runtime target identity mismatch");
        }
        return new Candidate(target);
    }

    @Override
    public synchronized RuntimeDriverSnapshot snapshot() {
        var values = new LinkedHashMap<ExecutionUnitKey, ExecutionObservation>();
        publishedUnits.forEach((key, unit) -> values.put(key, unit.snapshot()));
        return new RuntimeDriverSnapshot(id(), values);
    }

    @Override
    public synchronized Mono<Void> closeAsync() {
        if (close == null) {
            close = Mono.<Void>fromRunnable(() -> {
                synchronized (JavaRuntimeDriver.this) {
                    if (wiringPool.values().stream().anyMatch(wiring -> wiring.references != 0)) {
                        throw new IllegalStateException("Java runtime driver still has leased class spaces");
                    }
                    closeUnusedWireings();
                    publishedUnits.clear();
                }
            }).cache();
        }
        return close;
    }

    private RuntimeArtifactInspection inspectFacet(ManagedFacet facet) {
        requireOpen();
        if (!id().equals(Objects.requireNonNull(facet, "facet").facet().runtimeId())) {
            throw new IllegalArgumentException("facet runtime is not Java");
        }
        descriptors.read(facet.facet());
        return new RuntimeArtifactInspection(id(), facet.artifactId());
    }

    private synchronized void requireOpen() {
        if (close != null) throw new IllegalStateException("Java runtime driver is closed");
    }

    private synchronized StaticLease acquire(PluginFacetSource source,
                                             Map<ArtifactId, PluginFacetSource> sources) {
        return acquire(source, sources, new LinkedHashSet<>());
    }

    private StaticLease acquire(PluginFacetSource source,
                                Map<ArtifactId, PluginFacetSource> sources,
                                Set<ArtifactId> visiting) {
        var artifactId = source.facet().artifactId();
        if (!visiting.add(artifactId)) {
            throw new IllegalArgumentException("Java static dependency cycle at " + artifactId);
        }
        var dependencyLeases = new ArrayList<StaticLease>();
        try {
            for (var dependency : source.dependencies()) {
                var local = sources.get(dependency.artifactId());
                if (local != null) dependencyLeases.add(acquire(local, sources, visiting));
            }
            var descriptor = descriptors.read(source.facet().facet());
            JavaClassIndex.validate(artifactId, List.of(source.facet().facet().payload()), parent,
                parentPackages);
            var dependencyKeys = dependencyLeases.stream().map(lease -> lease.wiring.key.identity())
                .sorted().toList();
            var key = new WiringKey(source.facet().packageRevision(),
                source.facet().facet().facetId().value(), source.facet().facet().payloadDigest(),
                dependencyKeys, JavaRuntimeProvider.CONTRACT_IDENTITY);
            var wiring = wiringPool.get(key);
            if (wiring == null) {
                wiring = createWiring(key, source.facet(), descriptor,
                    dependencyLeases.stream().map(lease -> lease.wiring).toList());
                wiringPool.put(key, wiring);
            }
            wiring.references++;
            return new StaticLease(wiring, List.copyOf(dependencyLeases));
        } catch (RuntimeException failure) {
            releaseAll(dependencyLeases, failure);
            throw failure;
        } finally {
            visiting.remove(artifactId);
        }
    }

    private StaticWiring createWiring(WiringKey key, ManagedFacet facet,
                                      JavaFacetDescriptor descriptor,
                                      List<StaticWiring> dependencies) {
        PluginClassLoader loader = null;
        try {
            loader = new PluginClassLoader(List.of(facet.facet().payload().toUri().toURL()), parent,
                parentPackages);
            loader.dependencies(dependencies.stream().map(value -> value.loader).toList());
            JavaClassSpace.validate(facet.artifactId(), descriptor, loader);
            return new StaticWiring(key, facet, loader,
                JavaClassSpace.entry(facet.artifactId(), descriptor, loader));
        } catch (MalformedURLException failure) {
            throw new JavaRuntimeException(JavaRuntimePhase.LOAD, facet.artifactId(),
                "cannot create Java facet loader", failure);
        } catch (RuntimeException failure) {
            if (loader != null) close(loader, failure);
            throw failure;
        }
    }

    private void releaseAll(List<StaticLease> leases, RuntimeException primary) {
        for (var lease : leases.reversed()) {
            try {
                lease.close();
            } catch (RuntimeException cleanup) {
                primary.addSuppressed(cleanup);
            }
        }
    }

    private synchronized void release(StaticWiring wiring) {
        if (wiring.references < 1) throw new IllegalStateException("Java class space lease underflow");
        if (--wiring.references == 0) {
            try {
                closer.close(wiring.loader);
                wiringPool.remove(wiring.key, wiring);
            } catch (IOException | RuntimeException failure) {
                wiring.references++;
                throw new JavaRuntimeException(JavaRuntimePhase.CLOSE, wiring.facet.artifactId(),
                    "cannot close Java class space", failure);
            }
        }
    }

    private void close(PluginClassLoader loader, RuntimeException primary) {
        try {
            closer.close(loader);
        } catch (IOException | RuntimeException cleanup) {
            primary.addSuppressed(cleanup);
        }
    }

    private void closeUnusedWireings() {
        var values = new ArrayList<>(wiringPool.values());
        for (var wiring : values.reversed()) {
            if (wiring.references != 0) continue;
            try {
                closer.close(wiring.loader);
                wiringPool.remove(wiring.key, wiring);
            } catch (IOException failure) {
                throw new JavaRuntimeException(JavaRuntimePhase.CLOSE, wiring.facet.artifactId(),
                    "cannot close Java class space", failure);
            }
        }
    }

    private final class Candidate implements RuntimeCandidate {
        private final RuntimeTargetSlice target;
        private final Mono<Void> preparation = Mono.<Void>fromRunnable(this::prepare).cache();
        private boolean closed;
        private boolean started;
        private boolean sealed;
        private RuntimePlan plan;
        private Map<ExecutionUnitKey, PreparedUnit> prepared = Map.of();
        private Mono<Void> close;

        private Candidate(RuntimeTargetSlice target) { this.target = target; }

        @Override
        public Mono<Void> prepareAsync() {
            synchronized (this) {
                if (closed) return Mono.error(new IllegalStateException("Java runtime candidate is closed"));
                started = true;
            }
            return preparation;
        }

        @Override
        public synchronized RuntimePlan preparedPlan() {
            if (plan == null) throw new IllegalStateException("Java runtime candidate is not prepared");
            return plan;
        }

        @Override
        public synchronized PreparedRuntimeGeneration seal(CompiledRuntimeSlice slice) {
            if (plan == null || closed || sealed) throw new IllegalStateException("Java runtime candidate cannot seal");
            var compiled = Objects.requireNonNull(slice, "slice");
            if (!id().equals(compiled.runtimeId()) || !compiled.plan().units().equals(plan.units())
                || !compiled.plan().definitions().equals(plan.definitions())) {
                throw new IllegalArgumentException("compiled Java runtime slice does not match prepared plan");
            }
            sealed = true;
            var generation = new Generation(compiled, prepared,
                target.target().targetRevision(),
                target.capabilities().availableNames());
            prepared = Map.of();
            return generation;
        }

        @Override
        public synchronized Mono<Void> closeAsync() {
            if (close == null) {
                close = Mono.defer(() -> {
                    synchronized (Candidate.this) { closed = true; }
                    return started ? preparation.onErrorResume(ignored -> Mono.empty()) : Mono.empty();
                }).then(Mono.<Void>fromRunnable(() -> {
                    synchronized (Candidate.this) {
                        if (!sealed) releasePrepared(prepared);
                        prepared = Map.of();
                        plan = null;
                    }
                })).cache();
            }
            return close;
        }

        private void prepare() {
            var sources = sourceIndex(target.facets());
            var values = new LinkedHashMap<ExecutionUnitKey, PreparedUnit>();
            try {
                for (var entryId : target.affectedEntryIds().stream().sorted().toList()) {
                    var resolved = target.desired().entries().get(entryId);
                    if (!(resolved.input() instanceof DesiredInputEntry entry)
                        || !resolved.effective().enabled()) {
                        throw new IllegalArgumentException("affected Java entry is not enabled: " + entryId);
                    }
                    var definitionRef = entry.definitionRef();
                    var source = dynamicSource(definitionRef, sources);
                    var lease = source == null ? NoopLease.INSTANCE : acquire(source, sources);
                    try {
                        var catalog = source == null
                            ? builtIn(definitionRef, target).definition
                            : Objects.requireNonNull(((StaticLease) lease).wiring.definition,
                                "Java facet has no plugin definition");
                        if (!catalog.definition().name().equals(definitionRef.definitionId())) {
                            throw new IllegalArgumentException("Java definition id does not match " + entryId);
                        }
                        var binding = bind(catalog, resolved.resolvedConfig().orElseThrow(() ->
                            new IllegalArgumentException("missing resolved config for " + entryId)));
                        var key = new ExecutionUnitKey(entryId);
                        var unit = source == null
                            ? builtInPlan(key, definitionRef, target, requireDependencies(key))
                            : ExecutionUnitPlan.builder(key, id(), source.facet().facet().executionTarget())
                                .artifactId(source.facet().artifactId())
                                .provenance(source.facet().pluginId().value(),
                                    source.facet().facet().facetId().value(), source.facet().packageRevision())
                            .dependencies(requireDependencies(key)).build();
                        values.put(key, new PreparedUnit(unit, definitionRef,
                            entry.publicationRequirement(), binding,
                            resolved.effective().realms(),
                            resolved.effective().intercepts(), lease));
                    } catch (RuntimeException failure) {
                        try {
                            lease.close();
                        } catch (RuntimeException cleanup) {
                            failure.addSuppressed(cleanup);
                        }
                        throw failure;
                    }
                }
                var definitions = values.entrySet().stream().map(value ->
                    DefinitionBindingPlan.builder(value.getValue().definition, value.getKey().value())
                        .unitKey(value.getKey()).publicationRequirement(value.getValue().publication).build()).toList();
                synchronized (this) {
                    if (closed) throw new IllegalStateException("Java runtime candidate is closed");
                    prepared = Map.copyOf(values);
                    plan = RuntimePlan.of(id(), values.values().stream().map(PreparedUnit::plan).toList(), definitions);
                }
            } catch (RuntimeException failure) {
                releasePrepared(values);
                throw failure;
            }
        }

        private List<ExecutionUnitKey> requireDependencies(ExecutionUnitKey key) {
            var dependencies = target.unitDependencies().get(key);
            if (dependencies == null) throw new IllegalArgumentException("missing Java unit dependencies for " + key);
            return dependencies;
        }
    }

    private final class Generation implements PreparedRuntimeGeneration {
        private final Map<ExecutionUnitKey, RuntimeUnitGeneration> units;
        private final List<Unit> reverseDependency;
        private Mono<Void> abort;
        private Mono<Void> retire;

        private Generation(CompiledRuntimeSlice slice, Map<ExecutionUnitKey, PreparedUnit> prepared,
                           long targetRevision, Set<String> capabilities) {
            var values = new LinkedHashMap<ExecutionUnitKey, RuntimeUnitGeneration>();
            for (var key : slice.dependencyFirst()) {
                var unit = Objects.requireNonNull(prepared.get(key), "missing Java prepared unit " + key);
                values.put(key, new Unit(unit, targetRevision, capabilities));
            }
            units = Map.copyOf(values);
            reverseDependency = slice.reverseDependency().stream()
                .map(key -> (Unit) values.get(key)).toList();
        }

        @Override public Map<ExecutionUnitKey, RuntimeUnitGeneration> units() { return units; }

        @Override
        public synchronized Mono<Void> abortAsync() {
            if (abort == null) abort = Mono.<Void>fromRunnable(() -> release(false)).cache();
            return abort;
        }

        @Override
        public synchronized Mono<Void> retireAsync() {
            if (retire == null) retire = Mono.<Void>fromRunnable(() -> release(true)).cache();
            return retire;
        }

        private void release(boolean requireStopped) {
            var failures = new ArrayList<RuntimeException>();
            for (var unit : reverseDependency) unit.closeAdmission();
            for (var javaUnit : reverseDependency) {
                try {
                    if (requireStopped) javaUnit.retire(); else javaUnit.abort();
                } catch (RuntimeException failure) { failures.add(failure); }
            }
            if (!failures.isEmpty()) {
                var failure = new IllegalStateException("cannot release Java runtime generation");
                failures.forEach(failure::addSuppressed);
                throw failure;
            }
        }
    }

    private final class Unit implements RuntimeUnitGeneration {
        private final ExecutionUnitPlan plan;
        private final long unitTargetRevision;
        private final String runtimeInstanceId;
        private final Set<String> capabilities;
        private PreparedUnit prepared;
        private boolean admissionClosed;
        private boolean released;
        private Scope scope;
        private ContributionAdmission contributions;
        private PluginInstance<?> instance;
        private String operationId;
        private String drainOperationId;
        private boolean drained;
        private boolean observedActive;
        private boolean replacementRequested;
        private ExecutionObservation.State state = ExecutionObservation.State.PENDING;
        private Throwable failure;

        private Unit(PreparedUnit prepared, long unitTargetRevision,
                     Set<String> capabilities) {
            this.prepared = Objects.requireNonNull(prepared, "prepared");
            plan = prepared.plan;
            this.unitTargetRevision = unitTargetRevision;
            runtimeInstanceId = services.nextIdentity(
                "java:" + plan.key().value());
            this.capabilities = Set.copyOf(capabilities);
        }

        @Override public ExecutionUnitPlan plan() { return plan; }

        @Override
        public Mono<ExecutionObservation> reconcileAsync(String lifecycleOperationId) {
            var operation = requiredOperation(lifecycleOperationId);
            return Mono.defer(() -> {
                final Context mountedContext;
                try {
                    synchronized (this) {
                        if (admissionClosed || released) return Mono.error(new IllegalStateException("Java unit admission is closed"));
                        if (instance != null) {
                            operationId = operation;
                            return instance.settled().then(Mono.fromSupplier(
                                this::snapshot));
                        }
                        operationId = operation;
                        scope = services.scope().openChild("java:" + plan().key().value());
                        contributions = services.openContributionAdmission(plan.key());
                        var context = policies(scope.context(), prepared.realms,
                            prepared.intercepts)
                            .withRealm(ContributionServices.REGISTRAR,
                                runtimeInstanceId)
                            .withRealm(ManagedPluginControl.KEY,
                                runtimeInstanceId);
                        context.services().provide(ContributionServices.REGISTRAR,
                            contributions);
                        context.services().provide(ManagedPluginControl.KEY,
                            this::requestDisable);
                        mountedContext = context;
                    }
                } catch (RuntimeException startFailure) {
                    return failStart(startFailure);
                }
                final PluginInstance<?> mounted;
                try {
                    mounted = mount(mountedContext, prepared.binding,
                        plan().key().value());
                } catch (RuntimeException startFailure) {
                    return failStart(startFailure);
                }
                synchronized (this) {
                    instance = mounted;
                }
                try {
                    ownStateObservation(mounted);
                } catch (RuntimeException | Error observationFailure) {
                    return failStart(observationFailure);
                }
                return mounted.settled().then(Mono.fromSupplier(() -> {
                    synchronized (Unit.this) {
                        publishedUnits.put(plan().key(), Unit.this);
                        return snapshot();
                    }
                })).onErrorResume(this::failStart);
            });
        }

        @Override public void closeAdmission() {
            final ContributionAdmission closing;
            synchronized (this) {
                admissionClosed = true;
                closing = contributions;
            }
            if (closing != null) closing.closeAdmission();
        }

        @Override
        public Mono<ExecutionObservation> drainAsync(String lifecycleOperationId, Instant deadline) {
            requireDeadline(deadline);
            var operation = requiredOperation(lifecycleOperationId);
            final ContributionAdmission closing;
            synchronized (this) {
                if (!admissionClosed) return Mono.error(new IllegalStateException("Java unit admission is still open"));
                if (drainOperationId != null && !operation.equals(drainOperationId)) {
                    return Mono.error(new IllegalStateException("stale Java drain operation"));
                }
                drainOperationId = operation;
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

        @Override
        public Mono<ExecutionObservation> stopAsync(String lifecycleOperationId, Instant deadline) {
            requireDeadline(deadline);
            var operation = requiredOperation(lifecycleOperationId);
            final Scope closing;
            synchronized (this) {
                if (!admissionClosed) return Mono.error(new IllegalStateException("Java unit admission is still open"));
                if (released) return Mono.just(snapshot());
                if (!drained) return Mono.error(new IllegalStateException("Java unit has not drained"));
                operationId = operation;
                closing = scope;
            }
            return (closing == null ? Mono.<Void>empty()
                : services.releaseScope(closing))
                .then(Mono.fromSupplier(() -> {
                    synchronized (Unit.this) {
                        releaseLease();
                        instance = null;
                        scope = null;
                        contributions = null;
                        state = ExecutionObservation.State.PENDING;
                        failure = null;
                        publishedUnits.remove(plan().key(), Unit.this);
                        return snapshot();
                    }
                }));
        }

        @Override public synchronized ExecutionObservation snapshot() {
            var live = liveState();
            var liveFailure = liveFailure(live);
            var executions = live == ExecutionObservation.State.PENDING && instance == null ? List.<ExecutionObservation.Detail>of()
                : List.of(ExecutionObservation.Detail.builder().unitTargetRevision(unitTargetRevision)
                    .executionId(plan().key().value()).runtimeInstanceId(runtimeInstanceId)
                    .lifecycleOperationId(operationId == null ? "unstarted" : operationId)
                    .capabilities(capabilities).state(live)
                    .failure(liveFailure == null ? null : new ExecutionObservation.Failure(
                        instance == null ? "JAVA_RUNTIME" : "JAVA_PLUGIN",
                        liveFailure.toString(), Map.of("unit", plan().key().value())))
                    .build());
            return ExecutionObservation.of(plan().pluginId(), plan().facetId(), plan().runtimeId(), plan().executionTarget(), executions);
        }

        private void ownStateObservation(PluginInstance<?> mounted) {
            var observation = mounted.states().subscribe(ignored ->
                onInstanceStateChanged(mounted));
            try {
                scope.context().effects().add(() -> Mono.fromRunnable(
                    observation::dispose));
            } catch (RuntimeException | Error ownershipFailure) {
                observation.dispose();
                throw ownershipFailure;
            }
        }

        private void onInstanceStateChanged(PluginInstance<?> mounted) {
            boolean replace;
            synchronized (this) {
                if (instance != mounted || admissionClosed || released) return;
                var live = liveState();
                if (live == ExecutionObservation.State.ACTIVE) {
                    observedActive = true;
                }
                replace = live == ExecutionObservation.State.FAILED
                    && observedActive && !replacementRequested;
                if (replace) replacementRequested = true;
            }
            if (replace) {
                closeAdmission();
                services.requestObservationRefresh(fence());
                services.requestReconcile(Set.of(fence()),
                    "java-plugin-failed");
            } else {
                services.requestObservationRefresh(fence());
            }
        }

        private RuntimeUnitFence fence() {
            return RuntimeUnitFence.builder(id(), plan().key())
                .unitTargetRevision(unitTargetRevision)
                .runtimeInstanceId(runtimeInstanceId).build();
        }

        private ExecutionObservation.State liveState() {
            if (instance == null || state == ExecutionObservation.State.FAILED) {
                return state;
            }
            return switch (instance.state()) {
                case ACTIVE -> ExecutionObservation.State.ACTIVE;
                case FAILED -> ExecutionObservation.State.FAILED;
                case PENDING, STARTING, STOPPING, DISPOSED ->
                    ExecutionObservation.State.PENDING;
            };
        }

        private Throwable liveFailure(ExecutionObservation.State live) {
            if (live != ExecutionObservation.State.FAILED) return null;
            if (failure != null) return failure;
            return instance == null ? null : instance.failure().orElseGet(() ->
                new IllegalStateException("Java plugin failed without a cause"));
        }

        private Mono<ExecutionObservation> failStart(Throwable startFailure) {
            closeAdmission();
            final Scope failedScope;
            synchronized (this) {
                state = ExecutionObservation.State.FAILED;
                failure = startFailure;
                failedScope = scope;
                publishedUnits.put(plan().key(), this);
            }
            var closeScope = failedScope == null ? Mono.<Void>empty()
                : services.releaseScope(failedScope);
            return closeScope
                .then(Mono.fromSupplier(() -> {
                    synchronized (Unit.this) {
                        if (scope == failedScope) {
                            scope = null;
                            contributions = null;
                            instance = null;
                        }
                        return snapshot();
                    }
                }))
                .onErrorResume(cleanupFailure -> {
                    synchronized (Unit.this) {
                        if (cleanupFailure != startFailure) {
                            startFailure.addSuppressed(cleanupFailure);
                        }
                        return Mono.just(snapshot());
                    }
                });
        }

        private void requestDisable(PluginInstance<?> requested) {
            Objects.requireNonNull(requested, "requested");
            synchronized (this) {
                if (!plan().key().value().equals(requested.id())
                    || instance != null && instance != requested) {
                    throw new FibraException(
                        FibraException.PLUGIN_DISABLE_UNAVAILABLE,
                        "plugin disable is restricted to the unit root instance");
                }
            }
            services.requestDisable(RuntimeUnitDisableRequest.of(fence(),
                "java-managed-plugin-control"));
        }

        private synchronized void abort() {
            if (instance != null || scope != null || state != ExecutionObservation.State.PENDING) {
                throw new IllegalStateException("started Java unit cannot abort");
            }
            releaseLease();
            publishedUnits.remove(plan().key(), this);
        }

        private synchronized void retire() {
            if (!released) throw new IllegalStateException("Java unit must stop before retirement");
            publishedUnits.remove(plan().key(), this);
        }

        private void releaseLease() {
            if (!released) {
                prepared.lease.close();
                prepared = null;
                released = true;
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PluginDefinition.Prepared<?> bind(JavaDefinitionEntry<?> catalog,
                                                       com.sstlfsj.fibra.value.LiteralValue config) {
        return ((JavaDefinitionEntry) catalog).bind(config);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PluginInstance<?> mount(Context context,
                                           PluginDefinition.Prepared<?> binding,
                                           String instanceId) {
        return context.plugins().mount(instanceId,
            (PluginDefinition.Prepared) binding);
    }

    private static Context policies(Context context,
                                    Map<String, com.sstlfsj.fibra.config.DesiredInputGraph.EffectivePolicyValue> realms,
                                    Map<String, com.sstlfsj.fibra.config.DesiredInputGraph.EffectivePolicyValue> intercepts) {
        var result = context;
        for (var entry : new TreeMap<>(realms).entrySet()) {
            result = result.withRealm(entry.getKey(), entry.getValue().value().toJava());
        }
        for (var entry : new TreeMap<>(intercepts).entrySet()) {
            result = result.withIntercept(entry.getKey(), entry.getValue().value().toJava());
        }
        return result;
    }

    private static Map<ArtifactId, PluginFacetSource> sourceIndex(List<PluginFacetSource> sources) {
        var result = new LinkedHashMap<ArtifactId, PluginFacetSource>();
        for (var source : sources) {
            if (result.putIfAbsent(source.facet().artifactId(), source) != null) {
                throw new IllegalArgumentException("duplicate Java facet artifact " + source.facet().artifactId());
            }
        }
        return result;
    }

    private static PluginFacetSource dynamicSource(PluginDefinitionRef ref,
                                                   Map<ArtifactId, PluginFacetSource> sources) {
        var matches = sources.values().stream().filter(source ->
            source.facet().pluginId().value().equals(ref.pluginId())
                && source.facet().facet().facetId().value().equals(ref.facetId())).toList();
        if (matches.size() > 1) throw new IllegalArgumentException(
            "Java definition identifies multiple selected facets: " + ref);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private BuiltInBinding builtIn(PluginDefinitionRef ref, RuntimeTargetSlice target) {
        var candidates = builtIns.stream().filter(value -> value.metadata().pluginId().value()
            .equals(ref.pluginId())).filter(value -> value.metadata().facets().stream()
            .anyMatch(facet -> facet.facetId().value().equals(ref.facetId()))).toList();
        if (candidates.size() != 1 || target.builtInPackages().stream().noneMatch(metadata ->
            metadata.pluginId().equals(candidates.getFirst().metadata().pluginId())
                && metadata.packageDigest().equals(candidates.getFirst().metadata().packageDigest()))) {
            throw new IllegalArgumentException("Java definition does not identify one selected built-in facet: " + ref);
        }
        var owner = candidates.getFirst();
        var facet = owner.metadata().facets().stream().filter(value -> value.facetId().value()
            .equals(ref.facetId())).findFirst().orElseThrow();
        return new BuiltInBinding(owner, facet, owner.definition(facet.facetId(), ref.definitionId()));
    }

    private ExecutionUnitPlan builtInPlan(ExecutionUnitKey key, PluginDefinitionRef ref,
                                          RuntimeTargetSlice target,
                                          List<ExecutionUnitKey> dependencies) {
        var binding = builtIn(ref, target);
        return ExecutionUnitPlan.builder(key, id(), binding.facet.executionTarget())
            .artifactId(binding.owner.metadata().artifactId(binding.facet.facetId()))
            .provenance(binding.owner.metadata().pluginId().value(), binding.facet.facetId().value(),
                binding.owner.metadata().packageDigest())
            .dependencies(dependencies).build();
    }

    private static void releasePrepared(Map<ExecutionUnitKey, PreparedUnit> values) {
        RuntimeException failure = null;
        for (var unit : new ArrayList<>(values.values()).reversed()) {
            try { unit.lease.close(); } catch (RuntimeException cleanup) {
                if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) throw failure;
    }

    private static String requiredOperation(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("lifecycle operation id must not be blank");
        return value;
    }

    private static void requireDeadline(Instant deadline) { Objects.requireNonNull(deadline, "deadline"); }

    private record PreparedUnit(ExecutionUnitPlan plan, PluginDefinitionRef definition,
                                com.sstlfsj.fibra.config.PublicationRequirement publication,
                                PluginDefinition.Prepared<?> binding,
                                Map<String, com.sstlfsj.fibra.config.DesiredInputGraph.EffectivePolicyValue> realms,
                                Map<String, com.sstlfsj.fibra.config.DesiredInputGraph.EffectivePolicyValue> intercepts,
                                PrivateLease lease) {
        private PreparedUnit {
            realms = Map.copyOf(realms);
            intercepts = Map.copyOf(intercepts);
        }
    }

    private record BuiltInBinding(JavaBuiltInPackage owner,
                                  com.sstlfsj.fibra.engine.BuiltInFacet facet,
                                  JavaDefinitionEntry<?> definition) { }

    private record WiringKey(String packageRevision, String facetId, String payloadDigest,
                             List<String> dependencies, String contractIdentity) {
        private WiringKey { dependencies = List.copyOf(dependencies); }
        private String identity() { return packageRevision + ':' + facetId + ':' + payloadDigest + ':' + dependencies + ':' + contractIdentity; }
    }

    private static final class StaticWiring {
        private final WiringKey key;
        private final ManagedFacet facet;
        private final PluginClassLoader loader;
        private final JavaDefinitionEntry<?> definition;
        private int references;
        private StaticWiring(WiringKey key, ManagedFacet facet, PluginClassLoader loader,
                             JavaDefinitionEntry<?> definition) {
            this.key = key; this.facet = facet; this.loader = loader; this.definition = definition;
        }
    }

    private interface PrivateLease extends AutoCloseable {
        @Override void close();
    }

    private enum NoopLease implements PrivateLease {
        INSTANCE;
        @Override public void close() { }
    }

    private final class StaticLease implements PrivateLease {
        private final StaticWiring wiring;
        private final List<StaticLease> dependencies;
        private boolean wiringReleased;
        private boolean closed;
        private StaticLease(StaticWiring wiring, List<StaticLease> dependencies) {
            this.wiring = wiring; this.dependencies = new ArrayList<>(dependencies);
        }
        @Override public synchronized void close() {
            if (closed) return;
            if (!wiringReleased) {
                release(wiring);
                wiringReleased = true;
            }
            RuntimeException failure = null;
            for (var index = dependencies.size() - 1; index >= 0; index--) {
                var dependency = dependencies.get(index);
                if (dependency == null) continue;
                try {
                    dependency.close();
                    dependencies.set(index, null);
                } catch (RuntimeException cleanup) {
                    if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
                }
            }
            if (failure != null) throw failure;
            closed = true;
        }
    }

    interface LoaderCloser { void close(PluginClassLoader loader) throws IOException; }
}
