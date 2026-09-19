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
    private DeploymentCandidate candidate;
    private String candidateId;
    private Attempt current;
    private Retirement retirement;
    private AttemptPhase phase = AttemptPhase.REGISTERED;
    private TargetSaveState saveState = TargetSaveState.NOT_APPLICABLE;
    private HostTerminationRequest terminationRequest;
    private final List<String> cleanupFailures = new ArrayList<>();
    private final Map<RuntimeUnitGeneration, ExecutionObservation> lastObservations = new IdentityHashMap<>();
    private Throwable observationFailure;
    private String failure;

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
                    fatal("CONTRIBUTION_DIRECTORY_FAILED", error))).subscribe());
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
                phase = AttemptPhase.SETTLED;
                publish();
                return Mono.just(published.current());
            }
            durableTarget = stored.get().target();
            durableToken = stored.get().token();
            verifyToken(durableTarget, durableToken);
            durableState = DurableTargetState.PRESENT;
            return deploy(durableTarget, false, true, Set.of());
        }).onErrorResume(error -> {
            failure = error.toString();
            state = EngineState.FAILED;
            phase = AttemptPhase.FAILED;
            publish();
            return Mono.error(error);
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
        return deploy(same ? durableTarget : target, !same, false, Set.of());
    }

    private Mono<PublishedView> retryCurrent() {
        if (durableTarget == null) return Mono.just(published.current());
        var failed = new LinkedHashSet<ExecutionUnitKey>();
        if (current != null) current.units.forEach((key, unit) -> {
            if (observe(unit).aggregateState() == ExecutionObservation.State.FAILED) failed.add(key);
        });
        if (current != null && failed.isEmpty()) return reconcile(current.units.keySet());
        return deploy(durableTarget, false, current == null, failed);
    }

    private Mono<PublishedView> deploy(DeploymentTarget target, boolean save,
                                       boolean forceAll, Set<ExecutionUnitKey> forced) {
        return Mono.defer(() -> {
            ensureMutable();
            var nextCapabilities = Objects.requireNonNull(capabilitySource.get(), "capability snapshot");
            var builtIns = captureBuiltIns();
            var inputsIdentity = inputFingerprint(nextCapabilities, builtIns);
            var fingerprint = digest(target.targetDigest() + ":" + inputsIdentity);
            if (!save && current != null && current.compiled.compiledFingerprint().equals(fingerprint)
                && forced.isEmpty() && targetSatisfied(observations(current.units))) {
                return Mono.just(published.current());
            }
            phase = AttemptPhase.REGISTERED;
            saveState = save ? TargetSaveState.NOT_SAVED : TargetSaveState.NOT_APPLICABLE;
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
            candidate = new DeploymentCandidate(target);
            candidateId = identity("attempt");
            var staged = candidate;
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
            phase = AttemptPhase.PREPARING;
            publish();
            return Flux.fromIterable(staged.candidates().values())
                .concatMap(value -> loop.call(() -> value.prepareAsync().timeout(lifecycleTimeout))).then(loop.call(() -> {
                    ensureMutable();
                    phase = AttemptPhase.VALIDATING;
                    var plans = mergePlans(staged, retained.keySet());
                    var compiled = planner.validate(input, fingerprint, plans, retained.keySet());
                    for (var entry : staged.candidates().entrySet()) {
                        var runtimePlan = entry.getValue().preparedPlan();
                        var order = compiled.dependencyFirst().stream().filter(runtimePlan.units()::containsKey).toList();
                        staged.registerSealed(entry.getKey(), entry.getValue().seal(CompiledRuntimeSlice.of(runtimePlan, order)));
                    }
                    staged.seal(compiled, retained);
                    phase = AttemptPhase.READY_TO_SAVE;
                    publish();
                    DurableTargetToken token = durableToken;
                    if (save) {
                        phase = AttemptPhase.SAVING;
                        publish();
                        token = targetStore.save(durableTarget == null ? 0 : durableTarget.targetRevision(), target);
                        try { verifyToken(target, token); }
                        catch (RuntimeException invalidConfirmation) {
                            saveState = TargetSaveState.UNCONFIRMED;
                            throw invalidConfirmation;
                        }
                        durableTarget = target;
                        durableToken = token;
                        durableState = DurableTargetState.PRESENT;
                        saveState = TargetSaveState.SAVED;
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
        phase = AttemptPhase.PROMOTING;
        var owners = new LinkedHashMap<ExecutionUnitKey, PreparedRuntimeGeneration>();
        if (current != null) staged.compiled().retainedUnits().forEach(key -> owners.put(key, current.owners.get(key)));
        staged.generations().values().forEach(generation -> generation.units().keySet().forEach(key -> owners.put(key, generation)));
        var next = new Attempt(candidateId, token, staged.compiled(), staged.units(), owners, inputsIdentity);
        if (current != null) {
            var replaced = new LinkedHashMap<ExecutionUnitKey, RuntimeUnitGeneration>();
            current.units.forEach((key, unit) -> { if (next.units.get(key) != unit) replaced.put(key, unit); });
            if (!replaced.isEmpty()) retirement = new Retirement(current, replaced);
        }
        current = next;
        candidate = null;
        candidateId = null;
        state = EngineState.RUNNING;
        publish();
    }

    private Mono<PublishedView> settleReplacement() {
        if (retirement != null) {
            retirement.previous.compiled.reverseDependency().stream().filter(retirement.units::containsKey)
                .forEach(key -> retirement.units.get(key).closeAdmission());
            phase = AttemptPhase.DRAINING;
            publish();
        }
        return drainAndStopRetirement()
            .then(loop.call(() -> reconcile(current.compiled.affectedUnits())))
            .then(loop.call(this::retire))
            .then(loop.call(() -> {
                phase = AttemptPhase.SETTLED;
                publish();
                return Mono.just(published.current());
            }));
    }

    private Mono<Void> drainAndStopRetirement() {
        if (retirement == null) return Mono.empty();
        var batch = retirement;
        var reverse = batch.previous.compiled.reverseDependency().stream().filter(batch.units::containsKey).toList();
        return Flux.fromIterable(reverse).concatMap(key -> lifecycle(batch.units.get(key), true))
            .then(loop.call(() -> { phase = AttemptPhase.STOPPING; publish(); return Mono.empty(); }))
            .thenMany(Flux.fromIterable(reverse).concatMap(key -> lifecycle(batch.units.get(key), false))).then();
    }

    private Mono<Void> lifecycle(RuntimeUnitGeneration unit, boolean drain) {
        return loop.call(() -> {
            var operation = identity(drain ? "drain" : "stop");
            var deadline = Instant.now().plus(lifecycleTimeout);
            var action = drain ? unit.drainAsync(operation, deadline) : unit.stopAsync(operation, deadline);
            return action.timeout(lifecycleTimeout).switchIfEmpty(Mono.error(
                new IllegalStateException("runtime lifecycle returned no observation")))
                .then();
        });
    }

    private Mono<PublishedView> reconcile(Set<ExecutionUnitKey> requested) {
        if (current == null) return Mono.just(published.current());
        var closure = DeploymentPlanner.closure(requested, current.compiled);
        if (closure.stream().map(current.units::get)
            .noneMatch(unit -> observe(unit).aggregateState() == ExecutionObservation.State.PENDING)) {
            return Mono.just(published.current());
        }
        phase = AttemptPhase.RECONCILING;
        publish();
        return Flux.fromIterable(current.compiled.dependencyFirst())
            .filter(closure::contains)
            .concatMap(key -> loop.call(() -> {
                var unit = current.units.get(key);
                if (observe(unit).aggregateState() != ExecutionObservation.State.PENDING) return Mono.empty();
                if (unit.plan().dependencies().stream().anyMatch(dependency ->
                    observe(current.units.get(dependency)).aggregateState() != ExecutionObservation.State.ACTIVE)) return Mono.empty();
                return unit.reconcileAsync(identity("activate"))
                    .timeout(lifecycleTimeout)
                    .switchIfEmpty(Mono.error(new IllegalStateException("runtime activation returned no observation")))
                    .doOnNext(observed -> {
                        if (observed.aggregateState() == ExecutionObservation.State.FAILED) failure = "execution failed: " + key;
                    }).then().onErrorResume(error -> {
                        // driver 应把普通启动失败转为 FAILED；异常信号是 SPI 契约破坏。
                        fatal("RUNTIME_ACTIVATION_CONTRACT_VIOLATION", error);
                        return Mono.error(error);
                    }).doOnSuccess(ignored -> publish());
            }))
            .then(loop.call(() -> {
                phase = AttemptPhase.SETTLED;
                publish();
                return Mono.just(published.current());
            }));
    }

    private Mono<Void> retire() {
        if (retirement == null) return Mono.empty();
        phase = AttemptPhase.RELEASING;
        publish();
        var retiringOwners = new LinkedHashSet<PreparedRuntimeGeneration>();
        retirement.previous.compiled.reverseDependency().stream().filter(retirement.units::containsKey)
            .forEach(key -> retiringOwners.add(retirement.previous.owners.get(key)));
        retiringOwners.removeAll(current.owners.values());
        return Flux.fromIterable(retiringOwners).concatMap(owner ->
                loop.call(() -> owner.retireAsync().timeout(lifecycleTimeout)))
            .then(loop.call(() -> {
                retirement.units.values().forEach(lastObservations::remove);
                retirement = null;
                return Mono.empty();
            }));
    }

    private Mono<PublishedView> deploymentFailed(Throwable error) {
        if (error instanceof MutationGateClosedException) return Mono.error(error);
        failure = error.toString();
        if (error instanceof DeploymentTargetStore.SaveUnconfirmedException || saveState == TargetSaveState.UNCONFIRMED) {
            durableState = DurableTargetState.UNCERTAIN;
            saveState = TargetSaveState.UNCONFIRMED;
            fatal("TARGET_SAVE_UNCERTAIN", error);
            return Mono.error(new EngineChangeException(published.current(), saveState, error));
        }
        if (candidate == null) {
            if (retirement != null || phase == AttemptPhase.PROMOTING
                || phase == AttemptPhase.DRAINING || phase == AttemptPhase.STOPPING || phase == AttemptPhase.RELEASING) {
                fatal("LIFECYCLE_CLEANUP_FAILED", error);
            } else { phase = AttemptPhase.FAILED; publish(); }
            return Mono.error(new EngineChangeException(published.current(), saveState, error));
        }
        var staged = candidate;
        return cleanupCandidate(staged).then(loop.call(() -> {
            candidate = null;
            candidateId = null;
            phase = AttemptPhase.FAILED;
            publish();
            return Mono.<PublishedView>error(new EngineChangeException(published.current(), saveState, error));
        })).onErrorResume(cleanup -> {
            if (cleanup instanceof EngineChangeException) return Mono.error(cleanup);
            error.addSuppressed(cleanup);
            cleanupFailures.add(cleanup.toString());
            fatal("CANDIDATE_CLEANUP_FAILED", error);
            return Mono.error(new EngineChangeException(published.current(), saveState, error));
        });
    }

    private Mono<Void> cleanupCandidate(DeploymentCandidate staged) {
        var actions = new ArrayList<Supplier<Mono<Void>>>();
        for (var entry : new ArrayList<>(staged.candidates().entrySet()).reversed()) {
            var sealed = staged.generations().get(entry.getKey());
            actions.add(sealed == null ? entry.getValue()::closeAsync : sealed::abortAsync);
        }
        return cleanupAll(actions);
    }

    private Mono<Void> cleanupAll(List<Supplier<Mono<Void>>> actions) {
        var failures = new ArrayList<Throwable>();
        return Flux.fromIterable(actions).concatMap(action -> loop.call(action).timeout(lifecycleTimeout)
                .onErrorResume(error -> { failures.add(error); return Mono.empty(); }))
            .then(loop.call(() -> {
                if (failures.isEmpty()) return Mono.empty();
                var result = new IllegalStateException("runtime cleanup failed", failures.getFirst());
                failures.stream().skip(1).forEach(result::addSuppressed);
                return Mono.error(result);
            }));
    }

    private void fatal(String reason, Throwable error) {
        mutationGate = false;
        admissionOpen = false;
        state = EngineState.FAILED;
        failure = error.toString();
        var failedPhase = phase;
        phase = AttemptPhase.FAILED;
        var owned = Collections.newSetFromMap(new IdentityHashMap<RuntimeUnitGeneration, Boolean>());
        if (current != null) owned.addAll(current.units.values());
        if (retirement != null) owned.addAll(retirement.units.values());
        if (candidate != null) candidate.generations().values().forEach(value -> owned.addAll(value.units().values()));
        for (var unit : owned) {
            try { unit.closeAdmission(); }
            catch (RuntimeException | Error violation) { cleanupFailures.add(violation.toString()); }
        }
        publish();
        if (terminationRequest == null) {
            terminationRequest = new HostTerminationRequest(hostInstanceId, reason, failedPhase.name(),
                durableTarget == null ? OptionalLong.empty() : OptionalLong.of(durableTarget.targetRevision()));
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
        if (candidate != null) {
            return cleanupCandidate(candidate).then(loop.call(() -> {
                candidate = null;
                return shutdown();
            }));
        }
        if (retirement != null) return Mono.error(new IllegalStateException(
            "retirement cleanup failed; resource ownership retained for Host termination"));
        if (current != null) {
            retirement = new Retirement(current, current.units);
            current.compiled.reverseDependency().forEach(key -> current.units.get(key).closeAdmission());
            var old = current;
            current = null;
            phase = AttemptPhase.DRAINING;
            publish();
            var owners = Collections.newSetFromMap(new IdentityHashMap<PreparedRuntimeGeneration, Boolean>());
            owners.addAll(old.owners.values());
            return drainAndStopRetirement().thenMany(Flux.fromIterable(owners)
                .concatMap(owner -> loop.call(() -> owner.retireAsync().timeout(lifecycleTimeout)))).then(loop.call(() -> {
                    old.units.values().forEach(lastObservations::remove);
                    retirement = null;
                    return shutdown();
                })).onErrorResume(error -> {
                    fatal("HOST_CLOSE_FAILED", error);
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
            phase = AttemptPhase.SETTLED;
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
        var unitFacts = observations(current == null ? Map.of() : current.units);
        var retiredFacts = observations(retirement == null ? Map.of() : retirement.units);
        var snapshot = new EngineSnapshot(state, hostInstanceId, durableState, Optional.ofNullable(durableTarget),
            candidate == null ? Optional.empty() : Optional.of(new AttemptSnapshot(candidateId, AttemptRole.CANDIDATE,
                phase, candidate.target().targetRevision(),
                candidate.compiled() == null ? null : candidate.compiled().compiledFingerprint(), candidate.ownedUnitKeys())),
            current == null ? Optional.empty() : Optional.of(new AttemptSnapshot(current.id, AttemptRole.CURRENT, phase,
                current.token.targetRevision(), current.compiled.compiledFingerprint(), current.units.keySet())),
            retiredFacts, unitFacts, failure);
        var diagnostics = new EngineDiagnostics(phase, saveState,
            targetSatisfied(unitFacts), mutationGate,
            admissionOpen, cleanupFailures, Optional.ofNullable(terminationRequest), failure);
        var domainSnapshot = domain.snapshot();
        var runtimeDiagnostics = RuntimeDiagnostics.builder().domainName(domainSnapshot.name())
            .plugins(domainSnapshot.plugins()).services(domainSnapshot.services()).events(domainSnapshot.events())
            .cleanupFailures(domainSnapshot.cleanupFailures()).failure(failure).build();
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

    private Map<ExecutionUnitKey, ExecutionObservation> observations(Map<ExecutionUnitKey, RuntimeUnitGeneration> units) {
        var result = new LinkedHashMap<ExecutionUnitKey, ExecutionObservation>();
        units.forEach((key, unit) -> {
            var observation = observationFailure == null ? observe(unit) : lastObservations.get(unit);
            if (observation != null) result.put(key, observation);
        });
        return result;
    }
    private ExecutionObservation observe(RuntimeUnitGeneration unit) {
        try {
            var observation = Objects.requireNonNull(unit.snapshot(), "runtime unit snapshot");
            lastObservations.put(unit, observation);
            return observation;
        } catch (RuntimeException | Error violation) {
            observationFailure = violation;
            fatal("RUNTIME_SNAPSHOT_CONTRACT_VIOLATION", violation);
            throw violation;
        }
    }
    private boolean targetSatisfied(
        Map<ExecutionUnitKey, ExecutionObservation> observations) {
        if (observationFailure != null) return false;
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
                    var observation = observe(unit);
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
                if (failed.isEmpty()) return reconcile(notFailed).then();
                return deploy(durableTarget, false, false, failed)
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
                        return stillCurrent.isEmpty() ? Mono.empty()
                            : reconcile(stillCurrent).then();
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
                return deploy(replacement, true, false, Set.of());
            }).subscribe(ignored -> { }, error -> LOG.debug(
                "Runtime unit disable failed: {}", request.reason(), error));
        }
        public void requestRecompile(RuntimeRecompileReason reason) {
            loop.submit(() -> {
                ensureMutable();
                return durableTarget == null ? Mono.empty() : deploy(durableTarget, false, true, Set.of());
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
    private record Attempt(String id, DurableTargetToken token, CompiledDeployment compiled,
                           Map<ExecutionUnitKey, RuntimeUnitGeneration> units,
                           Map<ExecutionUnitKey, PreparedRuntimeGeneration> owners, String inputsIdentity) { }
    private record Retirement(Attempt previous, Map<ExecutionUnitKey, RuntimeUnitGeneration> units) { }

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
