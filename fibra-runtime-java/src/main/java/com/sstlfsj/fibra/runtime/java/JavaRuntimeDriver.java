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
import com.sstlfsj.fibra.engine.execution.CompiledRuntimeSlice;
import com.sstlfsj.fibra.engine.execution.DefinitionBindingPlan;
import com.sstlfsj.fibra.engine.observation.ExecutionObservation;
import com.sstlfsj.fibra.engine.execution.ExecutionUnitKey;
import com.sstlfsj.fibra.engine.execution.ExecutionUnitPlan;
import com.sstlfsj.fibra.engine.deployment.PluginFacetSource;
import com.sstlfsj.fibra.engine.runtime.PreparedRuntimeGeneration;
import com.sstlfsj.fibra.engine.runtime.RuntimeArtifactInspection;
import com.sstlfsj.fibra.engine.runtime.RuntimeCandidate;
import com.sstlfsj.fibra.engine.runtime.RuntimeDriver;
import com.sstlfsj.fibra.engine.runtime.RuntimeDefinitionQuery;
import com.sstlfsj.fibra.engine.observation.RuntimeDriverSnapshot;
import com.sstlfsj.fibra.engine.runtime.RuntimeHostServices;
import com.sstlfsj.fibra.engine.execution.RuntimePlan;
import com.sstlfsj.fibra.engine.runtime.RuntimeTargetSlice;
import com.sstlfsj.fibra.engine.execution.RuntimeUnitFence;
import com.sstlfsj.fibra.engine.runtime.RuntimeUnitDisableRequest;
import com.sstlfsj.fibra.engine.runtime.RuntimeUnitGeneration;
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
final class JavaRuntimeDriver implements RuntimeDriver {
    // Host sharing preferences; platform visibility is resolved independently by PluginClassLoader.
    private static final List<String> DEFAULT_PARENT_PACKAGES = JavaApiContracts.defaultParentPackages();

    private final RuntimeHostServices services;
    private final List<JavaBuiltInPackage> builtIns;
    private final ClassLoader parent;
    private final List<String> parentPackages;
    private final LoaderCloser closer;
    private final JavaApiContracts apiContracts;
    private final JavaFacetDescriptorReader descriptors = new JavaFacetDescriptorReader();
    private final JavaFacetDescriptorReader.ResourceAccess descriptorAccess;
    private final Set<DescriptorRead> descriptorReads = new LinkedHashSet<>();
    private final Map<WiringKey, StaticWiring> wiringPool = new LinkedHashMap<>();
    private final Set<AcquisitionOwner> acquisitions = new LinkedHashSet<>();
    private final Map<ExecutionUnitKey, RuntimeUnitGeneration> publishedUnits = new ConcurrentHashMap<>();
    private Mono<Void> close;

    JavaRuntimeDriver(RuntimeHostServices services, List<JavaBuiltInPackage> builtIns) {
        this(services, builtIns, new JavaApiContracts(List.of()));
    }

    JavaRuntimeDriver(RuntimeHostServices services, List<JavaBuiltInPackage> builtIns,
                      JavaApiContracts apiContracts) {
        this(services, builtIns, JavaRuntimeDriver.class.getClassLoader(), DEFAULT_PARENT_PACKAGES,
            PluginClassLoader::close, new JavaFacetDescriptorReader.ResourceAccess(), apiContracts);
    }

    JavaRuntimeDriver(RuntimeHostServices services, List<JavaBuiltInPackage> builtIns,
                      ClassLoader parent,
                      List<String> parentPackages, LoaderCloser closer) {
        this(services, builtIns, parent, parentPackages, closer,
            new JavaFacetDescriptorReader.ResourceAccess());
    }

    JavaRuntimeDriver(RuntimeHostServices services, List<JavaBuiltInPackage> builtIns,
                      ClassLoader parent, List<String> parentPackages, LoaderCloser closer,
                      JavaFacetDescriptorReader.ResourceAccess descriptorAccess) {
        this(services, builtIns, parent, parentPackages, closer, descriptorAccess,
            new JavaApiContracts(List.of()));
    }

