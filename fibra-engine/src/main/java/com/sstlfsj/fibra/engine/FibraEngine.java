package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.Context;
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
import com.sstlfsj.fibra.config.ConfigStage;
import com.sstlfsj.fibra.config.DesiredCompilation;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.DesiredInputGraph.EffectiveDesiredEntry;
import com.sstlfsj.fibra.config.DesiredSourceSnapshot;
import com.sstlfsj.fibra.config.DesiredStateRepository;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import com.sstlfsj.fibra.runtime.RuntimeDomain;
import com.sstlfsj.fibra.runtime.RuntimeDomainSnapshot;
import com.sstlfsj.fibra.value.LiteralValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

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
    private final ArtifactStore artifactStore;
    private final EngineStateStore stateStore;
    private final HostServiceRegistry hostServices;
    private final FibraRuntime runtime = FibraRuntime.create();
    private final EngineCommandLoop loop = new EngineCommandLoop();
    private final RuntimeResources resources;
    private final ContributionDirectory directory = new ContributionDirectory();
    private final Map<String, Managed<?>> instances = new LinkedHashMap<>();
    private final Sinks.Many<PublishedView> views = Sinks.many().replay().latest();
    private final AtomicReference<PublishedState> publishedState = new AtomicReference<>();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final Mono<PublishedView> startSignal;
    private final Mono<Void> closeSignal = Mono.defer(() -> {
        closeRequested.set(true);
        return loop.quiesce().then(loop.call(this::closeInternal));
    }).then(Mono.defer(loop::closeAsync))
        .onErrorResume(error -> loop.closeAsync().then(Mono.error(error))).cache();

    private RuntimeDomain domain;
    private reactor.core.Disposable observation;
    private DesiredCompilation compilation = DesiredCompilation.builder()
        .snapshot(new DesiredSourceSnapshot("unstarted", "0", Set.of()))
        .graph(new DesiredInputGraph(List.of())).build();
    private Map<ArtifactId, ArtifactRecord> artifacts = Map.of();
    private String targetRevision;
    private ChangePhase phase = ChangePhase.IDLE;
    private EngineState state = EngineState.NEW;
    private Set<String> affected = Set.of();
    private boolean mutationGate = true;
    private String failure;

    private final PublishedRuntime published = new PublishedRuntime() {
        @Override public PublishedView current() { return publishedState.get().view(); }
        @Override public Flux<PublishedView> views() {
            return views.asFlux().publishOn(Schedulers.boundedElastic());
        }
        @Override public <D, I, O> Mono<O> invoke(String revision,
                ContributionKind<D, I, O> kind, ContributionId id, I input) {
            return invokePublished(revision, kind, id, input).publishOn(Schedulers.boundedElastic());
        }
    };

    private FibraEngine(Builder builder) {
        desiredRepository = builder.desiredRepository;
        artifactStore = builder.artifactStore;
        stateStore = builder.stateStore;
        hostServices = builder.hostServices;
        resources = new RuntimeResources(builder.runtimeAdapters, builder.catalog);
        publishEmpty();
        startSignal = Mono.defer(() -> loop.submit(() -> Mono.defer(this::bootstrap)
            .onErrorResume(error -> {
                if (error instanceof EngineChangeException) return Mono.error(error);
                state = EngineState.FAILED;
                phase = ChangePhase.FAILED;
                mutationGate = false;
                failure = error.toString();
                return capture().flatMap(captured -> {
                    publish(captured);
                    return Mono.error(error);
                });
            }))).cache();
    }

    public static Builder builder(DesiredStateRepository repository) { return new Builder(repository); }
    public PublishedRuntime published() { return published; }
    public Mono<PublishedView> start() { return startSignal; }

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
        var target = new LinkedHashMap<ArtifactId, ArtifactRecord>();
        DesiredCompilation desired;
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
        }
        domain = runtime.openDomain("engine");
        var context = domain.rootScope().context();
        hostServices.freeze().forEach(binding -> provideHostBinding(context, binding));
        context.services().provide(ContributionServices.REGISTRAR, directory);
        observation = Flux.merge(domain.snapshots(), directory.views())
            .subscribe(ignored -> loop.observe(this::refresh));
        var plan = new ChangeSet(desired, target);
        plan.bootstrapping = true;
        plan.saved = saved.isPresent();
        return execute(plan).map(EngineCommandResult::view);
    }

    private Mono<EngineCommandResult> change(EngineCommand command) {
        var desired = compilation;
        if (command instanceof RefreshDesired) desired = loadDesired();
        if (command instanceof ReplaceDesiredGraph replace) {
            checkDesiredRevision(replace.expectedDesiredRevision());
            desired = replacementCompilation(replace.graph());
        }
        if (command instanceof ApplyDeployment deployment) {
            checkDesiredRevision(deployment.expectedDesiredRevision());
            desired = replacementCompilation(deployment.graph());
        }
        var plan = new ChangeSet(desired, new LinkedHashMap<>(artifacts));
        return Mono.defer(() -> {
            if (command instanceof UninstallArtifact uninstall) {
                if (plan.artifacts.remove(uninstall.artifactId()) == null) {
                    throw new IllegalArgumentException("artifact is not selected: " + uninstall.artifactId().value());
                }
            } else if (command instanceof InstallArtifact install) {
                stage(plan, install.artifactId(), install.runtimeId(), install.version(), install.source());
            } else if (command instanceof ApplyDeployment deployment) {
                var ids = new LinkedHashSet<ArtifactId>();
                for (var artifact : deployment.artifacts()) {
                    if (!ids.add(artifact.artifactId())) throw new IllegalArgumentException("duplicate deployment artifact");
                    stage(plan, artifact.artifactId(), artifact.runtimeId(), artifact.version(), artifact.source());
                }
            }
            return execute(plan);
        }).onErrorResume(error -> plan.executing ? Mono.error(error) : fail(plan, error));
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

    private Mono<EngineCommandResult> execute(ChangeSet plan) {
        plan.executing = true;
        return loop.call(() -> {
            phase = ChangePhase.PREPARING;
            failure = null;
            publishControl();
            plan.update = resources.createUpdate(plan.artifacts);
            return plan.update.prepareAsync();
        }).then(loop.call(() -> {
            plan.bound = bind(plan.desired, plan.update.catalog());
            var changed = new LinkedHashSet<String>();
            instances.forEach((id, managed) -> {
                var next = plan.bound.get(id);
                if (next == null || !sameInput(managed.bound(), next)) changed.add(id);
            });
            plan.bound.keySet().stream().filter(id -> !instances.containsKey(id)).forEach(changed::add);
            affected = Set.copyOf(changed);
            phase = ChangePhase.SAVING;
            publishControl();
            plan.installs.forEach(transaction -> {
                var saved = transaction.save();
                plan.artifacts.put(saved.id(), saved);
            });
            var selections = new LinkedHashMap<ArtifactId, String>();
            plan.artifacts.forEach((id, record) -> selections.put(id, record.revision()));
            var manifest = new DeploymentManifest(selections, plan.desired.graph());
            if (!plan.saved) stateStore.save(manifest);
            plan.saved = true;
            targetRevision = manifest.revision();
            compilation = plan.desired;
            artifacts = Map.copyOf(plan.artifacts);
            phase = ChangePhase.RECONCILING;
            publishControl();
            return reconcile(plan);
        })).then(loop.call(() -> domain.settled()))
            .then(loop.call(() -> {
                assertClean();
                phase = ChangePhase.RETIRING;
                refresh();
                return plan.update.closeAsync().doOnSuccess(ignored -> plan.retired = true);
            })).then(loop.call(() -> capture().map(captured -> {
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
            plan.update.adopt();
            return Flux.fromIterable(plan.bound.entrySet()).concatMap(entry -> loop.call(() -> {
                var previous = instances.get(entry.getKey());
                var next = entry.getValue();
                if (previous == null) {
                    var scope = domain.rootScope().openChild("plugin:" + entry.getKey());
                    try {
                        var managed = mount(scope, next);
                        instances.put(entry.getKey(), managed);
                    } catch (RuntimeException | Error error) {
                        return scope.closeAsync().then(Mono.error(error));
                    }
                    refresh();
                    return Mono.empty();
                }
                if (!sameInput(previous.bound(), next)) return update(previous, next)
                    .onErrorResume(error -> {
                        if (previous.instance().state() != PluginInstanceState.FAILED) return Mono.error(error);
                        return Mono.empty();
                    })
                    .then(loop.call(() -> {
                        instances.put(entry.getKey(), previous.withBound(next));
                        refresh();
                        return Mono.empty();
                    }));
                // Declaration-only requirements may change without restarting the instance.
                instances.put(entry.getKey(), previous.withBound(next));
                return Mono.empty();
            })).then();
        }));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Mono<Void> update(Managed<?> previous, Bound<?> next) {
        return ((PluginInstance) previous.instance()).updatePrepared(next.prepared()).then();
    }

    private Map<String, Bound<?>> bind(DesiredCompilation desired, PluginCatalog catalog) {
        var result = new LinkedHashMap<String, Bound<?>>();
        desired.graph().plugins().forEach((id, input) -> {
            var effective = desired.graph().effective(id);
            if (!effective.enabled()) return;
            var contract = catalog.find(input.definitionName()).orElseThrow(() ->
                bindingFailure(desired, id, "DEFINITION_NOT_FOUND", "unknown definition " + input.definitionName(), null));
            var previous = instances.get(id);
            if (previous != null && previous.bound().prepared().definition() == contract.definition()
                && previous.bound().input().config().equals(input.config())) {
                result.put(id, previous.bound().withDeclaration(input, effective));
                return;
            }
            try { result.put(id, bound(input, effective, contract)); }
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

    private static <C> Bound<C> bound(DesiredInputEntry input, EffectiveDesiredEntry effective,
                                     PluginCatalogEntry<C> contract) {
        return new Bound<>(input, effective, contract.bind(input.config()));
    }

    private static boolean mustRemount(Bound<?> old, Bound<?> next) {
        return old.prepared().definition() != next.prepared().definition()
            || !old.effective().realms().equals(next.effective().realms())
            || !old.effective().intercepts().equals(next.effective().intercepts())
            || !Objects.equals(old.effective().parentId(), next.effective().parentId());
    }

    private static boolean sameInput(Bound<?> old, Bound<?> next) {
        return !mustRemount(old, next) && old.input().config().equals(next.input().config());
    }

    private static <C> Managed<C> mount(Scope scope, Bound<C> bound) {
        var instance = context(scope.context(), bound.effective()).plugins()
            .mount(bound.effective().entryId(), bound.prepared());
        return new Managed<>(scope, instance, bound);
    }

    private Mono<EngineCommandResult> fail(ChangeSet plan, Throwable error) {
        boolean unconfirmed = error instanceof EngineStateStore.SaveUnconfirmedException;
        if (unconfirmed || (plan.saved && !plan.retired)) mutationGate = false;
        var cleanup = (plan.saved || unconfirmed) ? Mono.<Void>empty()
            : (plan.update == null ? Mono.<Void>empty() : plan.update.closeAsync())
                .then(Mono.fromRunnable(() -> plan.installs.forEach(ArtifactInstallTransaction::rollback)));
        return cleanup.onErrorResume(closeFailure -> {
            mutationGate = false;
            if (closeFailure != error) error.addSuppressed(closeFailure);
            return Mono.empty();
        }).then(loop.call(() -> {
            failure = error.toString();
            phase = ChangePhase.FAILED;
            if (plan.bootstrapping || domain == null || state == EngineState.NEW) state = EngineState.FAILED;
            return capture().flatMap(captured -> {
                publish(captured);
                return Mono.error(new EngineChangeException(publishedState.get().view(), plan.saved, error));
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
            failure = "runtime cleanup failed: " + captured.domain().cleanupFailures();
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
                .config(managed.bound().input().config())
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
        var enabled = compilation.graph().plugins().keySet().stream()
            .filter(id -> compilation.graph().effective(id).enabled())
            .collect(java.util.stream.Collectors.toSet());
        boolean satisfied = state == EngineState.RUNNING && phase != ChangePhase.FAILED
            && targetRevision != null && snapshot.desiredGraph().equals(compilation.graph())
            && snapshot.artifacts().equals(artifacts) && resources.matchesTarget(artifacts)
            && snapshot.instances().keySet().equals(enabled) && enabled.stream().allMatch(id -> {
                var expected = compilation.graph().plugins().get(id);
                var actual = snapshot.instances().get(id);
                var managed = instances.get(id);
                return actual != null && actual.requirementSatisfied()
                    && actual.definitionName().equals(expected.definitionName())
                    && actual.config().equals(expected.config())
                    && managed != null && managed.bound().effective().equals(compilation.graph().effective(id));
            });
        return EngineDiagnostics.builder().targetRevision(targetRevision).phase(phase)
            .affectedInstances(affected).resources(snapshot.runtimes()).targetSatisfied(satisfied)
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

    private <D, I, O> Mono<O> invokePublished(String revision, ContributionKind<D, I, O> kind,
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
            try { call = current.routes().acquire(kind, id); }
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
        phase = ChangePhase.CLOSING;
        publishControl();
        if (observation != null) observation.dispose();
        return directory.closeAsync()
            .then(domain == null ? Mono.empty() : Mono.defer(domain::closeAsync))
            .then(Mono.defer(runtime::closeAsync))
            .then(Mono.fromRunnable(this::assertClean))
            .then(Mono.defer(resources::closeAsync))
            .then(loop.call(() -> {
                closeStores();
                instances.clear();
                state = EngineState.CLOSED;
                phase = ChangePhase.CLOSED;
                publishEmpty();
                views.tryEmitComplete();
                return Mono.<Void>empty();
            })).onErrorResume(error -> loop.call(() -> {
                state = EngineState.FAILED;
                phase = ChangePhase.FAILED;
                mutationGate = false;
                failure = "engine cleanup failed: " + error;
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

    public static final class Builder {
        private final DesiredStateRepository desiredRepository;
        private PluginCatalog catalog = PluginCatalog.empty();
        private EngineStateStore stateStore = EngineStateStore.inMemory();
        private ArtifactStore artifactStore;
        private HostServiceRegistry hostServices = new HostServiceRegistry();
        private final Map<RuntimeId, PluginRuntimeAdapter> runtimeAdapters = new LinkedHashMap<>();
        private Builder(DesiredStateRepository repository) { desiredRepository = Objects.requireNonNull(repository, "repository"); }
        public Builder catalog(PluginCatalog value) { catalog = Objects.requireNonNull(value); return this; }
        public Builder stateStore(EngineStateStore value) { stateStore = Objects.requireNonNull(value); return this; }
        public Builder artifactStore(ArtifactStore value) { artifactStore = Objects.requireNonNull(value); return this; }
        public Builder hostServices(HostServiceRegistry value) { hostServices = Objects.requireNonNull(value); return this; }
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
        final List<ArtifactInstallTransaction> installs = new ArrayList<>();
        RuntimeResources.Update update;
        Map<String, Bound<?>> bound;
        boolean saved;
        boolean retired;
        boolean bootstrapping;
        boolean executing;
        ChangeSet(DesiredCompilation desired, Map<ArtifactId, ArtifactRecord> artifacts) {
            this.desired = desired;
            this.artifacts = new LinkedHashMap<>(artifacts);
        }
    }

    private record Bound<C>(DesiredInputEntry input, EffectiveDesiredEntry effective,
                            PluginDefinition.Prepared<C> prepared) {
        Bound<C> withDeclaration(DesiredInputEntry input, EffectiveDesiredEntry effective) {
            return new Bound<>(input, effective, prepared);
        }
    }
    private record Managed<C>(Scope scope, PluginInstance<C> instance, Bound<C> bound) {
        @SuppressWarnings("unchecked")
        Managed<C> withBound(Bound<?> next) { return new Managed<>(scope, instance, (Bound<C>) next); }
    }
    private record PublishedState(PublishedView view, ContributionRoutes routes) { }
    private record ObservedRuntime(RuntimeDomainSnapshot domain, ContributionDirectoryView contributions) { }
    private record LocalRealm(String ownerEntryId) {
        @Override public String toString() { return "local:" + ownerEntryId; }
    }
}
