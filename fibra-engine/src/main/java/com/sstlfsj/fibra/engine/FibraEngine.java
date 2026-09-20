package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.Scope;
import com.sstlfsj.fibra.ScopeView;
import com.sstlfsj.fibra.artifact.*;
import com.sstlfsj.fibra.bridge.*;
import com.sstlfsj.fibra.config.*;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import com.sstlfsj.fibra.runtime.RuntimeDomain;
import com.sstlfsj.fibra.value.LiteralValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** 唯一 RuntimeDriver owner、持久目标和全局 unit 生命周期协调器。 */
public final class FibraEngine implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(FibraEngine.class);
    private final PluginPackageStore packageStore;
    private final DeploymentTargetStore targetStore;
    private final RuntimeProviderRegistry providers;
    private final HostServiceRegistry hostServices;
    private final ContributionKindRegistry contributionKinds;
    private final RemoteContributionInvoker remoteContributions;
    private final HostTerminationPort terminationPort;
    private final Supplier<HostCapabilitySnapshot> capabilitySource;
    private final Duration lifecycleTimeout;
    private final FibraRuntime runtime;
    private final RuntimeDomain domain;
    private final ContributionDirectory directory;
    private final EngineCommandLoop loop;
    private final ExecutorService notifications;
    private final Map<RuntimeId, RuntimeDriver> drivers;
    private final DeploymentPlanner planner = new DeploymentPlanner();
    private final String hostInstanceId = UUID.randomUUID().toString();
    private final AtomicLong identities = new AtomicLong();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicReference<PublishedState> publication = new AtomicReference<>();
    private final Sinks.Many<PublishedView> views = Sinks.many().multicast().directBestEffort();
    private volatile Mono<PublishedView> start;
    private final Mono<Void> close;
    private final Disposable directoryObservation;
    private volatile boolean admissionOpen = true;
    private boolean mutationGate = true;
    private EngineState state = EngineState.NEW;
    private DurableTargetState durableState = DurableTargetState.ABSENT;
    private DeploymentTarget durableTarget;
    private DurableTargetToken durableToken;
    private CandidateAttempt candidate;
    private CurrentAttempt current;
    private RetirementBatch retirement;
    private TargetConvergence targetConvergence = TargetConvergence.ABSENT;
    private EngineOperation operation;
    private HostTerminationRequest terminationRequest;
    private final List<String> cleanupFailures = new ArrayList<>();
    private final Map<RuntimeUnitGeneration, ExecutionObservation> lastObservations = new IdentityHashMap<>();
    private FailureFact failure;

    private final PublishedRuntime published = new PublishedRuntime() {
        public PublishedView current() { return publication.get().view(); }
        public Flux<PublishedView> views() {
            return views.asFlux().onBackpressureLatest().publishOn(Schedulers.boundedElastic(), 1)
                .onBackpressureLatest();
        }
        public <D, I, O> Mono<O> invoke(String revision, long registration,
                                       ContributionKind<D, I, O> kind, ContributionId id, I input) {
            return invokePublished(revision, registration, kind, id, input)
                .publishOn(Schedulers.boundedElastic());
        }
    };

    private FibraEngine(Builder builder) {
        packageStore = builder.packageStore;
        targetStore = builder.targetStore;
        FibraRuntime acquiredRuntime = null;
        RuntimeDomain acquiredDomain = null;
        ContributionDirectory acquiredDirectory = null;
        EngineCommandLoop acquiredLoop = null;
        ExecutorService acquiredNotifications = null;
        Map<RuntimeId, RuntimeDriver> acquiredDrivers = null;
        Disposable acquiredDirectoryObservation = null;
        try {
            providers = RuntimeProviderRegistry.of(builder.providers);
            hostServices = builder.hostServices;
            contributionKinds = builder.contributionKinds;
            remoteContributions = new RemoteContributionInvoker(contributionKinds,
                published);
            terminationPort = Objects.requireNonNull(builder.terminationPort,
                "hostTerminationPort");
            capabilitySource = builder.capabilities;
            lifecycleTimeout = builder.lifecycleTimeout;
            var initialCapabilities = Objects.requireNonNull(capabilitySource.get(),
                "capability snapshot");
            inputFingerprint(initialCapabilities, captureBuiltIns());
            runtime = acquiredRuntime = FibraRuntime.create();
            domain = acquiredDomain = runtime.openDomain("fibra-engine");
            directory = acquiredDirectory = new ContributionDirectory();
            loop = acquiredLoop = new EngineCommandLoop();
            notifications = acquiredNotifications = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("fibra-termination-", 0).factory());
            drivers = acquiredDrivers = providers.createDrivers(new HostServices());
            publish();
            directoryObservation = acquiredDirectoryObservation = directory.views().subscribe(
                ignored -> loop.observe(this::publish),
                error -> loop.submit(() -> Mono.fromRunnable(() ->
                    failStop(engineFailure("CONTRIBUTION_DIRECTORY_FAILED",
                        error, FailureStage.OBSERVING)))).subscribe());
            start = Mono.defer(() -> loop.submit(this::bootstrap))
                .doOnSuccess(view -> {
                    // 首次等待者接收本次精确结果；后续订阅读当前视图且不长期保留启动视图。
                    start = Mono.fromSupplier(published::current);
                })
                .doOnError(error -> {
                    // 后续订阅只保留失败事实，避免缓存携带 PublishedView 或插件异常图。
                    var detail = error.toString();
                    if (error instanceof EngineChangeException change) {
                        start = Mono.defer(() -> Mono.error(new EngineChangeException(
                            published.current(), change.targetSaveState(),
                            new IllegalStateException(detail))));
                    } else {
                        start = Mono.defer(() -> Mono.error(new IllegalStateException(detail)));
                    }
                }).cache();
            close = Mono.defer(() -> {
                closing.set(true);
                admissionOpen = false;
                return loop.quiesce().then(loop.call(this::shutdown));
            }).doFinally(ignored -> {
                directoryObservation.dispose();
                notifications.shutdownNow();
            }).then(Mono.defer(loop::closeAsync))
                .onErrorResume(error -> loop.closeAsync().then(Mono.error(error))).cache();
        } catch (RuntimeException | Error constructionFailure) {
            cleanupConstructionFailure(constructionFailure, acquiredDirectoryObservation,
                acquiredDrivers, acquiredDirectory, acquiredDomain, acquiredRuntime,
                acquiredLoop, acquiredNotifications, targetStore, packageStore);
            throw constructionFailure;
        }
    }

    private static void cleanupConstructionFailure(
        Throwable constructionFailure,
        Disposable directoryObservation,
        Map<RuntimeId, RuntimeDriver> drivers,
        ContributionDirectory directory,
        RuntimeDomain domain,
        FibraRuntime runtime,
        EngineCommandLoop loop,
        ExecutorService notifications,
        DeploymentTargetStore targetStore,
        PluginPackageStore packageStore
    ) {
        if (directoryObservation != null) {
            suppressConstructionCleanupFailure(constructionFailure,
                directoryObservation::dispose);
        }
        if (drivers != null) {
            var reversedDrivers = new ArrayList<>(drivers.values());
            Collections.reverse(reversedDrivers);
            reversedDrivers.forEach(driver -> suppressConstructionCleanupFailure(
                constructionFailure, () -> driver.closeAsync().block()));
        }
        if (directory != null) {
            suppressConstructionCleanupFailure(constructionFailure,
                () -> directory.closeAsync().block());
        }
        if (domain != null) {
            suppressConstructionCleanupFailure(constructionFailure,
                () -> domain.closeAsync().block());
        }
        if (runtime != null) {
            suppressConstructionCleanupFailure(constructionFailure,
                () -> runtime.closeAsync().block());
        }
        if (loop != null) {
            suppressConstructionCleanupFailure(constructionFailure,
                () -> loop.closeAsync().block());
        }
        if (notifications != null) {
            suppressConstructionCleanupFailure(constructionFailure,
                notifications::shutdownNow);
        }
        suppressConstructionCleanupFailure(constructionFailure, targetStore::close);
        suppressConstructionCleanupFailure(constructionFailure, packageStore::close);
    }

    private static void suppressConstructionCleanupFailure(
        Throwable constructionFailure,
        Runnable cleanup
    ) {
        try {
            cleanup.run();
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != constructionFailure) {
                constructionFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    public static Builder builder(PluginPackageStore packages, DeploymentTargetStore targets) {
        return new Builder(packages, targets);
    }
    public PublishedRuntime published() { return published; }
    public EngineSnapshot snapshot() { return published.current().engine(); }
    public Mono<PublishedView> startAsync() { return Mono.defer(() -> start); }
    public Mono<EngineCommandResult> submit(EngineCommand command) {
        Objects.requireNonNull(command, "command");
        return Mono.defer(() -> loop.submit(() -> {
            ensureMutable();
            return switch (command) {
                case ApplyDeployment apply -> apply(apply);
                case ReconcileCurrent ignored -> retryCurrent();
            };
        })).map(view -> new EngineCommandResult(view, List.of()));
    }
    public Mono<Void> closeAsync() { return close; }
    @Override public void close() { closeAsync().block(); }

    private Mono<PublishedView> bootstrap() {
        return Mono.defer(() -> {
            if (state != EngineState.NEW) return Mono.just(published.current());
            installHostServices();
            var stored = targetStore.load();
            state = EngineState.RUNNING;
            if (stored.isEmpty()) {
                beginOperation(EngineOperationKind.BOOTSTRAP, 0,
                    TargetSaveState.NOT_APPLICABLE);
                targetConvergence = TargetConvergence.ABSENT;
                completeOperation();
                publish();
                return Mono.just(published.current());
            }
            durableTarget = stored.get().target();
            durableToken = stored.get().token();
            verifyToken(durableTarget, durableToken);
            durableState = DurableTargetState.PRESENT;
            targetConvergence = TargetConvergence.CONVERGING;
            return deploy(durableTarget, false, true, Set.of(),
                EngineOperationKind.BOOTSTRAP);
        }).onErrorResume(error -> {
            if (state == EngineState.FAIL_STOP) return Mono.error(error);
            targetConvergence = durableTarget == null
                ? TargetConvergence.ABSENT : TargetConvergence.BLOCKED;
            publish();
            return Mono.just(published.current());
        });
    }

    private void installHostServices() {
        var context = domain.rootScope().context();
        hostServices.freeze().forEach(binding -> provideHostBinding(context,
            binding));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void provideHostBinding(com.sstlfsj.fibra.Context context,
                                           HostServiceRegistry.Binding binding) {
        context.services().provide(binding.key(), binding.value());
    }

    private Mono<PublishedView> apply(ApplyDeployment command) {
        long revision = durableTarget == null ? 0 : durableTarget.targetRevision();
        if (command.expectedRevision() != revision) return Mono.error(new IllegalStateException(
            "deployment target revision conflict: expected " + command.expectedRevision() + ", actual " + revision));
        var target = DeploymentTarget.of(Math.incrementExact(revision), command.selections(),
            command.graph(), command.configContext());
        boolean same = durableTarget != null && target.targetDigest().equals(durableTarget.targetDigest());
        return deploy(same ? durableTarget : target, !same, false, Set.of(),
            EngineOperationKind.APPLY);
    }

    private Mono<PublishedView> retryCurrent() {
        if (durableTarget == null) return Mono.just(published.current());
        var failed = new LinkedHashSet<ExecutionUnitKey>();
        if (current != null) current.units.forEach((key, unit) -> {
            if (observeCurrent(key, unit).aggregateState()
                == ExecutionObservation.State.FAILED) failed.add(key);
        });
        if (current != null && failed.isEmpty()) {
            if (currentObservations().values().stream().noneMatch(observation ->
                observation.aggregateState()
                    == ExecutionObservation.State.PENDING)) {
                return Mono.just(published.current());
            }
            beginOperation(EngineOperationKind.RECONCILE,
                durableTarget.targetRevision(), TargetSaveState.NOT_APPLICABLE);
            return reconcile(current.units.keySet())
                .then(loop.call(this::finishCurrentOperation));
        }
        return deploy(durableTarget, false, current == null, failed,
            EngineOperationKind.RECONCILE);
    }

    private Mono<PublishedView> deploy(DeploymentTarget target, boolean save,
                                       boolean forceAll, Set<ExecutionUnitKey> forced,
                                       EngineOperationKind operationKind) {
        return Mono.defer(() -> {
            ensureMutable();
            beginOperation(operationKind, target.targetRevision(), save
                ? TargetSaveState.NOT_SAVED : TargetSaveState.NOT_APPLICABLE);
            var nextCapabilities = Objects.requireNonNull(capabilitySource.get(), "capability snapshot");
            var builtIns = captureBuiltIns();
            var inputsIdentity = inputFingerprint(nextCapabilities, builtIns);
            var fingerprint = digest(target.targetDigest() + ":" + inputsIdentity);
            if (current != null) refreshCurrentObservations();
            if (!save && current != null && current.compiled.compiledFingerprint().equals(fingerprint)
                && forced.isEmpty() && targetSatisfied(currentObservations())) {
                completeOperation();
                targetConvergence = TargetConvergence.SATISFIED;
                publish();
                return Mono.just(published.current());
            }
            failure = null;
            var packages = target.selections().values().stream()
                .filter(selection -> builtIns.stream().noneMatch(value -> value.pluginId().equals(selection.pluginId())))
                .map(selection -> packageStore.find(selection.pluginId(), selection.packageRevision())
                    .orElseThrow(() -> new IllegalArgumentException("selected package revision is missing: " + selection)))
                .map(PluginPackageRecord::managedPackage).toList();
            var input = planner.inputs(target, packages, builtIns,
                nextCapabilities);
            input.facets().facets().values().forEach(facet -> requireDriver(facet.facet().facet().runtimeId()));
            input.facets().builtInFacets().values().forEach(value -> requireDriver(value.facet().runtimeId()));
            boolean all = forceAll || current == null || !current.inputsIdentity.equals(inputsIdentity)
                || (!save && forced.isEmpty());
            var affected = planner.affected(input, current == null ? null : current.compiled, forced, all);
            var retained = new LinkedHashMap<ExecutionUnitKey, RuntimeUnitGeneration>();
            if (current != null) current.units.forEach((key, unit) -> {
                if (!affected.contains(key) && input.units().containsKey(key)) retained.put(key, unit);
            });
            candidate = new CandidateAttempt(identity("attempt"),
                new DeploymentCandidate(target));
            operation.attemptId = candidate.id;
            var staged = candidate.deployment;
            // inert create 与登记发生在同一 command-lane 调用栈；其后才允许订阅 prepare。
            for (var entry : drivers.entrySet()) {
                var id = entry.getKey();
                var keys = input.units().values().stream()
                    .filter(unit -> unit.runtimeId().equals(id) && !retained.containsKey(unit.key()))
                    .map(ExecutionUnitPlan::key).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                if (keys.isEmpty()) continue;
                var dependencies = new LinkedHashMap<ExecutionUnitKey, List<ExecutionUnitKey>>();
                keys.forEach(key -> dependencies.put(key, input.units().get(key).dependencies()));
                var slice = RuntimeTargetSlice.builder(id, target).desired(input.desired())
                    .capabilities(nextCapabilities)
                    .affectedEntryIds(keys.stream().map(ExecutionUnitKey::value).collect(java.util.stream.Collectors.toSet()))
                    .unitDependencies(dependencies)
                    .facets(input.facets().facets().values().stream()
                        .filter(facet -> facet.facet().facet().runtimeId().equals(id))
                        .map(facet -> new PluginFacetSource(facet.facet(), facet.dependencies())).toList())
                    .builtInPackages(input.facets().builtInFacets().values().stream()
                        .filter(value -> value.facet().runtimeId().equals(id))
                        .map(DeploymentTargetCompiler.CompiledBuiltInFacet::pluginPackage).distinct().toList()).build();
                staged.register(id, entry.getValue().createCandidate(slice));
            }
            transitionCandidate(CandidatePhase.PREPARING,
                EngineOperationStage.PREPARING);
            publish();
            return Flux.fromIterable(staged.candidates().values())
                .concatMap(value -> loop.call(() -> runtimeVoid(
                    value::prepareAsync)))
                .then(loop.call(() -> {
                    ensureMutable();
                    transitionCandidate(CandidatePhase.VALIDATING,
                        EngineOperationStage.VALIDATING);
                    var plans = mergePlans(staged, retained.keySet());
                    var compiled = planner.validate(input, fingerprint, plans, retained.keySet());
                    for (var entry : staged.candidates().entrySet()) {
                        var runtimePlan = entry.getValue().preparedPlan();
                        var order = compiled.dependencyFirst().stream().filter(runtimePlan.units()::containsKey).toList();
                        staged.registerSealed(entry.getKey(), entry.getValue().seal(CompiledRuntimeSlice.of(runtimePlan, order)));
                    }
                    staged.seal(compiled, retained);
                    transitionCandidate(CandidatePhase.READY_TO_SAVE,
                        EngineOperationStage.VALIDATING);
                    publish();
                    DurableTargetToken token = durableToken;
                    if (save) {
                        transitionCandidate(CandidatePhase.SAVING,
                            EngineOperationStage.SAVING);
                        publish();
                        token = targetStore.save(durableTarget == null ? 0 : durableTarget.targetRevision(), target);
                        try { verifyToken(target, token); }
                        catch (RuntimeException invalidConfirmation) {
                            operation.targetSaveState = TargetSaveState.UNCONFIRMED;
                            throw invalidConfirmation;
                        }
                        durableTarget = target;
                        durableToken = token;
                        durableState = DurableTargetState.PRESENT;
                        targetConvergence = TargetConvergence.CONVERGING;
                        operation.targetSaveState = TargetSaveState.SAVED;
                    } else {
                        targetConvergence = TargetConvergence.CONVERGING;
                    }
                    promote(staged, Objects.requireNonNull(token, "durable token"), inputsIdentity);
                    return settleReplacement();
                }));
        }).onErrorResume(this::deploymentFailed);
    }

    private Map<RuntimeId, RuntimePlan> mergePlans(DeploymentCandidate staged, Set<ExecutionUnitKey> retained) {
        var result = new LinkedHashMap<RuntimeId, RuntimePlan>();
        for (var id : drivers.keySet()) {
            var units = new ArrayList<ExecutionUnitPlan>();
            var bindings = new ArrayList<DefinitionBindingPlan>();
            if (current != null) {
                var previous = current.compiled.runtimePlans().get(id);
                if (previous != null) {
                    previous.units().values().stream().filter(unit -> retained.contains(unit.key())).forEach(units::add);
                    previous.definitions().stream().filter(binding -> retained.contains(binding.unitKey())).forEach(bindings::add);
                }
            }
            var value = staged.candidates().get(id);
            if (value != null) {
                var plan = value.preparedPlan();
                if (!id.equals(plan.runtimeId())) throw new IllegalArgumentException("candidate plan has another runtime identity");
                units.addAll(plan.units().values());
                bindings.addAll(plan.definitions());
            }
            if (!units.isEmpty() || !bindings.isEmpty()) result.put(id, RuntimePlan.of(id, units, bindings));
        }
        return result;
    }

    /** 只移动 Engine 内存所有权，不调用 driver，也不执行 I/O。 */
    private void promote(DeploymentCandidate staged, DurableTargetToken token, String inputsIdentity) {
        transitionOperation(EngineOperationStage.PROMOTING);
        var owners = new LinkedHashMap<ExecutionUnitKey, PreparedRuntimeGeneration>();
        if (current != null) staged.compiled().retainedUnits().forEach(key -> owners.put(key, current.owners.get(key)));
        staged.generations().values().forEach(generation -> generation.units().keySet().forEach(key -> owners.put(key, generation)));
        var previous = current;
        var next = new CurrentAttempt(candidate.id, token, staged.compiled(),
            staged.units(), owners, inputsIdentity, CurrentPhase.RECONCILING);
        if (current != null) {
            var replaced = new LinkedHashMap<ExecutionUnitKey, RuntimeUnitGeneration>();
            current.units.forEach((key, unit) -> { if (next.units.get(key) != unit) replaced.put(key, unit); });
            if (!replaced.isEmpty()) {
                retirement = new RetirementBatch(identity("retirement"),
                    previous, replaced);
                next.phase = CurrentPhase.WAITING_FOR_RETIREMENT;
            }
        }
        current = next;
        candidate = null;
        state = EngineState.RUNNING;
        refreshCurrentObservations();
        if (retirement != null) refreshRetirementObservations();
        publish();
    }

    private Mono<PublishedView> settleReplacement() {
        if (retirement != null) {
            retirement.source.compiled.reverseDependency().stream().filter(retirement.units::containsKey)
                .forEach(key -> retirement.units.get(key).closeAdmission());
            transitionRetirement(RetirementPhase.DRAINING);
            transitionOperation(EngineOperationStage.RETIRING);
            publish();
        }
        return drainAndStopRetirement()
            .then(loop.call(() -> reconcile(current.compiled.affectedUnits())))
            .then(loop.call(this::retire))
            .then(loop.call(this::finishCurrentOperation));
    }

    private Mono<Void> drainAndStopRetirement() {
        if (retirement == null) return Mono.empty();
        var batch = retirement;
        var reverse = batch.source.compiled.reverseDependency().stream()
            .filter(batch.units::containsKey).toList();
        return Flux.fromIterable(reverse)
            .concatMap(key -> lifecycle(key, batch.units.get(key), true))
            .then(loop.call(() -> {
                refreshRetirementObservations();
                transitionRetirement(RetirementPhase.STOPPING);
                publish();
                return Mono.empty();
            }))
            .thenMany(Flux.fromIterable(reverse)
                .concatMap(key -> lifecycle(key, batch.units.get(key), false)))
            .then(loop.call(() -> {
                refreshRetirementObservations();
                transitionRetirement(RetirementPhase.READY_TO_RELEASE);
                publish();
                return Mono.empty();
            }));
    }

    private Mono<Void> lifecycle(ExecutionUnitKey key, RuntimeUnitGeneration unit,
                                 boolean drain) {
        return loop.call(() -> {
            var lifecycleOperation = identity(drain ? "drain" : "stop");
            var deadline = Instant.now().plus(lifecycleTimeout);
            return runtimeObservation(() -> drain
                    ? unit.drainAsync(lifecycleOperation, deadline)
                    : unit.stopAsync(lifecycleOperation, deadline),
                "runtime lifecycle returned no observation").then();
        });
    }

    private Mono<PublishedView> reconcile(Set<ExecutionUnitKey> requested) {
        if (current == null) return Mono.just(published.current());
        var closure = DeploymentPlanner.closure(requested, current.compiled);
        if (closure.stream().noneMatch(key -> observeCurrent(key,
            current.units.get(key)).aggregateState()
            == ExecutionObservation.State.PENDING)) {
            return Mono.just(published.current());
        }
        transitionCurrent(CurrentPhase.RECONCILING);
        transitionOperation(EngineOperationStage.RECONCILING);
        publish();
        return Flux.fromIterable(current.compiled.dependencyFirst())
            .filter(closure::contains)
            .concatMap(key -> loop.call(() -> {
                var unit = current.units.get(key);
                if (observeCurrent(key, unit).aggregateState()
                    != ExecutionObservation.State.PENDING) return Mono.empty();
                if (unit.plan().dependencies().stream().anyMatch(dependency ->
                    observeCurrent(dependency, current.units.get(dependency))
                        .aggregateState() != ExecutionObservation.State.ACTIVE)) {
                    return Mono.empty();
                }
                return runtimeObservation(() -> unit.reconcileAsync(
                        identity("activate")),
                    "runtime activation returned no observation")
                    .doOnNext(observed -> {
                        lastObservations.put(unit, observed);
                        if (observed.aggregateState()
                            == ExecutionObservation.State.FAILED) {
                            targetConvergence = TargetConvergence.UNSATISFIED;
                        }
                    }).then().onErrorResume(error -> {
                        // driver 应把普通启动失败转为 FAILED；异常信号是 SPI 契约破坏。
                        failStop(unitFailure(
                            "RUNTIME_ACTIVATION_CONTRACT_VIOLATION",
                            current.id, unit, error,
                            FailureStage.RECONCILING));
                        return Mono.error(error);
                    }).doOnSuccess(ignored -> publish());
            }))
            .then(loop.call(() -> Mono.just(published.current())));
    }

    private Mono<Void> retire() {
        if (retirement == null) return Mono.empty();
        transitionRetirement(RetirementPhase.RELEASING);
        publish();
        var retiringOwners = new LinkedHashSet<PreparedRuntimeGeneration>();
        retirement.source.compiled.reverseDependency().stream()
            .filter(retirement.units::containsKey)
            .forEach(key -> retiringOwners.add(retirement.source.owners.get(key)));
        retiringOwners.removeAll(current.owners.values());
        return Flux.fromIterable(retiringOwners).concatMap(owner ->
                loop.call(() -> runtimeVoid(owner::retireAsync)))
            .then(loop.call(() -> {
                retirement.units.values().forEach(lastObservations::remove);
                retirement = null;
                return Mono.empty();
            }));
    }

    private Mono<PublishedView> deploymentFailed(Throwable error) {
        if (error instanceof MutationGateClosedException) return Mono.error(error);
        var saveState = operation == null ? TargetSaveState.NOT_APPLICABLE
            : operation.targetSaveState;
        if (state == EngineState.FAIL_STOP) {
            return Mono.error(new EngineChangeException(published.current(),
                saveState, error));
        }
        if (error instanceof DeploymentTargetStore.SaveUnconfirmedException
            || saveState == TargetSaveState.UNCONFIRMED) {
            durableState = DurableTargetState.UNCERTAIN;
            targetConvergence = TargetConvergence.BLOCKED;
            operation.targetSaveState = TargetSaveState.UNCONFIRMED;
            var fact = candidateFailure("TARGET_SAVE_UNCERTAIN", error,
                FailureStage.SAVING);
            failStop(fact);
            return Mono.error(new EngineChangeException(published.current(),
                TargetSaveState.UNCONFIRMED, error));
        }
        if (candidate != null) {
            var fact = candidateFailure("CANDIDATE_FAILED", error,
                failureStage());
            candidate.phase = CandidatePhase.FAILED;
            failOperation(fact);
            var staged = candidate;
            publish();
            return cleanupCandidate(staged).then(loop.call(() -> {
                candidate = null;
                if (current == null && durableTarget != null) {
                    targetConvergence = TargetConvergence.BLOCKED;
                    if (operation != null
                        && operation.kind == EngineOperationKind.BOOTSTRAP) {
                        failOperation(durableTargetFailure(
                            "BOOTSTRAP_TARGET_BLOCKED", error,
                            failureStage()));
                    }
                }
                publish();
                return Mono.<PublishedView>error(new EngineChangeException(
                    published.current(), saveState, error));
            })).onErrorResume(cleanup -> {
                if (cleanup instanceof EngineChangeException) {
                    return Mono.error(cleanup);
                }
                error.addSuppressed(cleanup);
                cleanupFailures.add(cleanup.toString());
                var cleanupFact = candidateFailure(
                    "CANDIDATE_CLEANUP_FAILED", error,
                    failureStage());
                failStop(cleanupFact);
                return Mono.error(new EngineChangeException(
                    published.current(), saveState, error));
            });
        }
        if (retirement != null) {
            retirement.phase = RetirementPhase.FAILED;
            if (current != null) current.phase = CurrentPhase.BLOCKED;
            targetConvergence = TargetConvergence.BLOCKED;
            failStop(retirementFailure("LIFECYCLE_CLEANUP_FAILED", error,
                failureStage()));
            return Mono.error(new EngineChangeException(published.current(), saveState, error));
        }
        if (current != null && operation != null
            && operation.stage.ordinal()
            >= EngineOperationStage.PROMOTING.ordinal()) {
            current.phase = CurrentPhase.FAILED;
            targetConvergence = TargetConvergence.BLOCKED;
            failStop(currentFailure("CURRENT_CONVERGENCE_FAILED", error,
                failureStage()));
            return Mono.error(new EngineChangeException(published.current(),
                saveState, error));
        }
        targetConvergence = durableTarget == null ? TargetConvergence.ABSENT
            : TargetConvergence.BLOCKED;
        failOperation(operation != null
            && operation.kind == EngineOperationKind.BOOTSTRAP
            && durableTarget != null
            ? durableTargetFailure("BOOTSTRAP_TARGET_BLOCKED", error,
                failureStage())
            : operationFailure("DEPLOYMENT_PLANNING_FAILED", error,
                failureStage()));
        publish();
        return Mono.error(new EngineChangeException(published.current(),
            saveState, error));
    }

    private Mono<Void> cleanupCandidate(CandidateAttempt staged) {
        var deployment = staged.deployment;
        var actions = new ArrayList<Supplier<Mono<Void>>>();
        for (var entry : new ArrayList<>(deployment.candidates().entrySet()).reversed()) {
            var sealed = deployment.generations().get(entry.getKey());
            actions.add(sealed == null ? entry.getValue()::closeAsync : sealed::abortAsync);
        }
        return cleanupAll(actions);
    }

    private Mono<Void> cleanupAll(List<Supplier<Mono<Void>>> actions) {
        var failures = new ArrayList<Throwable>();
        return Flux.fromIterable(actions).concatMap(action -> loop.call(() ->
                runtimeVoid(action)).onErrorResume(error -> {
                    failures.add(error);
                    return Mono.empty();
                }))
            .then(loop.call(() -> {
                if (failures.isEmpty()) return Mono.empty();
                var result = new IllegalStateException("runtime cleanup failed",
                    failures.getFirst());
                failures.stream().skip(1).forEach(result::addSuppressed);
                return Mono.error(result);
            }));
    }

    private void failStop(FailureFact fact) {
        mutationGate = false;
        admissionOpen = false;
        state = EngineState.FAIL_STOP;
        failure = Objects.requireNonNull(fact, "fact");
        if (operation != null) operation.outcome = EngineOperationOutcome.FAILED;
        switch (fact.subject()) {
            case FailureSubject.Candidate subject -> {
                if (candidate != null
                    && candidate.id.equals(subject.attemptId())) {
                    candidate.phase = CandidatePhase.FAILED;
                }
            }
            case FailureSubject.Current subject -> {
                if (current != null
                    && current.id.equals(subject.attemptId())) {
                    current.phase = CurrentPhase.FAILED;
                }
            }
            case FailureSubject.Retirement subject -> {
                if (retirement != null && retirement.id.equals(subject.batchId())
                    && retirement.source.id.equals(subject.sourceAttemptId())) {
                    retirement.phase = RetirementPhase.FAILED;
                    if (current != null) current.phase = CurrentPhase.BLOCKED;
                }
            }
            case FailureSubject.Unit subject -> {
                if (current != null
                    && current.id.equals(subject.ownerId())) {
                    current.phase = CurrentPhase.FAILED;
                } else if (retirement != null
                    && retirement.id.equals(subject.ownerId())) {
                    retirement.phase = RetirementPhase.FAILED;
                    if (current != null) current.phase = CurrentPhase.BLOCKED;
                }
            }
            case FailureSubject.Engine ignored -> { }
            case FailureSubject.DurableTarget ignored -> { }
            case FailureSubject.Operation ignored -> { }
        }
        var owned = Collections.newSetFromMap(
            new IdentityHashMap<RuntimeUnitGeneration, Boolean>());
        if (current != null) owned.addAll(current.units.values());
        if (retirement != null) owned.addAll(retirement.units.values());
        if (candidate != null) candidate.deployment.generations().values()
            .forEach(value -> owned.addAll(value.units().values()));
        for (var unit : owned) {
            try {
                unit.closeAdmission();
            } catch (RuntimeException | Error violation) {
                cleanupFailures.add(violation.toString());
            }
        }
        publish();
        if (terminationRequest == null) {
            terminationRequest = new HostTerminationRequest(hostInstanceId,
                fact.reason(), fact.subject(), fact.operationStage(),
                fact.targetRevision());
            publish();
            var request = terminationRequest;
            notifications.execute(() -> {
                try { terminationPort.requestTermination(request); }
                catch (Throwable portFailure) { LOG.error("Host termination notification failed", portFailure); }
            });
        }
    }

    private Mono<Void> shutdown() {
        mutationGate = false;
        admissionOpen = false;
        if (operation == null || operation.kind != EngineOperationKind.SHUTDOWN) {
            beginOperation(EngineOperationKind.SHUTDOWN,
                durableTarget == null ? 0 : durableTarget.targetRevision(),
                TargetSaveState.NOT_APPLICABLE);
            state = EngineState.CLOSING;
        }
        if (candidate != null) {
            return cleanupCandidate(candidate).then(loop.call(() -> {
                candidate = null;
                return shutdown();
            })).onErrorResume(error -> {
                failStop(candidateFailure("HOST_CLOSE_FAILED", error,
                    FailureStage.CLOSING));
                return Mono.error(error);
            });
        }
        if (retirement != null) return Mono.error(new IllegalStateException(
            "retirement cleanup failed; resource ownership retained for Host termination"));
        if (current != null) {
            retirement = new RetirementBatch(identity("retirement"),
                current, current.units);
            current.compiled.reverseDependency().forEach(key -> current.units.get(key).closeAdmission());
            var old = current;
            current = null;
            transitionRetirement(RetirementPhase.DRAINING);
            transitionOperation(EngineOperationStage.RETIRING);
            publish();
            var owners = Collections.newSetFromMap(new IdentityHashMap<PreparedRuntimeGeneration, Boolean>());
            owners.addAll(old.owners.values());
            return drainAndStopRetirement().thenMany(Flux.fromIterable(owners)
                .concatMap(owner -> loop.call(() -> runtimeVoid(
                    owner::retireAsync))))
                .then(loop.call(() -> {
                    old.units.values().forEach(lastObservations::remove);
                    retirement = null;
                    return shutdown();
                })).onErrorResume(error -> {
                    if (retirement != null) {
                        failStop(retirementFailure("HOST_CLOSE_FAILED", error,
                            FailureStage.CLOSING));
                    } else {
                        failStop(engineFailure("HOST_CLOSE_FAILED", error,
                            FailureStage.CLOSING));
                    }
                    return Mono.error(error);
                });
        }
        var actions = new ArrayList<Supplier<Mono<Void>>>();
        new ArrayList<>(drivers.values()).reversed().forEach(driver -> actions.add(driver::closeAsync));
        actions.add(directory::closeAsync);
        actions.add(domain::closeAsync);
        actions.add(runtime::closeAsync);
        actions.add(() -> Mono.fromRunnable(targetStore::close));
        actions.add(() -> Mono.fromRunnable(packageStore::close));
        return cleanupAll(actions).then(loop.call(() -> {
            lastObservations.clear();
            state = EngineState.CLOSED;
            completeOperation();
            publish();
            views.tryEmitComplete();
            return Mono.empty();
        }));
    }

    private <D, I, O> Mono<O> invokePublished(String revision, long registration,
                                               ContributionKind<D, I, O> kind,
                                               ContributionId id, I input) {
        return Mono.defer(() -> {
            if (!admissionOpen || closing.get()) return Mono.error(new MutationGateClosedException());
            var current = publication.get();
            if (!Objects.equals(revision, current.view().viewRevision())) return Mono.error(
                new PublishedRevisionConflictException(revision, current.view().viewRevision()));
            final ContributionCall<I, O> call;
            try { call = current.routes().acquire(kind, id, registration); }
            catch (RuntimeException error) { return Mono.error(error); }
            if (!admissionOpen || publication.get() != current || closing.get()) {
                call.close();
                return Mono.error(new PublishedRevisionConflictException(revision, publication.get().view().viewRevision()));
            }
            final Scope scope;
            try { scope = domain.rootScope().openChild("invocation:" + kind.name()); }
            catch (RuntimeException | Error error) { call.close(); return Mono.error(error); }
            var cleanup = Mono.defer(scope::closeAsync).then(Mono.fromRunnable(() -> {
                var failures = domain.cleanupFailures(scope);
                if (!failures.isEmpty()) throw new IllegalStateException("invocation cleanup failed: " + failures);
            })).doOnSuccess(ignored -> call.close()).doOnError(error -> call.failCleanup(error.toString())).cache();
            return Mono.usingWhen(Mono.just(scope), value -> call.invoke(value.context(), input),
                ignored -> cleanup, (ignored, error) -> cleanup, ignored -> cleanup);
        });
    }

    private void publish() {
        var contributions = directory.current();
        var unitFacts = currentObservations();
        var retiredFacts = retirementObservations();
        var snapshot = new EngineSnapshot(state, hostInstanceId, durableState,
            targetConvergence, Optional.ofNullable(durableTarget),
            candidate == null ? Optional.empty() : Optional.of(
                new CandidateAttemptSnapshot(candidate.id, candidate.phase,
                    candidate.deployment.target().targetRevision(),
                    Optional.ofNullable(candidate.deployment.compiled())
                        .map(CompiledDeployment::compiledFingerprint),
                    candidate.deployment.ownedUnitKeys())),
            current == null ? Optional.empty() : Optional.of(
                new CurrentAttemptSnapshot(current.id, current.phase,
                    current.token.targetRevision(),
                    current.compiled.compiledFingerprint(), unitFacts)),
            retirement == null ? Optional.empty() : Optional.of(
                new RetirementBatchSnapshot(retirement.id,
                    retirement.source.id,
                    retirement.phase, retirement.source.token.targetRevision(),
                    retirement.source.compiled.compiledFingerprint(),
                    retiredFacts)));
        var diagnostics = new EngineDiagnostics(
            Optional.ofNullable(operation).map(EngineOperation::snapshot),
            mutationGate, admissionOpen, cleanupFailures,
            Optional.ofNullable(terminationRequest), Optional.ofNullable(failure));
        var domainSnapshot = domain.snapshot();
        var runtimeDiagnostics = RuntimeDiagnostics.builder().domainName(domainSnapshot.name())
            .plugins(domainSnapshot.plugins()).services(domainSnapshot.services()).events(domainSnapshot.events())
            .cleanupFailures(domainSnapshot.cleanupFailures()).build();
        var previous = publication.get();
        if (previous != null && previous.view.engine().equals(snapshot)
            && previous.view.contributions().equals(contributions.snapshot())
            && previous.view.engineDiagnostics().equals(diagnostics)
            && previous.view.diagnostics().equals(runtimeDiagnostics)) return;
        var view = PublishedView.builder().viewRevision(identity("view")).engine(snapshot)
            .contributions(contributions.snapshot()).diagnostics(runtimeDiagnostics).engineDiagnostics(diagnostics).build();
        publication.set(new PublishedState(view, contributions.routes()));
        views.tryEmitNext(view);
    }

    private void beginOperation(EngineOperationKind kind, long targetRevision,
                                TargetSaveState targetSaveState) {
        operation = new EngineOperation(identity("operation"), kind,
            targetRevision, targetSaveState);
        failure = null;
    }

    private void transitionOperation(EngineOperationStage next) {
        if (operation != null) operation.stage = Objects.requireNonNull(next,
            "next");
    }

    private void completeOperation() {
        if (operation == null) return;
        operation.stage = EngineOperationStage.COMPLETED;
        operation.outcome = EngineOperationOutcome.SUCCEEDED;
    }

    private void failOperation(FailureFact fact) {
        failure = Objects.requireNonNull(fact, "fact");
        if (operation != null) operation.outcome = EngineOperationOutcome.FAILED;
    }

    private void transitionCandidate(CandidatePhase next,
                                     EngineOperationStage operationStage) {
        if (candidate == null) throw new IllegalStateException(
            "candidate attempt is missing");
        candidate.phase = Objects.requireNonNull(next, "next");
        transitionOperation(operationStage);
    }

    private void transitionCurrent(CurrentPhase next) {
        if (current == null) throw new IllegalStateException(
            "current attempt is missing");
        current.phase = Objects.requireNonNull(next, "next");
    }

    private void transitionRetirement(RetirementPhase next) {
        if (retirement == null) throw new IllegalStateException(
            "retirement batch is missing");
        retirement.phase = Objects.requireNonNull(next, "next");
    }

    private Mono<PublishedView> finishCurrentOperation() {
        refreshCurrentObservations();
        transitionCurrent(CurrentPhase.SETTLED);
        targetConvergence = targetSatisfied(currentObservations())
            ? TargetConvergence.SATISFIED : TargetConvergence.UNSATISFIED;
        completeOperation();
        publish();
        return Mono.just(published.current());
    }

    private Map<ExecutionUnitKey, ExecutionObservation> currentObservations() {
        return cachedObservations(current == null ? Map.of() : current.units);
    }

    private Map<ExecutionUnitKey, ExecutionObservation> retirementObservations() {
        return cachedObservations(retirement == null ? Map.of()
            : retirement.units);
    }

    private Map<ExecutionUnitKey, ExecutionObservation> cachedObservations(
        Map<ExecutionUnitKey, RuntimeUnitGeneration> units) {
        var result = new LinkedHashMap<ExecutionUnitKey, ExecutionObservation>();
        units.forEach((key, unit) -> {
            var observation = lastObservations.get(unit);
            if (observation != null) result.put(key, observation);
        });
        return Map.copyOf(result);
    }

    private void refreshCurrentObservations() {
        if (current != null) current.units.forEach(this::observeCurrent);
    }

    private void refreshRetirementObservations() {
        if (retirement != null) retirement.units.forEach(this::observeRetirement);
    }

    private ExecutionObservation observeCurrent(ExecutionUnitKey key,
                                                RuntimeUnitGeneration unit) {
        return observe(key, unit, current.id);
    }

    private ExecutionObservation observeRetirement(ExecutionUnitKey key,
                                                   RuntimeUnitGeneration unit) {
        return observe(key, unit, retirement.id);
    }

    private ExecutionObservation observe(ExecutionUnitKey key,
                                         RuntimeUnitGeneration unit,
                                         String ownerId) {
        try {
            var observation = Objects.requireNonNull(unit.snapshot(), "runtime unit snapshot");
            lastObservations.put(unit, observation);
            return observation;
        } catch (RuntimeException | Error violation) {
            failStop(unitFailure("RUNTIME_SNAPSHOT_CONTRACT_VIOLATION",
                ownerId, unit, violation, FailureStage.OBSERVING));
            throw violation;
        }
    }

    private boolean targetSatisfied(
        Map<ExecutionUnitKey, ExecutionObservation> observations) {
        return current != null && current.compiled.runtimePlans().values().stream()
            .flatMap(plan -> plan.definitions().stream()).allMatch(binding -> {
                var observation = observations.get(binding.unitKey());
                if (observation == null) return false;
                var state = observation.aggregateState();
                return state == ExecutionObservation.State.ACTIVE
                    || state == ExecutionObservation.State.PENDING
                    && binding.publicationRequirement() == PublicationRequirement.PENDING_ALLOWED;
            });
    }

    private <T> Mono<T> runtimeObservation(Supplier<Mono<T>> action,
                                           String emptyMessage) {
        return Mono.defer(() -> Objects.requireNonNull(action.get(),
                "runtime lifecycle publisher"))
            .timeout(lifecycleTimeout)
            .switchIfEmpty(Mono.error(new IllegalStateException(emptyMessage)));
    }

    private Mono<Void> runtimeVoid(Supplier<Mono<Void>> action) {
        return Mono.defer(() -> Objects.requireNonNull(action.get(),
                "runtime lifecycle publisher"))
            .timeout(lifecycleTimeout);
    }

    private FailureFact engineFailure(String reason, Throwable error,
                                      FailureStage stage) {
        return failureFact(reason, new FailureSubject.Engine(hostInstanceId),
            error, stage);
    }

    private FailureFact durableTargetFailure(String reason, Throwable error,
                                             FailureStage stage) {
        if (durableTarget == null) {
            throw new IllegalStateException("durable target is missing");
        }
        return failureFact(reason, new FailureSubject.DurableTarget(
            durableTarget.targetRevision(), durableTarget.targetDigest()),
            error, stage);
    }

    private FailureFact operationFailure(String reason, Throwable error,
                                         FailureStage stage) {
        if (operation == null) {
            throw new IllegalStateException("operation is missing");
        }
        return failureFact(reason, new FailureSubject.Operation(operation.id),
            error, stage);
    }

    private FailureFact candidateFailure(String reason, Throwable error,
                                         FailureStage stage) {
        if (candidate == null) {
            throw new IllegalStateException("candidate is missing");
        }
        return failureFact(reason, new FailureSubject.Candidate(candidate.id),
            error, stage);
    }

    private FailureFact currentFailure(String reason, Throwable error,
                                       FailureStage stage) {
        if (current == null) {
            throw new IllegalStateException("current attempt is missing");
        }
        return failureFact(reason, new FailureSubject.Current(current.id),
            error, stage);
    }

    private FailureFact retirementFailure(String reason, Throwable error,
                                          FailureStage stage) {
        if (retirement == null) {
            throw new IllegalStateException("retirement batch is missing");
        }
        return failureFact(reason, new FailureSubject.Retirement(retirement.id,
            retirement.source.id), error, stage);
    }

    private FailureFact unitFailure(String reason, String ownerId,
                                    RuntimeUnitGeneration unit, Throwable error,
                                    FailureStage stage) {
        return failureFact(reason, new FailureSubject.Unit(ownerId,
            unit.fence()), error, stage);
    }

    private FailureFact failureFact(String reason, FailureSubject subject,
                                    Throwable error, FailureStage stage) {
        var operationStage = operation == null ? Optional.<EngineOperationStage>empty()
            : Optional.of(operation.stage);
        long revision = operation == null ? 0 : operation.targetRevision;
        if (revision < 1 && durableTarget != null) {
            revision = durableTarget.targetRevision();
        }
        return new FailureFact(reason, subject, stage, operationStage,
            revision < 1 ? OptionalLong.empty() : OptionalLong.of(revision),
            error.toString());
    }

    private FailureStage failureStage() {
        if (retirement != null) {
            return switch (retirement.phase) {
                case DRAINING -> FailureStage.DRAINING;
                case STOPPING, READY_TO_RELEASE -> FailureStage.STOPPING;
                case RELEASING -> FailureStage.RELEASING;
                case FAILED -> FailureStage.CLOSING;
            };
        }
        if (candidate != null) {
            return switch (candidate.phase) {
                case REGISTERED -> FailureStage.PLANNING;
                case PREPARING -> FailureStage.PREPARING;
                case VALIDATING, READY_TO_SAVE -> FailureStage.VALIDATING;
                case SAVING -> FailureStage.SAVING;
                case FAILED -> FailureStage.CLOSING;
            };
        }
        if (current != null && current.phase == CurrentPhase.RECONCILING) {
            return FailureStage.RECONCILING;
        }
        if (operation == null) return FailureStage.BOOTSTRAPPING;
        return switch (operation.stage) {
            case PLANNING -> FailureStage.PLANNING;
            case PREPARING -> FailureStage.PREPARING;
            case VALIDATING -> FailureStage.VALIDATING;
            case SAVING -> FailureStage.SAVING;
            case PROMOTING -> FailureStage.PROMOTING;
            case RETIRING -> FailureStage.DRAINING;
            case RECONCILING -> FailureStage.RECONCILING;
            case COMPLETED -> FailureStage.OBSERVING;
        };
    }
    private void ensureMutable() {
        if (!mutationGate || closing.get()) throw new MutationGateClosedException();
        if (state == EngineState.NEW) throw new IllegalStateException("engine is not started");
    }
    private RuntimeDriver requireDriver(RuntimeId id) {
        var driver = drivers.get(id);
        if (driver == null) throw new IllegalArgumentException("runtime provider is missing: " + id);
        return driver;
    }
    private String identity(String namespace) { return hostInstanceId + ':' + namespace + ':' + identities.incrementAndGet(); }
    private List<BuiltInPluginPackage> captureBuiltIns() {
        var result = providers.providers().values().stream().flatMap(provider -> {
            var metadata = List.copyOf(provider.builtInPackages());
            if (metadata.stream().flatMap(value -> value.facets().stream())
                .anyMatch(value -> !value.runtimeId().equals(provider.id()))) {
                throw new IllegalArgumentException("provider declares another runtime's built-in package");
            }
            return metadata.stream();
        }).sorted(Comparator.comparing(value -> value.pluginId().value())).toList();
        if (result.stream().map(BuiltInPluginPackage::pluginId).distinct().count() != result.size()) {
            throw new IllegalArgumentException("duplicate built-in plugin package");
        }
        return result;
    }
    private String inputFingerprint(HostCapabilitySnapshot snapshot, List<BuiltInPluginPackage> builtIns) {
        var contracts = new TreeMap<String, Object>();
        providers.providers().forEach((id, provider) -> {
            var identity = provider.contractIdentity();
            if (identity == null || identity.isBlank()) throw new IllegalArgumentException("runtime contract identity is blank");
            contracts.put(id.value(), identity);
        });
        var metadata = builtIns.stream().map(value -> Map.of(
            "pluginId", value.pluginId().value(), "version", value.version(),
            "packageDigest", value.packageDigest(), "facets", value.facets().stream().map(facet -> Map.of(
                "facetId", facet.facetId().value(), "runtimeId", facet.runtimeId().value(),
                "executionTarget", facet.executionTarget().value(),
                "dependencies", facet.dependencies().stream()
                    .sorted(Comparator.comparing((FacetDependency dependency) -> dependency.pluginId().value())
                        .thenComparing(dependency -> dependency.facetId().value()))
                    .map(dependency -> Map.of("pluginId", dependency.pluginId().value(),
                        "facetId", dependency.facetId().value())).toList(),
                "requiredCapabilities", facet.requiredCapabilities().stream().sorted().toList(),
                "definitionIds", facet.definitionIds().stream().sorted().toList())).toList())).toList();
        return digest(LiteralValue.of(Map.of("capabilities", snapshot.values(), "contracts", contracts,
            "builtInPackages", metadata)).canonicalJson());
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void verifyToken(DeploymentTarget target, DurableTargetToken token) {
        if (token.targetRevision() != target.targetRevision() || !token.targetDigest().equals(target.targetDigest())) {
            throw new IllegalStateException("store confirmed a different target");
        }
    }

    private final class HostServices implements RuntimeHostServices {
        public String hostInstanceId() { return hostInstanceId; }
        public ScopeView scope() { return domain.rootScope().context().scope(); }
        public Mono<Void> releaseScope(Scope scope) {
            Objects.requireNonNull(scope, "scope");
            return Mono.defer(scope::closeAsync).then(Mono.fromRunnable(() -> {
                var failures = domain.cleanupFailures(scope);
                if (!failures.isEmpty()) {
                    throw new IllegalStateException(
                        "runtime scope cleanup failed: " + failures);
                }
            }));
        }
        public ContributionKindRegistry contributionKinds() { return contributionKinds; }
        public ContributionAdmission openContributionAdmission(ExecutionUnitKey key) {
            Objects.requireNonNull(key, "key");
            if (!admissionOpen || closing.get()) throw new MutationGateClosedException();
            return directory.openAdmission(key.value());
        }
        public RemoteContributionInvoker remoteContributions() {
            return remoteContributions;
        }
        public String nextIdentity(String namespace) { return identity(namespace); }
        public void requestReconcile(Set<RuntimeUnitFence> fences,
                                     String reason) {
            var requested = Set.copyOf(Objects.requireNonNull(fences,
                "fences"));
            if (requested.isEmpty()) return;
            if (closing.get()) return;
            loop.submit(() -> {
                ensureMutable();
                if (current == null) return Mono.<Void>empty();
                var valid = new LinkedHashMap<ExecutionUnitKey,
                    RuntimeUnitFence>();
                var failed = new LinkedHashSet<ExecutionUnitKey>();
                requested.forEach(fence -> {
                    var unit = current.units.get(fence.unitKey());
                    if (unit == null) return;
                    var observation = observeCurrent(fence.unitKey(), unit);
                    if (!matchesFence(unit, fence)) return;
                    valid.put(fence.unitKey(), fence);
                    if (observation.aggregateState()
                        == ExecutionObservation.State.FAILED) {
                        failed.add(fence.unitKey());
                    }
                });
                if (valid.isEmpty()) return Mono.<Void>empty();
                var notFailed = new LinkedHashSet<>(valid.keySet());
                notFailed.removeAll(failed);
                if (failed.isEmpty()) {
                    beginOperation(EngineOperationKind.RECONCILE,
                        durableTarget.targetRevision(),
                        TargetSaveState.NOT_APPLICABLE);
                    return reconcile(notFailed)
                        .then(loop.call(
                            FibraEngine.this::finishCurrentOperation)).then();
                }
                return deploy(durableTarget, false, false, failed,
                    EngineOperationKind.RECONCILE)
                    .then(loop.call(() -> {
                        if (current == null) return Mono.empty();
                        var stillCurrent = new LinkedHashSet<ExecutionUnitKey>();
                        notFailed.forEach(key -> {
                            var unit = current.units.get(key);
                            if (unit != null && matchesFence(unit,
                                valid.get(key))) {
                                stillCurrent.add(key);
                            }
                        });
                        if (stillCurrent.isEmpty()) return Mono.empty();
                        beginOperation(EngineOperationKind.RECONCILE,
                            durableTarget.targetRevision(),
                            TargetSaveState.NOT_APPLICABLE);
                        return reconcile(stillCurrent)
                            .then(loop.call(
                                FibraEngine.this::finishCurrentOperation))
                            .then();
                    }));
            }).subscribe(ignored -> { }, error -> LOG.debug("Runtime reconcile was not accepted: {}", reason, error));
        }
        public void requestObservationRefresh(RuntimeUnitFence fence) {
            Objects.requireNonNull(fence, "fence");
            if (closing.get()) return;
            loop.observe(() -> {
                if (current == null) return;
                var unit = current.units.get(fence.unitKey());
                if (unit == null || !matchesFence(unit, fence)) return;
                observeCurrent(fence.unitKey(), unit);
                targetConvergence = targetSatisfied(currentObservations())
                    ? TargetConvergence.SATISFIED
                    : TargetConvergence.UNSATISFIED;
                publish();
            });
        }
        public void requestDisable(RuntimeUnitDisableRequest request) {
            Objects.requireNonNull(request, "request");
            if (closing.get()) return;
            loop.submit(() -> {
                ensureMutable();
                if (current == null || durableTarget == null) {
                    return Mono.just(published.current());
                }
                var unit = current.units.get(request.fence().unitKey());
                if (unit == null || !matchesFence(unit, request.fence())) {
                    return Mono.just(published.current());
                }
                var entry = durableTarget.desiredGraph().plugins().get(
                    request.fence().unitKey().value());
                if (entry == null || !entry.enabled()) {
                    return Mono.just(published.current());
                }
                var replacement = DeploymentTarget.of(Math.incrementExact(
                        durableTarget.targetRevision()),
                    durableTarget.selections().values(),
                    durableTarget.desiredGraph().withEnabled(
                        request.fence().unitKey().value(), false),
                    durableTarget.configContext());
                return deploy(replacement, true, false, Set.of(),
                    EngineOperationKind.APPLY);
            }).subscribe(ignored -> { }, error -> LOG.debug(
                "Runtime unit disable failed: {}", request.reason(), error));
        }
        public void requestRecompile(RuntimeRecompileReason reason) {
            loop.submit(() -> {
                ensureMutable();
                return durableTarget == null ? Mono.empty()
                    : deploy(durableTarget, false, true, Set.of(),
                        EngineOperationKind.RECONCILE);
            }).subscribe(ignored -> { }, error -> LOG.debug("Runtime recompile failed: {}", reason, error));
        }

        private boolean matchesFence(RuntimeUnitGeneration unit,
                                     RuntimeUnitFence fence) {
            if (!fence.runtimeId().equals(unit.plan().runtimeId())
                || !fence.unitKey().equals(unit.plan().key())) {
                return false;
            }
            var observation = lastObservations.get(unit);
            return observation != null && observation.executions().stream()
                .anyMatch(execution -> execution.unitTargetRevision()
                    == fence.unitTargetRevision()
                    && execution.runtimeInstanceId().equals(
                        fence.runtimeInstanceId()));
        }
    }

    private record PublishedState(PublishedView view, ContributionRoutes routes) { }
    private static final class CandidateAttempt {
        private final String id;
        private final DeploymentCandidate deployment;
        private CandidatePhase phase = CandidatePhase.REGISTERED;

        private CandidateAttempt(String id, DeploymentCandidate deployment) {
            this.id = Objects.requireNonNull(id, "id");
            this.deployment = Objects.requireNonNull(deployment, "deployment");
        }
    }

    private static final class CurrentAttempt {
        private final String id;
        private final DurableTargetToken token;
        private final CompiledDeployment compiled;
        private final Map<ExecutionUnitKey, RuntimeUnitGeneration> units;
        private final Map<ExecutionUnitKey, PreparedRuntimeGeneration> owners;
        private final String inputsIdentity;
        private CurrentPhase phase;

        private CurrentAttempt(String id, DurableTargetToken token,
                               CompiledDeployment compiled,
                               Map<ExecutionUnitKey, RuntimeUnitGeneration> units,
                               Map<ExecutionUnitKey, PreparedRuntimeGeneration> owners,
                               String inputsIdentity, CurrentPhase phase) {
            this.id = Objects.requireNonNull(id, "id");
            this.token = Objects.requireNonNull(token, "token");
            this.compiled = Objects.requireNonNull(compiled, "compiled");
            this.units = Map.copyOf(units);
            this.owners = Map.copyOf(owners);
            this.inputsIdentity = Objects.requireNonNull(inputsIdentity,
                "inputsIdentity");
            this.phase = Objects.requireNonNull(phase, "phase");
        }
    }

    private static final class RetirementBatch {
        private final String id;
        private final CurrentAttempt source;
        private final Map<ExecutionUnitKey, RuntimeUnitGeneration> units;
        private RetirementPhase phase = RetirementPhase.DRAINING;

        private RetirementBatch(String id, CurrentAttempt source,
                                Map<ExecutionUnitKey, RuntimeUnitGeneration> units) {
            this.id = Objects.requireNonNull(id, "id");
            this.source = Objects.requireNonNull(source, "source");
            this.units = Map.copyOf(units);
        }
    }

    private static final class EngineOperation {
        private final String id;
        private final EngineOperationKind kind;
        private final long targetRevision;
        private EngineOperationStage stage = EngineOperationStage.PLANNING;
        private EngineOperationOutcome outcome = EngineOperationOutcome.RUNNING;
        private TargetSaveState targetSaveState;
        private String attemptId;

        private EngineOperation(String id, EngineOperationKind kind,
                                long targetRevision, TargetSaveState targetSaveState) {
            this.id = Objects.requireNonNull(id, "id");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.targetRevision = targetRevision;
            this.targetSaveState = Objects.requireNonNull(targetSaveState,
                "targetSaveState");
        }

        private EngineOperationSnapshot snapshot() {
            return new EngineOperationSnapshot(id, kind, stage, outcome,
                targetRevision, targetSaveState,
                Optional.ofNullable(attemptId));
        }
    }

    public static final class Builder {
        private final PluginPackageStore packageStore;
        private final DeploymentTargetStore targetStore;
        private final List<RuntimeProvider> providers = new ArrayList<>();
        private HostServiceRegistry hostServices = new HostServiceRegistry();
        private ContributionKindRegistry contributionKinds = ContributionKindRegistry.empty();
        private HostTerminationPort terminationPort;
        private Supplier<HostCapabilitySnapshot> capabilities = HostCapabilitySnapshot::empty;
        private Duration lifecycleTimeout = Duration.ofSeconds(30);
        private Builder(PluginPackageStore packages, DeploymentTargetStore targets) {
            packageStore = Objects.requireNonNull(packages, "packageStore");
            targetStore = Objects.requireNonNull(targets, "targetStore");
        }
        public Builder runtimeProvider(RuntimeProvider value) { providers.add(Objects.requireNonNull(value)); return this; }
        public Builder hostServices(HostServiceRegistry value) {
            hostServices = Objects.requireNonNull(value, "hostServices");
            return this;
        }
        public Builder contributionKinds(ContributionKindRegistry value) {
            contributionKinds = Objects.requireNonNull(value, "contributionKinds");
            return this;
        }
        public Builder hostTerminationPort(HostTerminationPort value) { terminationPort = Objects.requireNonNull(value); return this; }
        public Builder capabilities(Supplier<HostCapabilitySnapshot> value) { capabilities = Objects.requireNonNull(value); return this; }
        public Builder lifecycleTimeout(Duration value) {
            if (value.isNegative() || value.isZero()) throw new IllegalArgumentException("lifecycle timeout must be positive");
            lifecycleTimeout = value; return this;
        }
        public FibraEngine build() { return new FibraEngine(this); }
    }
}