    private JavaRuntimeDriver(RuntimeHostServices services, List<JavaBuiltInPackage> builtIns,
                               ClassLoader parent, List<String> parentPackages, LoaderCloser closer,
                               JavaFacetDescriptorReader.ResourceAccess descriptorAccess,
                               JavaApiContracts apiContracts) {
        this.services = Objects.requireNonNull(services, "services");
        this.builtIns = List.copyOf(Objects.requireNonNull(builtIns, "builtIns"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.parentPackages = List.copyOf(parentPackages);
        this.closer = Objects.requireNonNull(closer, "closer");
        this.descriptorAccess = Objects.requireNonNull(descriptorAccess, "descriptorAccess");
        this.apiContracts = Objects.requireNonNull(apiContracts, "apiContracts");
    }

    @Override
    public synchronized RuntimeDefinitionQuery createDefinitionQuery(ManagedFacet facet) {
        return new DefinitionQuery(createDescriptorRead(facet));
    }

    private synchronized DescriptorRead createDescriptorRead(ManagedFacet facet) {
        Objects.requireNonNull(facet, "facet");
        if (!id().equals(facet.facet().runtimeId())) {
            throw new IllegalArgumentException("facet runtime is not Java");
        }
        requireOpen();
        var read = new DescriptorRead(facet);
        descriptorReads.add(read);
        return read;
    }

    private JavaFacetDescriptor readDescriptor(ManagedFacet facet, AcquisitionNode node) {
        var read = createDescriptorRead(facet);
        if (node != null) node.descriptorRead = read;
        boolean fatalRead = false;
        try {
            return read.read();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            fatalRead = true;
            throw fatal;
        } finally {
            if (!fatalRead) {
                read.close();
                if (node != null) node.descriptorRead = null;
            }
        }
    }

    /** The file owner is read by either the synchronous path or one query adapter. */
    private final class DescriptorRead {
        private final ManagedFacet facet;
        private final JavaFacetDescriptorReader.Resources resources =
            new JavaFacetDescriptorReader.Resources(descriptorAccess);
        private boolean closeAttempted;
        private Throwable closeFailure;

        private DescriptorRead(ManagedFacet facet) { this.facet = facet; }

        private JavaFacetDescriptor read() {
            try {
                return descriptors.read(facet.facet(), resources);
            } catch (Throwable failure) {
                rethrowFatal(failure);
                // Never retain a nonfatal original exception graph in a cached query result.
                throw new JavaRuntimeException(JavaRuntimePhase.MANIFEST, facet.artifactId(),
                    "cannot read Java definition declaration: " + failure.getClass().getName()
                        + ": " + failure.getMessage(), null);
            }
        }

        private synchronized void close() {
            if (!closeAttempted) {
                closeAttempted = true;
                try {
                    resources.close();
                    synchronized (JavaRuntimeDriver.this) {
                        descriptorReads.remove(this);
                    }
                } catch (RuntimeException | Error failure) {
                    closeFailure = failure;
                    throw failure;
                }
            }
            if (closeFailure instanceof RuntimeException failure) throw failure;
            if (closeFailure instanceof Error failure) throw failure;
        }
    }

    /** Observes one owner asynchronously; it never owns or closes file handles itself. */
    private final class DefinitionQuery implements RuntimeDefinitionQuery {
        private final DescriptorRead owner;
        private final Mono<JavaFacetDescriptor> descriptor;
        private boolean started;
        private boolean closing;
        private Mono<Void> closeResult;

        private DefinitionQuery(DescriptorRead owner) {
            this.owner = owner;
            descriptor = Mono.defer(() -> {
                synchronized (this) {
                    if (closing) return Mono.error(new IllegalStateException("Java definition query is closed"));
                    started = true;
                }
                return Mono.fromCallable(owner::read)
                    .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
            }).cache();
        }

        @Override public Mono<List<String>> readAsync() {
            return descriptor.map(value -> value.definitionId().map(List::of).orElseGet(List::of));
        }

        @Override public synchronized Mono<Void> closeAsync() {
            if (closeResult == null) {
                closeResult = Mono.defer(() -> {
                    final boolean waitForRead;
                    synchronized (this) {
                        closing = true;
                        waitForRead = started;
                    }
                    var readTerminal = waitForRead
                        ? descriptor.onErrorResume(failure -> Mono.empty()).then() : Mono.<Void>empty();
                    return readTerminal.then(Mono.fromRunnable(owner::close));
                }).then().cache();
            }
            return closeResult;
        }
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
                    if (!descriptorReads.isEmpty()) {
                        throw new IllegalStateException("Java runtime driver still has descriptor reads");
                    }
                    if (!acquisitions.isEmpty()) {
                        throw new IllegalStateException("Java runtime driver still has acquisition owners");
                    }
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
        readDescriptor(facet, null);
        return new RuntimeArtifactInspection(id(), facet.artifactId());
    }

    private synchronized void requireOpen() {
        if (close != null) throw new IllegalStateException("Java runtime driver is closed");
    }

    private synchronized StaticLease acquire(PluginFacetSource source,
                                             Map<ArtifactId, PluginFacetSource> sources,
                                             AcquisitionNode node) {
        requireOpen();
        return acquire(source, sources, new LinkedHashSet<>(), node);
    }

    private StaticLease acquire(PluginFacetSource source,
                                Map<ArtifactId, PluginFacetSource> sources,
                                Set<ArtifactId> visiting, AcquisitionNode node) {
        var artifactId = source.facet().artifactId();
        if (!visiting.add(artifactId)) {
            throw new IllegalArgumentException("Java static dependency cycle at " + artifactId);
        }
        var dependencyLeases = new ArrayList<StaticLease>();
        try {
            for (var dependency : source.dependencies()) {
                var local = sources.get(dependency.artifactId());
                if (local != null) {
                    var child = new AcquisitionNode(local.facet().artifactId());
                    node.children.add(child);
                    dependencyLeases.add(acquire(local, sources, visiting, child));
                }
            }
            var descriptor = readDescriptor(source.facet(), node);
            var importedClasses = apiContracts.bind(artifactId, descriptor.hostApis());
            JavaClassIndex.validate(artifactId, List.of(source.facet().facet().payload()), parent,
                parentPackages, apiContracts.names());
            var dependencyKeys = dependencyLeases.stream().map(lease -> lease.wiring.key.identity())
                .sorted().toList();
            var key = new WiringKey(source.facet().packageRevision(),
                source.facet().facet().facetId().value(), source.facet().facet().payloadDigest(),
                dependencyKeys, apiContracts.identity());
            var wiring = wiringPool.get(key);
            var publish = wiring == null;
            if (wiring == null) {
                wiring = createWiring(key, source.facet(), descriptor,
                    dependencyLeases.stream().map(lease -> lease.wiring).toList(), importedClasses, node);
            }
            if (descriptor.definitionId().isPresent() != (wiring.definition != null)) {
                throw new JavaRuntimeException(JavaRuntimePhase.LOAD, artifactId,
                    "Java definition presence does not match declared definitionId", null);
            }
            if (wiring.definition != null
                && !wiring.definition.definition().name().equals(descriptor.definitionId().orElseThrow())) {
                throw new JavaRuntimeException(JavaRuntimePhase.LOAD, artifactId,
                    "Java metadata definition name does not match declared definitionId", null);
            }
            wiring.references++;
            node.lease = new StaticLease(wiring, List.copyOf(dependencyLeases));
            if (publish) wiringPool.put(key, wiring);
            node.loader = null;
            node.children.clear();
            return node.lease;
        } catch (RuntimeException | Error failure) {
            rethrowFatal(failure);
            throw acquisitionFailure(artifactId, failure);
        } finally {
            visiting.remove(artifactId);
        }
    }

    private StaticWiring createWiring(WiringKey key, ManagedFacet facet,
                                      JavaFacetDescriptor descriptor,
                                      List<StaticWiring> dependencies, Map<String, Class<?>> importedClasses,
                                      AcquisitionNode node) {
        try {
            var loader = new PluginClassLoader(List.of(facet.facet().payload().toUri().toURL()), parent,
                parentPackages, importedClasses, apiContracts.names());
            node.loader = loader;
            loader.dependencies(dependencies.stream().map(value -> value.loader).toList());
            JavaClassSpace.validate(facet.artifactId(), descriptor, loader);
            var entry = JavaClassSpace.entry(facet.artifactId(), descriptor, loader);
            return new StaticWiring(key, facet, loader, entry);
        } catch (MalformedURLException failure) {
            throw new JavaRuntimeException(JavaRuntimePhase.LOAD, facet.artifactId(),
                "cannot create Java facet loader", failure);
        }
    }

    private synchronized void release(StaticWiring wiring) {
        if (wiring.references < 1) throw new IllegalStateException("Java class space lease underflow");
        if (--wiring.references == 0) {
            try {
                closer.close(wiring.loader);
                wiringPool.remove(wiring.key, wiring);
            } catch (IOException | RuntimeException | Error failure) {
                wiring.references++;
                rethrowFatal(failure);
                throw new JavaRuntimeException(JavaRuntimePhase.CLOSE, wiring.facet.artifactId(),
                    "cannot close Java class space", failure);
            }
        }
    }

    private static JavaRuntimeException acquisitionFailure(ArtifactId id, Throwable failure) {
        return failure instanceof JavaRuntimeException javaFailure ? javaFailure
            : new JavaRuntimeException(JavaRuntimePhase.LOAD, id,
                "cannot prepare Java class space", failure);
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
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
        private final AcquisitionOwner acquisition = new AcquisitionOwner();
        private final Mono<Void> preparation = Mono.<Void>fromRunnable(this::prepare).cache();
        private boolean closed;
        private boolean started;
        private boolean sealed;
        private RuntimePlan plan;
        private Map<ExecutionUnitKey, PreparedUnit> prepared = Map.of();
        private Mono<Void> close;
        private boolean cleanupAttempted;
        private RuntimeException cleanupFailure;

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
            var generation = new Generation(compiled, prepared,
                target.target().targetRevision(),
                target.capabilities().availableNames());
            sealed = true;
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
                        if (!sealed) cleanup();
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
            synchronized (this) { prepared = values; }
            ArtifactId artifactId = null;
            try {
                for (var entryId : target.affectedEntryIds().stream().sorted().toList()) {
                    var resolved = target.desired().entries().get(entryId);
                    if (!(resolved.input() instanceof DesiredInputEntry entry)
                        || !resolved.effective().enabled()) {
                        throw new IllegalArgumentException("affected Java entry is not enabled: " + entryId);
                    }
                    var definitionRef = entry.definitionRef();
                    var source = dynamicSource(definitionRef, sources);
                    artifactId = source == null ? null : source.facet().artifactId();
                    var node = source == null ? null : acquisition.root(artifactId);
                    var lease = source == null ? NoopLease.INSTANCE : acquire(source, sources, node);
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
                    if (node != null) acquisition.transfer(node);
                }
                var definitions = values.entrySet().stream().map(value ->
                    DefinitionBindingPlan.builder(value.getValue().definition, value.getKey().value())
                        .unitKey(value.getKey()).publicationRequirement(value.getValue().publication).build()).toList();
                synchronized (this) {
                    if (closed) throw new IllegalStateException("Java runtime candidate is closed");
                    plan = RuntimePlan.of(id(), values.values().stream().map(PreparedUnit::plan).toList(), definitions);
                }
            } catch (RuntimeException | Error failure) {
                rethrowFatal(failure);
                var primary = acquisitionFailure(artifactId, failure);
                try { cleanup(); } catch (RuntimeException cleanup) {
                    if (cleanup != primary) primary.addSuppressed(cleanup);
                }
                throw primary;
            }
        }

        private synchronized void cleanup() {
            if (!cleanupAttempted) {
                cleanupAttempted = true;
                try { acquisition.close(); } catch (RuntimeException failure) {
                    cleanupFailure = failure;
                }
                try { releasePrepared(prepared); } catch (RuntimeException failure) {
                    if (cleanupFailure == null) cleanupFailure = failure;
                    else if (cleanupFailure != failure) cleanupFailure.addSuppressed(failure);
                }
            }
            if (cleanupFailure != null) throw cleanupFailure;
        }

        private List<ExecutionUnitKey> requireDependencies(ExecutionUnitKey key) {
            var dependencies = target.unitDependencies().get(key);
            if (dependencies == null) throw new IllegalArgumentException("missing Java unit dependencies for " + key);
            return dependencies;
        }
    }

    /** Owns partial acquisitions until the candidate has installed a complete prepared unit. */
    private final class AcquisitionOwner {
        private final List<AcquisitionNode> roots = new ArrayList<>();
        private boolean closed;
        private RuntimeException failure;

        private AcquisitionNode root(ArtifactId id) {
            synchronized (JavaRuntimeDriver.this) {
                requireOpen();
                var node = new AcquisitionNode(id);
                roots.add(node);
                acquisitions.add(this);
                return node;
            }
        }

        private void transfer(AcquisitionNode node) {
            synchronized (JavaRuntimeDriver.this) {
                roots.remove(node);
                if (roots.isEmpty()) acquisitions.remove(this);
            }
        }

        private void close() {
            synchronized (JavaRuntimeDriver.this) {
                if (!closed) {
                    closed = true;
                    for (var index = roots.size() - 1; index >= 0; index--) {
                        try {
                            roots.get(index).close();
                            roots.remove(index);
                        } catch (RuntimeException cleanup) {
                            if (failure == null) failure = cleanup;
                            else if (failure != cleanup) failure.addSuppressed(cleanup);
                        }
                    }
                    if (failure == null) {
                        acquisitions.remove(this);
                    }
                }
                if (failure != null) throw failure;
            }
        }
    }

    private final class AcquisitionNode {
        private final ArtifactId artifactId;
        private final List<AcquisitionNode> children = new ArrayList<>();
        private PluginClassLoader loader;
        private StaticLease lease;
        private DescriptorRead descriptorRead;

        private AcquisitionNode(ArtifactId artifactId) { this.artifactId = artifactId; }

        private void close() {
            if (descriptorRead != null) {
                descriptorRead.close();
                descriptorRead = null;
            }
            if (lease != null) {
                lease.close();
                lease = null;
                return;
            }
            if (loader != null) {
                try {
                    closer.close(loader);
                    loader = null;
                } catch (IOException | RuntimeException | Error cleanup) {
                    rethrowFatal(cleanup);
                    throw new JavaRuntimeException(JavaRuntimePhase.CLOSE, artifactId,
                        "cannot close partially acquired Java class space", cleanup);
                }
            }
            RuntimeException failure = null;
            for (var index = children.size() - 1; index >= 0; index--) {
                try {
                    children.get(index).close();
                    children.remove(index);
                } catch (RuntimeException cleanup) {
                    if (failure == null) failure = cleanup;
                    else if (failure != cleanup) failure.addSuppressed(cleanup);
                }
            }
            if (failure != null) throw failure;
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

        @Override
        public RuntimeUnitFence fence() {
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
        for (var entry : new ArrayList<>(values.entrySet()).reversed()) {
            try {
                entry.getValue().lease.close();
                values.remove(entry.getKey());
            } catch (RuntimeException cleanup) {
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
                                  com.sstlfsj.fibra.engine.deployment.BuiltInFacet facet,
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
        private StaticWiring wiring;
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
                wiring = null;
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
