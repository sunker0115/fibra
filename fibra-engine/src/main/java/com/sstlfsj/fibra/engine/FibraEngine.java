package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.FibraException;
import com.sstlfsj.fibra.ManagedPluginControl;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstance;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.Scope;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactInstallTransaction;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.bridge.ContributionCall;
import com.sstlfsj.fibra.bridge.ContributionDirectory;
import com.sstlfsj.fibra.bridge.ContributionDirectoryView;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionRoutes;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.bridge.ContributionSnapshot;
import com.sstlfsj.fibra.config.ConfigDiagnostic;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.ConfigStage;
import com.sstlfsj.fibra.config.DesiredCompilation;
import com.sstlfsj.fibra.config.DesiredEvaluation;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.DesiredInputGraph.EffectiveDesiredEntry;
import com.sstlfsj.fibra.config.DesiredSourceSnapshot;
import com.sstlfsj.fibra.config.DesiredStateRepository;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import com.sstlfsj.fibra.runtime.PluginUpdate;
import com.sstlfsj.fibra.runtime.RuntimeDomain;
import com.sstlfsj.fibra.runtime.RuntimeDomainSnapshot;
import com.sstlfsj.fibra.value.LiteralValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class FibraEngine implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(FibraEngine.class);
    private final DesiredStateRepository desiredRepository;
    private final InitialArtifactSource initialArtifacts;
    private final ArtifactStore artifactStore;
    private final EngineStateStore stateStore;
    private final HostServiceRegistry hostServices;
    private final FibraRuntime runtime = FibraRuntime.create();
    private final EngineCommandLoop loop = new EngineCommandLoop();
    private final RuntimeResources resources;
    private final DesiredSourceMonitor sourceMonitor;
    private final ContributionDirectory directory = new ContributionDirectory();
    private final Map<String, Managed<?>> instances = new LinkedHashMap<>();
    private final Sinks.Many<PublishedView> views = Sinks.many().multicast().directBestEffort();
    private final AtomicReference<PublishedState> publishedState = new AtomicReference<>();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicBoolean sourceRefreshQueued = new AtomicBoolean();
    private final AtomicBoolean sourceDirty = new AtomicBoolean();
    private final AtomicReference<Throwable> sourceMonitorFailure = new AtomicReference<>();
    private volatile Mono<PublishedView> startSignal;
    private final Mono<Void> closeSignal;

    private RuntimeDomain domain;
    private reactor.core.Disposable observation;
    private DesiredCompilation compilation = DesiredCompilation.builder()
        .snapshot(new DesiredSourceSnapshot("unstarted", "0", Set.of()))
        .graph(new DesiredInputGraph(List.of())).build();
    private ConfigContextSnapshot configContext;
    private DesiredEvaluation evaluation;
    private Map<ArtifactId, ArtifactRecord> artifacts = Map.of();
    private String targetRevision;
    private ChangePhase phase = ChangePhase.IDLE;
    private EngineState state = EngineState.NEW;
    private Set<String> affected = Set.of();
    private boolean mutationGate = true;
    private boolean sourceBaselinePending;
    private boolean sourceFailure;
    private ChangePhase failedPhase;
    private TargetSaveState targetSaveState = TargetSaveState.NOT_APPLICABLE;
    private List<String> cleanupFailures = List.of();
    private String failure;
    private String lastSourceRevision;

    private final PublishedRuntime published = new PublishedRuntime() {
        @Override public PublishedView current() { return publishedState.get().view(); }
        @Override public Flux<PublishedView> views() {
            return views.asFlux().onBackpressureLatest()
                .publishOn(Schedulers.boundedElastic(), 1).onBackpressureLatest();
        }
        @Override public <D, I, O> Mono<O> invoke(String revision,
                long registrationIdentity,
                ContributionKind<D, I, O> kind, ContributionId id, I input) {
            return invokePublished(revision, registrationIdentity, kind, id, input)
                .publishOn(Schedulers.boundedElastic());
        }
    };

    private FibraEngine(Builder builder) {
        desiredRepository = builder.desiredRepository;
        initialArtifacts = builder.initialArtifacts;
        artifactStore = builder.artifactStore;
        stateStore = builder.stateStore;
        hostServices = builder.hostServices;
        configContext = builder.configContext;
        evaluation = DesiredEvaluation.evaluate(compilation.graph(), configContext);
        resources = new RuntimeResources(builder.runtimeAdapters, builder.catalog);
        sourceMonitor = builder.autoRefreshInterval == null ? null
            : new DesiredSourceMonitor(builder.autoRefreshInterval);
        closeSignal = Mono.defer(() -> {
            closeRequested.set(true);
            var stopMonitor = sourceMonitor == null ? Mono.<Void>empty()
                : sourceMonitor.closeAsync().onErrorResume(error -> {
                    sourceMonitorFailure.compareAndSet(null, error);
                    return Mono.empty();
                });
            return loop.quiesce().then(stopMonitor).then(loop.call(this::closeInternal));
        }).then(Mono.defer(loop::closeAsync))
            .onErrorResume(error -> loop.closeAsync().then(Mono.error(error))).cache();
        publishEmpty();
        startSignal = Mono.defer(() -> loop.submit(() -> Mono.defer(this::bootstrap)
            .onErrorResume(error -> {
                if (error instanceof EngineChangeException) return Mono.error(error);
                state = EngineState.FAILED;
                failedPhase = ChangePhase.PREPARING;
                cleanupFailures = List.of();
                phase = ChangePhase.FAILED;
                mutationGate = false;
                failure = error.toString();
                return capture().flatMap(captured -> {
                    publish(captured);
                    return Mono.error(error);
                });
            }))).doOnSuccess(view -> {
                // 启动等待者接收本次精确结果；后续订阅读当前视图且不长期保留启动视图。
                startSignal = Mono.fromSupplier(published::current);
            })
            .doOnError(error -> {
                // 正在等待首次启动的订阅者仍收到原异常；后续订阅只保留失败事实。
                var detail = error.toString();
                if (error instanceof EngineChangeException change) {
                    var targetSaveState = change.targetSaveState();
                    startSignal = Mono.defer(() -> Mono.error(new EngineChangeException(
                        published.current(), targetSaveState, new IllegalStateException(detail))));
                } else {
                    startSignal = Mono.defer(() -> Mono.error(new IllegalStateException(detail)));
                }
            }).cache();
    }

    public static Builder builder(DesiredStateRepository repository) { return new Builder(repository); }
    public PublishedRuntime published() { return published; }
    public Mono<PublishedView> start() {
        return Mono.defer(() -> startSignal);
    }

    public Mono<EngineCommandResult> submit(EngineCommand command) {
        Objects.requireNonNull(command, "command");
        return Mono.defer(() -> loop.submit(() -> {
            if (state == EngineState.NEW) return Mono.error(new IllegalStateException("engine is not started"));
            if (!mutationGate) return Mono.error(new MutationGateClosedException());
            checkRevision(command.expectedRevision());
            return change(command);
        }));
    }

    private Mono<PublishedView> bootstrap() {
        if (closeRequested.get()) return Mono.error(new IllegalStateException("engine is closing"));
        var saved = stateStore.load();
        targetSaveState = saved.isPresent()
            ? TargetSaveState.SAVED : TargetSaveState.NOT_SAVED;
        var target = new LinkedHashMap<ArtifactId, ArtifactRecord>();
        DesiredCompilation desired;
        List<DeploymentArtifact> bootstrapArtifacts = List.of();
        if (saved.isPresent()) {
            var manifest = saved.get();
            desired = compilation(manifest.desiredGraph(), manifest.revision());
            targetRevision = manifest.revision();
            compilation = desired;
            manifest.artifacts().forEach((id, revision) -> {
                if (artifactStore == null) throw new IllegalStateException("saved target requires an artifact store");
                target.put(id, artifactStore.find(id, revision).orElseThrow(() ->
                    new IllegalStateException("saved artifact is missing: " + id.value() + "@" + revision)));
            });
            artifacts = Map.copyOf(target);
        } else {
            desired = loadDesired();
            bootstrapArtifacts = validatedArtifacts(initialArtifacts.load());
        }
        domain = runtime.openDomain("engine");
        var context = domain.rootScope().context();
        hostServices.freeze().forEach(binding -> provideHostBinding(context, binding));
        context.services().provide(ManagedPluginControl.KEY, this::requestDisable);
        context.services().provide(ContributionServices.REGISTRAR, directory);
        observation = Flux.merge(domain.snapshots(), directory.views())
            .subscribe(ignored -> loop.observe(this::refresh));
        var plan = new ChangeSet(desired, target, configContext);
        plan.bootstrapping = true;
        plan.targetSaveState = saved.isPresent()
            ? TargetSaveState.SAVED : TargetSaveState.NOT_SAVED;
        plan.committed = saved.isPresent();
        var initialSelection = bootstrapArtifacts;
        return Mono.defer(() -> {
            if (saved.isEmpty()) stage(plan, initialSelection);
            return execute(plan);
        }).onErrorResume(error -> plan.executing ? Mono.error(error) : fail(plan, error))
            .map(EngineCommandResult::view).doOnSuccess(view -> {
            if (sourceMonitor == null) return;
            if (saved.isEmpty()) acceptSource(desired);
            else {
                sourceBaselinePending = true;
                seedSourceObservation();
            }
            sourceMonitor.start(this::desiredSourceDirty);
        });
    }

    private Mono<EngineCommandResult> change(EngineCommand command) {
        if (command instanceof RefreshDesired) return refreshDesired(true);
        if (command instanceof ReplaceConfigContext replace) return replaceConfigContext(replace);
        var desired = compilation;
        if (command instanceof ReplaceDesiredGraph replace) {
            checkDesiredRevision(replace.expectedDesiredRevision());
            desired = replacementCompilation(replace.graph());
        }
        if (command instanceof ApplyDeployment deployment) {
            checkDesiredRevision(deployment.expectedDesiredRevision());
            desired = replacementCompilation(deployment.graph());
        }
        Map<ArtifactId, ArtifactRecord> selection = command instanceof ApplyDeployment
            ? Map.of() : artifacts;
        var plan = new ChangeSet(desired, selection, configContext);
        return Mono.defer(() -> {
            if (command instanceof UninstallArtifact uninstall) {
                if (plan.artifacts.remove(uninstall.artifactId()) == null) {
                    throw new IllegalArgumentException("artifact is not selected: " + uninstall.artifactId().value());
                }
            } else if (command instanceof InstallArtifact install) {
                stage(plan, install.artifactId(), install.runtimeId(), install.version(), install.source());
            } else if (command instanceof ApplyDeployment deployment) {
                stage(plan, validatedArtifacts(deployment.artifacts()));
            }
            return execute(plan);
        }).onErrorResume(error -> plan.executing ? Mono.error(error) : fail(plan, error));
    }

    private void requestDisable(PluginInstance<?> instance) {
        Objects.requireNonNull(instance, "instance");
        if (closeRequested.get()) return;
        loop.submit(() -> disable(instance)).subscribe(ignored -> { }, error -> {
            if (!closeRequested.get()) {
                LOGGER.warn("Plugin disable request failed for {}", instance.id(), error);
            }
        });
    }

    private Mono<EngineCommandResult> disable(PluginInstance<?> instance) {
        if (closeRequested.get() || !mutationGate || !evaluationCurrent()) return Mono.empty();
        var managed = instances.get(instance.id());
        if (managed == null || managed.instance() != instance) {
            return Mono.empty();
        }
        var state = instance.state();
        if (state == PluginInstanceState.STARTING) return retryDisableAfterSettled(instance);
        if (state != PluginInstanceState.ACTIVE) return Mono.empty();
        var entry = compilation.graph().plugins().get(instance.id());
        if (entry == null || !entry.enabled() || !evaluation.require(instance.id()).effective().enabled()) {
            return Mono.empty();
        }
        state = instance.state();
        if (state == PluginInstanceState.STARTING) return retryDisableAfterSettled(instance);
        if (state != PluginInstanceState.ACTIVE) return Mono.empty();
        var plan = new ChangeSet(replacementCompilation(
            compilation.graph().withEnabled(instance.id(), false)), artifacts, configContext);
        return execute(plan);
    }

    private Mono<EngineCommandResult> retryDisableAfterSettled(PluginInstance<?> instance) {
        instance.settled().subscribe(ignored -> requestDisable(instance), ignored -> { });
        return Mono.empty();
    }

    private boolean evaluationCurrent() {
        return evaluation != null && evaluation.graph().equals(compilation.graph())
            && evaluation.context().equals(configContext);
    }

    private Mono<EngineCommandResult> replaceConfigContext(ReplaceConfigContext replace) {
        checkContextRevision(replace.expectedContextRevision());
        final DesiredEvaluation nextEvaluation;
        final Map<String, Bound<?>> nextBound;
        try {
            nextEvaluation = DesiredEvaluation.evaluate(compilation.graph(), replace.context());
            nextBound = bind(compilation, nextEvaluation, resources.catalog());
        } catch (RuntimeException | Error error) {
            return Mono.error(error);
        }
        var plan = new ChangeSet(compilation, artifacts, replace.context());
        plan.evaluation = nextEvaluation;
        plan.bound = nextBound;
        plan.saveTarget = false;
        plan.targetSaveState = TargetSaveState.NOT_APPLICABLE;
        plan.updateResources = false;
        return execute(plan);
    }

    private Mono<EngineCommandResult> refreshDesired(boolean force) {
        return Mono.defer(() -> {
            final DesiredCompilation desired;
            try {
                desired = loadDesired();
            } catch (RuntimeException | Error error) {
                return failSourceRefresh(error);
            }
            sourceMonitorUpdate(desired);
            final DesiredEvaluation nextEvaluation;
            try {
                nextEvaluation = DesiredEvaluation.evaluate(desired.graph(), configContext);
            } catch (RuntimeException | Error error) {
                return failSourceRefresh(error);
            }
            if (sourceBaselinePending && !force) {
                acceptSource(desired);
                return unchangedSource(desired);
            }
            if (!force && desired.snapshot().revision().equals(lastSourceRevision)) {
                return unchangedSource(desired);
            }
            var plan = new ChangeSet(desired, artifacts, configContext);
            plan.evaluation = nextEvaluation;
            return execute(plan).doOnSuccess(ignored -> acceptSource(desired))
                .doOnError(error -> {
                    if (error instanceof EngineChangeException change
                        && change.targetSaveState() == TargetSaveState.SAVED) {
                        acceptSource(desired);
                    }
                });
        });
    }

    private Mono<EngineCommandResult> failSourceRefresh(Throwable error) {
        return loop.call(() -> {
            sourceFailure = true;
            failedPhase = ChangePhase.PREPARING;
            targetSaveState = TargetSaveState.NOT_APPLICABLE;
            cleanupFailures = List.of();
            failure = error.toString();
            phase = ChangePhase.FAILED;
            return capture().flatMap(captured -> {
                publish(captured);
                return Mono.error(error);
            });
        });
    }

    private Mono<EngineCommandResult> unchangedSource(DesiredCompilation desired) {
        var warnings = desired.diagnostics().stream().map(ConfigDiagnostic::message).toList();
        if (!sourceFailure) {
            return Mono.just(new EngineCommandResult(publishedState.get().view(), warnings));
        }
        sourceFailure = false;
        failedPhase = null;
        targetSaveState = TargetSaveState.NOT_APPLICABLE;
        cleanupFailures = List.of();
        failure = null;
        phase = ChangePhase.IDLE;
        affected = Set.of();
        return capture().map(captured -> {
            publish(captured);
            return new EngineCommandResult(publishedState.get().view(), warnings);
        });
    }

    private void desiredSourceDirty() {
        if (closeRequested.get()) return;
        sourceDirty.set(true);
        scheduleSourceRefresh();
    }

    private void scheduleSourceRefresh() {
        if (!sourceRefreshQueued.compareAndSet(false, true)) return;
        loop.submit(() -> {
            if (!sourceDirty.getAndSet(false) || state != EngineState.RUNNING
                || !mutationGate) {
                return Mono.<Void>empty();
            }
            return refreshDesired(false).then();
        }).doFinally(ignored -> {
            sourceRefreshQueued.set(false);
            if (sourceDirty.get() && !closeRequested.get()) scheduleSourceRefresh();
        }).subscribe(ignored -> { }, error -> LOGGER.warn(
            "Automatic desired source refresh failed: {}", error.toString()));
    }

    private void seedSourceObservation() {
        try {
            var desired = loadDesired();
            DesiredEvaluation.evaluate(desired.graph(), configContext);
            acceptSource(desired);
        } catch (RuntimeException | Error error) {
            LOGGER.warn("Cannot establish desired source refresh baseline: {}",
                error.toString());
            desiredSourceDirty();
        }
    }

    private void acceptSource(DesiredCompilation desired) {
        sourceBaselinePending = false;
        lastSourceRevision = desired.snapshot().revision();
        sourceMonitorUpdate(desired);
    }

    private void sourceMonitorUpdate(DesiredCompilation desired) {
        if (sourceMonitor != null) {
            sourceMonitor.update(desired.snapshot().sources());
        }
    }

    private DesiredCompilation loadDesired() {
        var desired = desiredRepository.load();
        desired.diagnostics().forEach(diagnostic -> LOGGER.warn("{} source={} entry={}: {}",
            diagnostic.code(), diagnostic.source(), diagnostic.entryId(), diagnostic.message()));
        return desired;
    }

    private void stage(ChangeSet plan, ArtifactId id, RuntimeId runtimeId,
                       String version, java.nio.file.Path source) {
        if (artifactStore == null) throw new IllegalStateException("engine has no artifact store");
        var transaction = artifactStore.prepareInstall(id, runtimeId, version, source);
        plan.installs.add(transaction);
        plan.artifacts.put(id, transaction.candidate());
    }

    private void stage(ChangeSet plan, List<DeploymentArtifact> artifacts) {
        artifacts.forEach(artifact -> stage(plan, artifact.artifactId(),
            artifact.runtimeId(), artifact.version(), artifact.source()));
    }

    private static List<DeploymentArtifact> validatedArtifacts(
        List<DeploymentArtifact> artifacts) {
        var selection = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        var ids = new LinkedHashSet<ArtifactId>();
        for (var artifact : selection) {
            if (!ids.add(artifact.artifactId())) {
                throw new IllegalArgumentException("duplicate deployment artifact");
            }
        }
        return selection;
    }

    private Mono<EngineCommandResult> execute(ChangeSet plan) {
        plan.executing = true;
        return loop.call(() -> {
            sourceFailure = false;
            failedPhase = null;
            targetSaveState = plan.targetSaveState;
            cleanupFailures = List.of();
            if (plan.evaluation == null) {
                plan.evaluation = DesiredEvaluation.evaluate(plan.desired.graph(), plan.context);
            }
            phase = ChangePhase.PREPARING;
            plan.sourcePhase = phase;
            failure = null;
            publishControl();
            if (!plan.updateResources) return Mono.<Void>empty();
            plan.update = resources.createUpdate(plan.artifacts);
            return plan.update.prepareAsync();
        }).then(loop.call(() -> {
            if (plan.bound == null) {
                var catalog = plan.update == null ? resources.catalog() : plan.update.catalog();
                plan.bound = bind(plan.desired, plan.evaluation, catalog);
            }
            var changed = new LinkedHashSet<String>();
            instances.forEach((id, managed) -> {
                var next = plan.bound.get(id);
                if (next == null || !sameInput(managed.bound(), next)) changed.add(id);
            });
            plan.bound.keySet().stream().filter(id -> !instances.containsKey(id)).forEach(changed::add);
            affected = Set.copyOf(changed);
            if (plan.saveTarget) {
                phase = ChangePhase.SAVING;
                plan.sourcePhase = phase;
                publishControl();
                plan.installs.forEach(transaction -> {
                    var saved = transaction.save();
                    plan.artifacts.put(saved.id(), saved);
                });
                var selections = new LinkedHashMap<ArtifactId, String>();
                plan.artifacts.forEach((id, record) -> selections.put(id, record.revision()));
                var manifest = new DeploymentManifest(selections, plan.desired.graph());
                if (plan.targetSaveState != TargetSaveState.SAVED) stateStore.save(manifest);
                plan.targetSaveState = TargetSaveState.SAVED;
                targetSaveState = plan.targetSaveState;
                targetRevision = manifest.revision();
            } else if (!plan.installs.isEmpty()) {
                throw new IllegalStateException("context-only change cannot install artifacts");
            }
            compilation = plan.desired;
            configContext = plan.context;
            evaluation = plan.evaluation;
            artifacts = Map.copyOf(plan.artifacts);
            plan.committed = true;
            phase = ChangePhase.RECONCILING;
            plan.sourcePhase = phase;
            publishControl();
            return reconcile(plan);
        })).then(loop.call(() -> domain.settled()))
            .then(loop.call(() -> {
                assertClean();
                phase = ChangePhase.RETIRING;
                plan.sourcePhase = phase;
                refresh();
                if (plan.update == null) {
                    plan.retired = true;
                    return Mono.<Void>empty();
                }
                return plan.update.closeAsync().doOnSuccess(ignored -> plan.retired = true);
            })).then(loop.call(() -> capture().map(captured -> {
                plan.sourcePhase = ChangePhase.RECONCILING;
                phase = ChangePhase.IDLE;
                affected = Set.of();
                state = EngineState.RUNNING;
                publish(captured);
                var view = publishedState.get().view();
                if (!view.engineDiagnostics().targetSatisfied()) {
                    var error = new IllegalStateException("deployment target requirements are not satisfied");
                    view.engine().instances().forEach((id, fact) -> {
                        if (fact.state() == PluginInstanceState.FAILED) {
                            instances.get(id).instance().failure().ifPresent(error::addSuppressed);
                        }
                    });
                    throw error;
                }
                return new EngineCommandResult(view, plan.desired.diagnostics().stream()
                    .map(ConfigDiagnostic::message).toList());
            }))).onErrorResume(error -> fail(plan, error));
    }

    private Mono<Void> reconcile(ChangeSet plan) {
        var retiring = instances.entrySet().stream().filter(entry -> {
            var next = plan.bound.get(entry.getKey());
            return next == null || mustRemount(entry.getValue().bound(), next);
        }).map(Map.Entry::getKey).toList();
        // Scope owns the instance and nested resources; disposing it waits on contribution effects.
        return Flux.fromIterable(retiring).concatMap(id -> loop.call(() -> {
            var managed = instances.get(id);
            return managed.scope().closeAsync().then(loop.call(() -> {
                assertClean();
                instances.remove(id);
                refresh();
                return Mono.empty();
            }));
        })).then(loop.call(() -> {
            assertClean();
            if (plan.update != null) plan.update.adopt();
            var existing = plan.bound.entrySet().stream()
                .map(entry -> new ExistingTarget(entry.getKey(),
                    instances.get(entry.getKey()), entry.getValue()))
                .filter(target -> target.previous() != null)
                .toList();
            var updating = existing.stream()
                .filter(target -> !sameInput(target.previous().bound(), target.next()))
                .toList();
            var updates = updating.stream().map(target -> preparedUpdate(
                target.previous(), target.next())).toArray(PluginUpdate<?>[]::new);
            return domain.updateBatch(updates).onErrorResume(error ->
                    error instanceof FibraException failure
                        && FibraException.PLUGIN_BATCH_UPDATE_FAILED.equals(failure.code())
                        ? Mono.empty() : Mono.error(error))
                .then(loop.call(() -> {
                    existing.forEach(target -> instances.put(target.id(),
                        target.previous().withBound(target.next())));
                    refresh();
                    return Flux.fromIterable(plan.bound.entrySet())
                        .filter(entry -> !instances.containsKey(entry.getKey()))
                        .concatMap(entry -> loop.call(() -> {
                            var scope = domain.rootScope().openChild("plugin:" + entry.getKey());
                            try {
                                var managed = mount(scope, entry.getValue());
                                instances.put(entry.getKey(), managed);
                            } catch (RuntimeException | Error error) {
                                return scope.closeAsync().then(Mono.error(error));
                            }
                            refresh();
                            return Mono.empty();
                        })).then();
                }));
        }));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PluginUpdate<?> preparedUpdate(Managed<?> previous, Bound<?> next) {
        return PluginUpdate.prepared((PluginInstance) previous.instance(), next.prepared());
    }

    private Map<String, Bound<?>> bind(DesiredCompilation desired, DesiredEvaluation evaluation,
                                       PluginCatalog catalog) {
        var result = new LinkedHashMap<String, Bound<?>>();
        desired.graph().plugins().forEach((id, input) -> {
            var resolved = evaluation.require(id);
            var effective = resolved.effective();
            if (!effective.enabled()) return;
            var config = resolved.resolvedConfig().orElseThrow();
            var contract = catalog.find(input.definitionName()).orElseThrow(() ->
                bindingFailure(desired, id, "DEFINITION_NOT_FOUND", "unknown definition " + input.definitionName(), null));
            var previous = instances.get(id);
            if (previous != null && previous.bound().prepared().definition() == contract.definition()
                && previous.bound().config().equals(config)) {
                result.put(id, previous.bound().withDeclaration(input, config, effective));
                return;
            }
            try { result.put(id, bound(input, config, effective, contract)); }
            catch (RuntimeException error) {
                throw bindingFailure(desired, id, "CONFIG_BIND_FAILED", "cannot bind " + input.definitionName(), error);
            }
        });
        return java.util.Collections.unmodifiableMap(result);
    }

    private static DesiredBindingException bindingFailure(DesiredCompilation desired, String id,
            String code, String message, Throwable error) {
        return new DesiredBindingException(new ConfigDiagnostic(ConfigStage.COMPILE, code, message,
            desired.entrySources().get(id), id), error);
    }

    private static <C> Bound<C> bound(DesiredInputEntry input, LiteralValue config,
                                     EffectiveDesiredEntry effective,
                                     PluginCatalogEntry<C> contract) {
        return new Bound<>(input, config, effective, contract.bind(config));
    }

    private static boolean mustRemount(Bound<?> old, Bound<?> next) {
        return old.prepared().definition() != next.prepared().definition()
            || !old.effective().realms().equals(next.effective().realms())
            || !old.effective().intercepts().equals(next.effective().intercepts())
            || !Objects.equals(old.effective().parentId(), next.effective().parentId());
    }

    private static boolean sameInput(Bound<?> old, Bound<?> next) {
        return !mustRemount(old, next) && old.config().equals(next.config());
    }

    private static <C> Managed<C> mount(Scope scope, Bound<C> bound) {
        var instance = context(scope.context(), bound.effective()).plugins()
            .mount(bound.effective().entryId(), bound.prepared());
        return new Managed<>(scope, instance, bound);
    }

    private Mono<EngineCommandResult> fail(ChangeSet plan, Throwable error) {
        sourceFailure = false;
        boolean unconfirmed = error instanceof EngineStateStore.SaveUnconfirmedException;
        if (plan.sourcePhase == ChangePhase.RETIRING) {
            plan.cleanupFailures.addAll(cleanupFailureFacts(error));
        }
        if (unconfirmed || (plan.committed && !plan.retired)) mutationGate = false;
        var cleanup = (plan.committed || unconfirmed) ? Mono.<Void>empty()
            : (plan.update == null ? Mono.<Void>empty() : plan.update.closeAsync())
                .then(Mono.fromRunnable(() -> plan.installs.forEach(ArtifactInstallTransaction::rollback)));
        return cleanup.onErrorResume(closeFailure -> {
            mutationGate = false;
            plan.cleanupFailures.addAll(cleanupFailureFacts(closeFailure));
            if (closeFailure != error) error.addSuppressed(closeFailure);
            return Mono.empty();
        }).then(loop.call(() -> {
            failedPhase = plan.sourcePhase;
            targetSaveState = targetSaveState(plan, error);
            cleanupFailures = List.copyOf(new LinkedHashSet<>(plan.cleanupFailures));
            failure = "[" + plan.sourcePhase + "] " + error;
            phase = ChangePhase.FAILED;
            if (plan.bootstrapping || domain == null || state == EngineState.NEW) state = EngineState.FAILED;
            return capture().flatMap(captured -> {
                publish(captured);
                return Mono.error(new EngineChangeException(
                    publishedState.get().view(), targetSaveState(plan, error), error));
            });
        }));
    }

    private void checkRevision(String expected) {
        var actual = publishedState.get().view().viewRevision();
        if (expected != null && !expected.equals(actual)) throw new PublishedRevisionConflictException(expected, actual);
    }

    private void checkDesiredRevision(String expected) {
        if (!Objects.equals(expected, compilation.snapshot().revision())) {
            throw new IllegalArgumentException("desired revision conflict: expected " + expected
                + ", actual " + compilation.snapshot().revision());
        }
    }

    private void checkContextRevision(String expected) {
        if (!expected.equals(configContext.revision())) {
            throw new IllegalArgumentException("context revision conflict: expected " + expected
                + ", actual " + configContext.revision());
        }
    }

    private static DesiredCompilation compilation(DesiredInputGraph graph, String revision) {
        return DesiredCompilation.builder().graph(graph)
            .snapshot(new DesiredSourceSnapshot("deployment-target", revision == null ? "0" : revision, Set.of()))
            .build();
    }

    private static DesiredCompilation replacementCompilation(DesiredInputGraph graph) {
        return DesiredCompilation.builder().graph(graph).snapshot(new DesiredSourceSnapshot(
            "managed-desired", java.util.UUID.randomUUID().toString(), Set.of())).build();
    }

    private Mono<ObservedRuntime> capture() {
        return Mono.defer(() -> {
            var captured = tryCapture();
            return captured == null ? loop.nextTurn().then(Mono.defer(this::capture)) : Mono.just(captured);
        });
    }

    private ObservedRuntime tryCapture() {
        var before = directory.current();
        var snapshot = domain == null ? null : domain.snapshot();
        var after = directory.current();
        return before.snapshot().revision() == after.snapshot().revision()
            ? new ObservedRuntime(snapshot, after) : null;
    }

    private void refresh() {
        if (closeRequested.get() || domain == null) return;
        var captured = tryCapture();
        if (captured == null) loop.observe(this::refresh);
        else publish(captured);
    }

    private void publish(ObservedRuntime captured) {
        if (captured.domain() != null && !captured.domain().cleanupFailures().isEmpty()) {
            mutationGate = false;
            var observed = new LinkedHashSet<>(cleanupFailures);
            captured.domain().cleanupFailures().stream()
                .map(RuntimeDomainSnapshot.CleanupFailure::failure).forEach(observed::add);
            cleanupFailures = List.copyOf(observed);
            var cleanupFailure = "runtime cleanup failed: " + captured.domain().cleanupFailures();
            if (failure == null) failure = cleanupFailure;
            phase = ChangePhase.FAILED;
        }
        var pluginFacts = new LinkedHashMap<Long, RuntimeDomainSnapshot.Plugin>();
        if (captured.domain() != null) captured.domain().plugins().forEach(plugin -> pluginFacts.put(plugin.identity(), plugin));
        var observed = new LinkedHashMap<String, PluginInstanceSnapshot>();
        instances.forEach((id, managed) -> {
            var fact = pluginFacts.get(managed.instance().identity());
            var declared = compilation.graph().plugins().get(id);
            var input = declared == null ? managed.bound().input() : declared;
            observed.put(id, PluginInstanceSnapshot.builder().identity(managed.instance().identity())
                .instanceId(id).definitionName(managed.instance().definition().name())
                .config(managed.bound().config())
                .state(fact == null ? PluginInstanceState.DISPOSED : fact.state())
                .publicationRequirement(input.publicationRequirement())
                .failure(fact == null ? null : fact.failure()).build());
        });
        var engine = new EngineSnapshot(state, compilation.snapshot(), compilation.graph(), observed,
            artifacts, resources.snapshots(), failure);
        var diagnostic = runtimeDiagnostics(captured.domain());
        var controls = diagnostics(engine);
        var current = publishedState.get();
        if (current != null && engine.equals(current.view().engine())
            && captured.contributions().snapshot().equals(current.view().contributions())
            && diagnostic.equals(current.view().diagnostics()) && controls.equals(current.view().engineDiagnostics())) return;
        var view = PublishedView.builder().viewRevision(nextRevision()).engine(engine)
            .contributions(captured.contributions().snapshot()).diagnostics(diagnostic)
            .engineDiagnostics(controls).build();
        publishedState.set(new PublishedState(view, captured.contributions().routes()));
        views.tryEmitNext(view);
    }

    private RuntimeDiagnostics runtimeDiagnostics(RuntimeDomainSnapshot snapshot) {
        return RuntimeDiagnostics.builder().domainName(snapshot == null ? null : snapshot.name())
            .plugins(snapshot == null ? List.of() : snapshot.plugins())
            .services(snapshot == null ? List.of() : snapshot.services())
            .events(snapshot == null ? List.of() : snapshot.events())
            .cleanupFailures(snapshot == null ? List.of() : snapshot.cleanupFailures())
            .failure(failure).build();
    }

    private void assertClean() {
        var failures = domain == null ? List.of() : domain.snapshot().cleanupFailures();
        if (!failures.isEmpty()) {
            throw new IllegalStateException("runtime has unreleased resources: " + failures);
        }
    }

    private EngineDiagnostics diagnostics(EngineSnapshot snapshot) {
        var enabled = evaluation.entries().entrySet().stream()
            .filter(entry -> entry.getValue().input() instanceof DesiredInputEntry)
            .filter(entry -> entry.getValue().effective().enabled())
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());
        boolean satisfied = state == EngineState.RUNNING
            && (phase != ChangePhase.FAILED || sourceFailure)
            && targetRevision != null && snapshot.desiredGraph().equals(compilation.graph())
            && evaluation.graph().equals(compilation.graph())
            && evaluation.context().equals(configContext)
            && snapshot.artifacts().equals(artifacts) && resources.matchesTarget(artifacts)
            && snapshot.instances().keySet().equals(enabled) && enabled.stream().allMatch(id -> {
                var expected = compilation.graph().plugins().get(id);
                var resolved = evaluation.require(id);
                var actual = snapshot.instances().get(id);
                var managed = instances.get(id);
                return actual != null && actual.requirementSatisfied()
                    && actual.definitionName().equals(expected.definitionName())
                    && actual.config().equals(resolved.resolvedConfig().orElseThrow())
                    && managed != null && managed.bound().effective().equals(resolved.effective());
            });
        return EngineDiagnostics.builder().targetRevision(targetRevision)
            .contextRevision(configContext.revision()).phase(phase)
            .failedPhase(failedPhase).targetSaveState(targetSaveState)
            .affectedInstances(affected).resources(snapshot.runtimes()).targetSatisfied(satisfied)
            .cleanupFailures(cleanupFailures)
            .mutationGateOpen(mutationGate && !closeRequested.get()).failure(failure).build();
    }

    private void publishControl() {
        var current = publishedState.get();
        if (current == null) { publishEmpty(); return; }
        var view = PublishedView.builder().viewRevision(nextRevision()).engine(current.view().engine())
            .contributions(current.view().contributions()).diagnostics(current.view().diagnostics())
            .engineDiagnostics(diagnostics(current.view().engine())).build();
        publishedState.set(new PublishedState(view, current.routes()));
        views.tryEmitNext(view);
    }

    private void publishEmpty() {
        publish(new ObservedRuntime(null, directory.current()));
    }

    private String nextRevision() {
        var current = publishedState.get();
        return current == null ? "0" : Long.toString(Long.parseLong(current.view().viewRevision()) + 1);
    }

    private <D, I, O> Mono<O> invokePublished(String revision, long registrationIdentity,
                                             ContributionKind<D, I, O> kind,
                                             ContributionId id, I input) {
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        return Mono.defer(() -> {
            if (closeRequested.get()) return Mono.error(new IllegalStateException("engine is closing"));
            var current = publishedState.get();
            if (!revision.equals(current.view().viewRevision())) return Mono.error(
                new PublishedRevisionConflictException(revision, current.view().viewRevision()));
            if (domain == null) return Mono.error(new IllegalStateException("engine is not running"));
            final ContributionCall<I, O> call;
            try { call = current.routes().acquire(kind, id, registrationIdentity); }
            catch (RuntimeException error) { return Mono.error(error); }
            if (publishedState.get() != current || closeRequested.get()) {
                call.close();
                return Mono.error(new PublishedRevisionConflictException(revision, publishedState.get().view().viewRevision()));
            }
            final Scope scope;
            try { scope = domain.rootScope().openChild("invocation:" + kind.name()); }
            catch (RuntimeException | Error error) { call.close(); return Mono.error(error); }
            var cleanup = Mono.defer(scope::closeAsync)
                .then(Mono.<Void>fromRunnable(() -> {
                    var failures = domain.cleanupFailures(scope);
                    if (!failures.isEmpty()) throw new IllegalStateException("invocation cleanup failed: " + failures);
                }))
                .doOnSuccess(ignored -> call.close()).doOnError(error -> call.failCleanup(error.toString())).cache();
            return Mono.usingWhen(Mono.just(scope), value -> call.invoke(value.context(), input),
                ignored -> cleanup, (ignored, error) -> cleanup, ignored -> cleanup);
        });
    }

    private Mono<Void> closeInternal() {
        var previousFailedPhase = failedPhase;
        var previousTargetSaveState = targetSaveState;
        var previousCleanupFailures = cleanupFailures;
        var previousFailure = failure;
        phase = ChangePhase.CLOSING;
        if (previousFailure == null) {
            failedPhase = null;
            targetSaveState = TargetSaveState.NOT_APPLICABLE;
            cleanupFailures = List.of();
        }
        publishControl();
        if (observation != null) observation.dispose();
        return directory.closeAsync()
            .then(domain == null ? Mono.empty() : Mono.defer(domain::closeAsync))
            .then(Mono.defer(runtime::closeAsync))
            .then(Mono.fromRunnable(this::assertClean))
            .then(Mono.defer(resources::closeAsync))
            .then(loop.call(() -> {
                closeStores();
                var monitorFailure = sourceMonitorFailure.get();
                if (monitorFailure != null) {
                    throw new IllegalStateException(
                        "cannot close desired source monitor", monitorFailure);
                }
                instances.clear();
                state = EngineState.CLOSED;
                phase = ChangePhase.CLOSED;
                publishEmpty();
                views.tryEmitComplete();
                return Mono.<Void>empty();
            })).onErrorResume(error -> loop.call(() -> {
                state = EngineState.FAILED;
                if (previousFailure == null) {
                    failedPhase = ChangePhase.CLOSING;
                    targetSaveState = TargetSaveState.NOT_APPLICABLE;
                    cleanupFailures = cleanupFailureFacts(error);
                    failure = "engine cleanup failed: " + error;
                } else {
                    failedPhase = previousFailedPhase;
                    targetSaveState = previousTargetSaveState;
                    var combined = new LinkedHashSet<>(previousCleanupFailures);
                    combined.addAll(cleanupFailureFacts(error));
                    cleanupFailures = List.copyOf(combined);
                    failure = previousFailure;
                }
                phase = ChangePhase.FAILED;
                mutationGate = false;
                return capture().flatMap(captured -> {
                    publish(captured);
                    views.tryEmitComplete();
                    return Mono.error(error);
                });
            }));
    }

    public Mono<Void> closeAsync() { return closeSignal; }
    @Override public void close() { closeSignal.block(); }

    private void closeStores() {
        RuntimeException failure = null;
        try { stateStore.close(); }
        catch (RuntimeException error) { failure = error; }
        try { if (artifactStore != null) artifactStore.close(); }
        catch (RuntimeException error) {
            if (failure == null) failure = error;
            else if (failure != error) failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void provideHostBinding(Context context, HostServiceRegistry.Binding binding) {
        context.services().provide(binding.key(), binding.value());
    }

    private static Context context(Context initial, EffectiveDesiredEntry entry) {
        var result = initial;
        for (var policy : entry.realms().entrySet()) {
            var value = policy.getValue().value();
            if (value instanceof LiteralValue.BooleanValue flag && flag.value()) {
                result = result.withRealm(policy.getKey(), new LocalRealm(policy.getValue().ownerEntryId()));
            } else if (value instanceof LiteralValue.StringValue name) result = result.withRealm(policy.getKey(), name.value());
        }
        for (var policy : entry.intercepts().entrySet()) {
            var value = policy.getValue().value();
            if (!(value instanceof LiteralValue.NullValue)) result = result.withIntercept(policy.getKey(), value.toJava());
        }
        return result;
    }

    private static TargetSaveState targetSaveState(ChangeSet plan, Throwable error) {
        if (!plan.saveTarget) return TargetSaveState.NOT_APPLICABLE;
        if (error instanceof EngineStateStore.SaveUnconfirmedException) {
            return TargetSaveState.UNCONFIRMED;
        }
        return plan.targetSaveState;
    }

    private static List<String> cleanupFailureFacts(Throwable failure) {
        var facts = new ArrayList<String>();
        collectCleanupFailureFacts(failure, facts,
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        return List.copyOf(facts);
    }

    private static void collectCleanupFailureFacts(Throwable failure, List<String> facts,
                                                   Set<Throwable> visited) {
        if (!visited.add(failure)) return;
        facts.add(failure.toString());
        var suppressed = failure.getSuppressed();
        for (var nested : suppressed) {
            collectCleanupFailureFacts(nested, facts, visited);
        }
    }

    public static final class Builder {
        private final DesiredStateRepository desiredRepository;
        private InitialArtifactSource initialArtifacts = List::of;
        private PluginCatalog catalog = PluginCatalog.empty();
        private EngineStateStore stateStore = EngineStateStore.inMemory();
        private ArtifactStore artifactStore;
        private HostServiceRegistry hostServices = new HostServiceRegistry();
        private ConfigContextSnapshot configContext = ConfigContextSnapshot.empty();
        private Duration autoRefreshInterval;
        private final Map<RuntimeId, PluginRuntimeAdapter> runtimeAdapters = new LinkedHashMap<>();
        private Builder(DesiredStateRepository repository) { desiredRepository = Objects.requireNonNull(repository, "repository"); }
        public Builder initialArtifacts(InitialArtifactSource value) {
            initialArtifacts = Objects.requireNonNull(value); return this;
        }
        public Builder catalog(PluginCatalog value) { catalog = Objects.requireNonNull(value); return this; }
        public Builder stateStore(EngineStateStore value) { stateStore = Objects.requireNonNull(value); return this; }
        public Builder artifactStore(ArtifactStore value) { artifactStore = Objects.requireNonNull(value); return this; }
        public Builder hostServices(HostServiceRegistry value) { hostServices = Objects.requireNonNull(value); return this; }
        public Builder configContext(ConfigContextSnapshot value) {
            configContext = Objects.requireNonNull(value); return this;
        }
        public Builder autoRefresh(Duration interval) {
            if (interval == null || interval.isZero() || interval.isNegative()) {
                throw new IllegalArgumentException("auto refresh interval must be positive");
            }
            autoRefreshInterval = interval;
            return this;
        }
        public Builder runtimeAdapter(PluginRuntimeAdapter value) {
            Objects.requireNonNull(value);
            if (runtimeAdapters.putIfAbsent(value.id(), value) != null) throw new IllegalArgumentException("duplicate runtime adapter " + value.id());
            return this;
        }
        public FibraEngine build() { return new FibraEngine(this); }
    }

    private static final class ChangeSet {
        final DesiredCompilation desired;
        final LinkedHashMap<ArtifactId, ArtifactRecord> artifacts;
        final ConfigContextSnapshot context;
        final List<ArtifactInstallTransaction> installs = new ArrayList<>();
        final List<String> cleanupFailures = new ArrayList<>();
        DesiredEvaluation evaluation;
        RuntimeResources.Update update;
        Map<String, Bound<?>> bound;
        boolean saveTarget = true;
        boolean updateResources = true;
        TargetSaveState targetSaveState = TargetSaveState.NOT_SAVED;
        boolean committed;
        boolean retired;
        boolean bootstrapping;
        boolean executing;
        ChangePhase sourcePhase = ChangePhase.PREPARING;
        ChangeSet(DesiredCompilation desired, Map<ArtifactId, ArtifactRecord> artifacts,
                  ConfigContextSnapshot context) {
            this.desired = desired;
            this.artifacts = new LinkedHashMap<>(artifacts);
            this.context = context;
        }
    }

    private record Bound<C>(DesiredInputEntry input, LiteralValue config,
                            EffectiveDesiredEntry effective,
                            PluginDefinition.Prepared<C> prepared) {
        Bound<C> withDeclaration(DesiredInputEntry input, LiteralValue config,
                                 EffectiveDesiredEntry effective) {
            return new Bound<>(input, config, effective, prepared);
        }
    }
    private record Managed<C>(Scope scope, PluginInstance<C> instance, Bound<C> bound) {
        @SuppressWarnings("unchecked")
        Managed<C> withBound(Bound<?> next) { return new Managed<>(scope, instance, (Bound<C>) next); }
    }
    private record PublishedState(PublishedView view, ContributionRoutes routes) { }
    private record ObservedRuntime(RuntimeDomainSnapshot domain, ContributionDirectoryView contributions) { }

    private record ExistingTarget(String id, Managed<?> previous, Bound<?> next) { }
    private record LocalRealm(String ownerEntryId) {
        @Override public String toString() { return "local:" + ownerEntryId; }
    }
}
