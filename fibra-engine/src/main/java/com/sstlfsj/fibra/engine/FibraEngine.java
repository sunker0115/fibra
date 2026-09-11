package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstance;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.Scope;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactInstallTransaction;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.DesiredCompilation;
import com.sstlfsj.fibra.config.ConfigDiagnostic;
import com.sstlfsj.fibra.config.ConfigStage;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.DesiredSourceSnapshot;
import com.sstlfsj.fibra.config.DesiredStateRepository;
import com.sstlfsj.fibra.config.DesiredStateWriteTransaction;
import com.sstlfsj.fibra.config.PublicationRequirement;
import com.sstlfsj.fibra.bridge.ContributionDirectory;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionRoutes;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.bridge.ContributionSnapshot;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import com.sstlfsj.fibra.runtime.RuntimeDomain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class FibraEngine implements AutoCloseable {
    private final DesiredStateRepository desiredRepository;
    private final PluginCatalog builtInCatalog;
    private final ArtifactStore artifactStore;
    private final Map<RuntimeId, PluginRuntimeAdapter> runtimeAdapters;
    private final HostServiceRegistry hostServices;
    private final FibraRuntime runtime = FibraRuntime.create();
    private final ChangeSetExecutor executor;
    private final Sinks.Many<PublishedView> views = Sinks.many().replay().latest();
    private final AtomicReference<PublishedState> publishedState;
    private final PublishedRuntime published = new PublishedRuntime() {
        @Override
        public PublishedView current() {
            return publishedState.get().view();
        }

        @Override
        public Flux<PublishedView> views() {
            return views.asFlux().publishOn(reactor.core.scheduler.Schedulers.boundedElastic());
        }

        @Override
        public <D, I, O> Mono<O> invoke(String expectedViewRevision,
                                        ContributionKind<D, I, O> kind,
                                        ContributionId id, I input) {
            return invokePublished(expectedViewRevision, kind, id, input)
                .publishOn(reactor.core.scheduler.Schedulers.boundedElastic());
        }
    };
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final Mono<PublishedView> startSignal;
    private final Mono<Void> closeSignal = Mono.defer(this::quiesce)
        .then(Mono.defer(this::closeInternal)).cache();

    private List<HostServiceRegistry.Binding<?>> hostBindings = List.of();

    private FibraEngine(Builder builder) {
        desiredRepository = builder.desiredRepository;
        builtInCatalog = builder.catalog;
        artifactStore = builder.artifactStore;
        runtimeAdapters = Map.copyOf(builder.runtimeAdapters);
        hostServices = builder.hostServices;
        executor = new ChangeSetExecutor(builder.journal, this::refreshDiagnostics);
        var initialEngine = emptySnapshot();
        var initialView = new PublishedView("0", "0", initialEngine,
            new ContributionSnapshot(0, List.of()),
            runtimeDiagnostics("0", null, null),
            engineDiagnostics("0", null, null, null));
        publishedState = new AtomicReference<>(
            new PublishedState(initialView, null, null, null, null));
        views.tryEmitNext(initialView);
        startSignal = Mono.defer(this::bootstrap).cache();
    }

    public static Builder builder(DesiredStateRepository desiredRepository) {
        return new Builder(desiredRepository);
    }

    public Mono<PublishedView> start() {
        return startSignal;
    }

    public Mono<EngineCommandResult> submit(EngineCommand command) {
        Objects.requireNonNull(command, "command");
        if (currentEngine().state() == EngineState.NEW) {
            return Mono.error(new IllegalStateException("engine is not started"));
        }
        return Mono.defer(() -> submitInternal(command));
    }

    public PublishedRuntime published() {
        return published;
    }

    private EngineSnapshot currentEngine() {
        return publishedState.get().view().engine();
    }

    private Generation currentGeneration() {
        return publishedState.get().generation();
    }

    private Mono<PublishedView> bootstrap() {
        return Mono.defer(() -> {
            if (closeRequested.get()) {
                return Mono.error(new IllegalStateException("engine is closed"));
            }
            hostBindings = hostServices.freeze();
            executor.verifyRecovered();
            var installed = artifactStore == null ? List.<ArtifactRecord>of() : artifactStore.installed();
            var artifacts = new LinkedHashMap<ArtifactId, ArtifactRecord>();
            installed.forEach(artifact -> artifacts.put(artifact.id(), artifact));
            var compilation = new Holder<DesiredCompilation>();
            var candidate = new Holder<Generation>();
            var changeSet = ChangeSet.builder(UUID.randomUUID().toString())
                .participant(desiredReadParticipant(new RefreshDesired(null), compilation))
                .participant(generationParticipant(compilation, candidate, () -> artifacts))
                .verify(() -> verify(candidate.value))
                .publish(() -> publish(compilation.value, candidate.value, artifacts)).build();
            return executor.execute(changeSet, result -> commandResult(result).view());
        });
    }

    private Mono<EngineCommandResult> submitInternal(EngineCommand command) {
        if (command instanceof ApplyDeployment deployment) {
            return submitDeployment(deployment);
        }
        if (command instanceof InstallArtifact install) {
            return submitArtifactChange(install, false);
        }
        if (command instanceof UninstallArtifact uninstall) {
            return submitArtifactChange(uninstall, true);
        }
        var compilation = new Holder<DesiredCompilation>();
        var desiredWrite = new Holder<DesiredStateWriteTransaction>();
        var candidate = new Holder<Generation>();
        var builder = ChangeSet.builder(UUID.randomUUID().toString());
        if (command instanceof ReplaceDesiredGraph replace) {
            builder.participant(desiredWriteParticipant(replace.expectedRevision(),
                replace.expectedDesiredRevision(), replace.graph(), compilation, desiredWrite));
        } else {
            builder.participant(desiredReadParticipant(command, compilation));
        }
        builder.participant(generationParticipant(compilation, candidate,
                () -> currentEngine().artifacts()))
            .verify(() -> verify(candidate.value))
            .publish(() -> publish(compilation.value, candidate.value, currentEngine().artifacts()));
        return executor.execute(builder.build(), this::commandResult);
    }

    private Mono<EngineCommandResult> submitDeployment(ApplyDeployment command) {
        var transactions = new Holder<List<ArtifactInstallTransaction>>();
        var previousArtifacts = new Holder<Map<ArtifactId, ArtifactRecord>>();
        var candidateArtifacts = new Holder<Map<ArtifactId, ArtifactRecord>>();
        var compilation = new Holder<DesiredCompilation>();
        var desiredWrite = new Holder<DesiredStateWriteTransaction>();
        var candidate = new Holder<Generation>();
        for (var artifact : command.artifacts()) {
            if (!runtimeAdapters.containsKey(artifact.runtimeId())) {
                return Mono.error(new UnknownRuntimeException(artifact.runtimeId()));
            }
        }
        var changeSet = ChangeSet.builder(UUID.randomUUID().toString())
            .participant(deploymentArtifactParticipant(command, transactions,
                previousArtifacts, candidateArtifacts))
            .participant(desiredWriteParticipant(command.expectedRevision(),
                command.expectedDesiredRevision(), command.graph(), compilation, desiredWrite))
            .participant(generationParticipant(compilation, candidate, () -> candidateArtifacts.value))
            .verify(() -> verify(candidate.value))
            .publish(() -> publish(compilation.value, candidate.value, candidateArtifacts.value))
            .build();
        return executor.execute(changeSet, this::commandResult);
    }

    private ChangeParticipant deploymentArtifactParticipant(
        ApplyDeployment command, Holder<List<ArtifactInstallTransaction>> transactions,
        Holder<Map<ArtifactId, ArtifactRecord>> previous,
        Holder<Map<ArtifactId, ArtifactRecord>> output) {
        return new ChangeParticipant() {
            @Override
            public String name() {
                return "artifact";
            }

            @Override
            public Mono<PreparedChange> prepare() {
                checkRevision(command.expectedRevision());
                if (artifactStore == null) {
                    return Mono.error(new IllegalStateException(
                        "engine has no artifact store"));
                }
                var ids = new java.util.HashSet<ArtifactId>();
                var candidates = new LinkedHashMap<>(currentEngine().artifacts());
                var replaced = new LinkedHashMap<ArtifactId, ArtifactRecord>();
                var prepared = new ArrayList<ArtifactInstallTransaction>();
                try {
                    for (var artifact : command.artifacts()) {
                        if (!ids.add(artifact.artifactId())) {
                            throw new IllegalArgumentException("duplicate deployment artifact "
                                + artifact.artifactId().value());
                        }
                        var old = candidates.get(artifact.artifactId());
                        if (old != null && !old.runtimeId().equals(artifact.runtimeId())) {
                            throw new IllegalArgumentException(
                                "deployment cannot change artifact runtime id");
                        }
                        var transaction = artifactStore.prepareInstall(
                            artifact.artifactId(), artifact.runtimeId(), artifact.version(),
                            artifact.source());
                        prepared.add(transaction);
                        if (old != null && !old.revision().equals(
                            transaction.candidate().revision())) {
                            replaced.put(artifact.artifactId(), old);
                        }
                        candidates.put(artifact.artifactId(), transaction.candidate());
                    }
                } catch (RuntimeException | Error failure) {
                    try {
                        rollbackArtifacts(prepared).block();
                    } catch (RuntimeException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                        throw new RecoveryUncertainException(
                            "cannot clean a partially prepared deployment", failure);
                    }
                    throw failure;
                }
                transactions.value = List.copyOf(prepared);
                previous.value = Map.copyOf(replaced);
                output.value = Map.copyOf(candidates);
                return Mono.just(new PreparedChange() {
                    @Override public String name() { return "artifact"; }

                    @Override
                    public Mono<Void> commit() {
                        return Flux.fromIterable(transactions.value)
                            .concatMap(transaction -> Mono.fromRunnable(() ->
                                output.value = replaceArtifact(output.value,
                                    transaction.commit()))).then();
                    }

                    @Override
                    public Mono<Void> rollback() {
                        return rollbackArtifacts(transactions.value);
                    }

                    @Override
                    public Mono<Void> retire() {
                        return Flux.fromIterable(previous.value.values())
                            .concatMap(artifact -> Mono.fromRunnable(() ->
                                artifactStore.retire(artifact))).then();
                    }
                });
            }
        };
    }

    private static Mono<Void> rollbackArtifacts(
        List<ArtifactInstallTransaction> transactions) {
        var reverse = new ArrayList<>(transactions);
        java.util.Collections.reverse(reverse);
        var failures = new ArrayList<Throwable>();
        return Flux.fromIterable(reverse)
            .concatMap(transaction -> Mono.fromRunnable(transaction::rollback)
                .onErrorResume(failure -> {
                    failures.add(failure);
                    return Mono.empty();
                }))
            .then(Mono.defer(() -> {
                if (failures.isEmpty()) {
                    return Mono.empty();
                }
                var failure = new IllegalStateException(
                    "failed to roll back deployment artifacts");
                failures.forEach(failure::addSuppressed);
                return Mono.error(failure);
            }));
    }

    private Mono<EngineCommandResult> submitArtifactChange(EngineCommand command,
                                                           boolean uninstall) {
        var artifactTransaction = new Holder<ArtifactInstallTransaction>();
        var previousArtifact = new Holder<ArtifactRecord>();
        var candidateArtifacts = new Holder<Map<ArtifactId, ArtifactRecord>>();
        var compilation = new Holder<DesiredCompilation>();
        var candidate = new Holder<Generation>();
        if (command instanceof InstallArtifact install
            && !runtimeAdapters.containsKey(install.runtimeId())) {
            return Mono.error(new UnknownRuntimeException(install.runtimeId()));
        }
        var changeSet = ChangeSet.builder(UUID.randomUUID().toString())
            .participant(artifactParticipant(command, uninstall, artifactTransaction,
                previousArtifact, candidateArtifacts))
            .participant(desiredReadParticipant(command, compilation))
            .participant(generationParticipant(compilation, candidate, () -> candidateArtifacts.value))
            .verify(() -> verify(candidate.value))
            .publish(() -> publish(compilation.value, candidate.value, candidateArtifacts.value))
            .build();
        return executor.execute(changeSet, this::commandResult);
    }

    private ChangeParticipant artifactParticipant(EngineCommand command, boolean uninstall,
                                                  Holder<ArtifactInstallTransaction> transaction,
                                                  Holder<ArtifactRecord> previous,
                                                  Holder<Map<ArtifactId, ArtifactRecord>> output) {
        return new ChangeParticipant() {
            @Override
            public String name() {
                return "artifact";
            }

            @Override
            public Mono<PreparedChange> prepare() {
                checkRevision(command.expectedRevision());
                if (artifactStore == null) {
                    return Mono.error(new IllegalStateException(
                        "engine has no artifact store"));
                }
                var artifacts = new LinkedHashMap<>(currentEngine().artifacts());
                if (uninstall) {
                    var artifactId = ((UninstallArtifact) command).artifactId();
                    previous.value = artifacts.remove(artifactId);
                    if (previous.value == null) {
                        return Mono.error(new IllegalArgumentException(
                            "artifact is not installed: " + artifactId.value()));
                    }
                } else {
                    var install = (InstallArtifact) command;
                    previous.value = artifacts.get(install.artifactId());
                    transaction.value = artifactStore.prepareInstall(install.artifactId(),
                        install.runtimeId(), install.version(), install.source());
                    artifacts.put(install.artifactId(), transaction.value.candidate());
                }
                output.value = artifacts;
                return Mono.just(new PreparedChange() {
                    @Override
                    public String name() {
                        return "artifact";
                    }

                    @Override
                    public Mono<Void> commit() {
                        if (uninstall) {
                            return Mono.empty();
                        }
                        return Mono.fromRunnable(() -> output.value = replaceArtifact(
                            output.value, transaction.value.commit()));
                    }

                    @Override
                    public Mono<Void> rollback() {
                        return uninstall ? Mono.empty()
                            : Mono.fromRunnable(transaction.value::rollback);
                    }

                    @Override
                    public Mono<Void> retire() {
                        if (previous.value == null
                            || (!uninstall && previous.value.revision().equals(
                                transaction.value.candidate().revision()))) {
                            return Mono.empty();
                        }
                        return Mono.fromRunnable(() -> artifactStore.retire(previous.value));
                    }
                });
            }
        };
    }

    private static Map<ArtifactId, ArtifactRecord> replaceArtifact(
        Map<ArtifactId, ArtifactRecord> artifacts, ArtifactRecord replacement) {
        var result = new LinkedHashMap<>(artifacts);
        result.put(replacement.id(), replacement);
        return Map.copyOf(result);
    }

    private ChangeParticipant desiredReadParticipant(EngineCommand command,
                                                     Holder<DesiredCompilation> output) {
        return new ChangeParticipant() {
            @Override
            public String name() {
                return "desired";
            }

            @Override
            public Mono<PreparedChange> prepare() {
                checkRevision(command.expectedRevision());
                output.value = desiredRepository.load();
                return Mono.just(noop("desired"));
            }
        };
    }

    private ChangeParticipant desiredWriteParticipant(String expectedRevision,
                                                      String expectedDesiredRevision,
                                                      DesiredInputGraph graph,
                                                      Holder<DesiredCompilation> output,
                                                      Holder<DesiredStateWriteTransaction> write) {
        return new ChangeParticipant() {
            @Override
            public String name() {
                return "desired";
            }

            @Override
            public Mono<PreparedChange> prepare() {
                checkRevision(expectedRevision);
                if (!desiredRepository.writable()) {
                    return Mono.error(new UnsupportedOperationException(
                        "desired state repository is read-only"));
                }
                write.value = desiredRepository.prepareReplace(
                    expectedDesiredRevision, graph);
                output.value = write.value.candidate();
                return Mono.just(new PreparedChange() {
                    @Override
                    public String name() {
                        return "desired";
                    }

                    @Override
                    public Mono<Void> commit() {
                        return Mono.fromRunnable(write.value::commit);
                    }

                    @Override
                    public Mono<Void> rollback() {
                        return Mono.fromRunnable(write.value::rollback);
                    }

                    @Override
                    public Mono<Void> retire() {
                        return Mono.empty();
                    }
                });
            }
        };
    }

    private ChangeParticipant generationParticipant(Holder<DesiredCompilation> compilation,
                                                     Holder<Generation> candidate,
                                                     java.util.function.Supplier<Map<ArtifactId,
                                                         ArtifactRecord>> artifacts) {
        return new ChangeParticipant() {
            @Override public String name() { return "generation"; }

            @Override
            public Mono<PreparedChange> prepare() {
                var previous = currentGeneration();
                var name = "engine-generation-"
                    + (Long.parseLong(publishedState.get().view().generationRevision()) + 1);
                var value = new Generation(name, compilation.value);
                candidate.value = value;
                return Mono.fromRunnable(() -> publishCandidateDiagnostics(value))
                    .then(Mono.defer(() -> prepareGeneration(value, artifacts.get())))
                    .thenReturn((PreparedChange) new PreparedChange() {
                        @Override public String name() { return "generation"; }
                        @Override public Mono<Void> commit() { return Mono.empty(); }
                        @Override public Mono<Void> rollback() {
                            return value.close().then(Mono.fromRunnable(() -> clearCandidate(value)));
                        }
                        @Override public Mono<Void> retire() {
                            return previous == null ? Mono.empty()
                                : previous.close().then(Mono.fromRunnable(() -> finishDraining(previous)));
                        }
                    })
                    .onErrorResume(failure -> failAfterClose(value.close()
                        .then(Mono.fromRunnable(() -> clearCandidate(value))), failure));
            }
        };
    }

    private Mono<Void> prepareGeneration(Generation candidate, Map<ArtifactId, ArtifactRecord> artifacts) {
        return candidate.resources.prepare(artifacts, runtimeAdapters, builtInCatalog)
            .then(Mono.defer(() -> {
                var bound = bind(candidate.compilation(), candidate.resources.catalog());
                candidate.domain = runtime.openDomain(candidate.name);
                candidate.directory = new ContributionDirectory();
                var scope = candidate.domain.rootScope();
                hostBindings.forEach(binding -> provideHostBinding(scope.context(), binding));
                scope.context().services().provide(ContributionServices.REGISTRAR, candidate.directory);
                var mounted = new LinkedHashMap<String, PluginInstance<?>>();
                for (var entry : bound) {
                    mounted.put(entry.input().instanceId(), mount(scope, entry));
                }
                candidate.instances = Map.copyOf(mounted);
                return awaitSettled(mounted.values());
            }));
    }

    private static <T> Mono<T> failAfterClose(Mono<Void> cleanup, Throwable failure) {
        return cleanup.onErrorMap(closeFailure -> {
            if (failure != closeFailure) failure.addSuppressed(closeFailure);
            return new RecoveryUncertainException("cannot close candidate generation", failure);
        }).then(Mono.error(failure));
    }

    private static Mono<Void> awaitSettled(
        java.util.Collection<PluginInstance<?>> instances) {
        return Flux.fromIterable(instances)
            .flatMap(PluginInstance::settled)
            .then(Mono.defer(() -> instances.stream().anyMatch(instance ->
                    instance.state() == PluginInstanceState.STARTING
                        || instance.state() == PluginInstanceState.STOPPING)
                ? awaitSettled(instances) : Mono.empty()));
    }

    private static List<BoundDesiredEntry<?>> bind(DesiredCompilation compilation,
                                                   PluginCatalog catalog) {
        var bound = new ArrayList<BoundDesiredEntry<?>>();
        for (var entry : compilation.graph().entries()) {
            if (!entry.enabled()) {
                continue;
            }
            var source = compilation.entrySources().get(entry.instanceId());
            var contract = catalog.find(entry.definitionName()).orElseThrow(() ->
                new DesiredBindingException(new ConfigDiagnostic(ConfigStage.COMPILE,
                    "DEFINITION_NOT_FOUND", "unknown plugin definition " + entry.definitionName(),
                    source, entry.instanceId()), null));
            try {
                bound.add(bind(entry, contract));
            } catch (RuntimeException failure) {
                throw new DesiredBindingException(new ConfigDiagnostic(ConfigStage.COMPILE,
                    "CONFIG_BIND_FAILED", "cannot bind config for " + entry.definitionName(),
                    source, entry.instanceId()), failure);
            }
        }
        return List.copyOf(bound);
    }

    private static <C> BoundDesiredEntry<C> bind(DesiredInputEntry input,
                                                 PluginCatalogEntry<C> catalogEntry) {
        return new BoundDesiredEntry<>(input, catalogEntry.bind(input.config()));
    }

    private static <C> PluginInstance<C> mount(Scope scope, BoundDesiredEntry<C> entry) {
        var definition = entry.prepared().definition();
        var context = context(scope.context(), entry.input(), definition);
        return context.plugins().mount(entry.input().instanceId(), entry.prepared());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void provideHostBinding(
        Context context, HostServiceRegistry.Binding binding) {
        context.services().provide(binding.key(), binding.value());
    }

    private static Context context(Context initial, DesiredInputEntry entry,
                                   PluginDefinition<?> definition) {
        var result = initial;
        var keys = new LinkedHashSet<ServiceKey<?>>();
        keys.addAll(definition.requires().keySet());
        keys.addAll(definition.provides());
        for (var key : keys) {
            if (entry.realms().containsKey(key.name())) {
                result = result.withRealm(key, entry.realms().get(key.name()).toJava());
            }
            if (entry.intercepts().containsKey(key.name())) {
                result = result.withIntercept(key, entry.intercepts().get(key.name()).toJava());
            }
        }
        return result;
    }

    private Mono<Void> verify(Generation candidate) {
        return Mono.fromRunnable(() -> {
            for (var entry : candidate.compilation().graph().entries()) {
                if (!entry.enabled()) {
                    continue;
                }
                var instance = candidate.instances().get(entry.instanceId());
                var state = instance.state();
                var accepted = state == PluginInstanceState.ACTIVE
                    || state == PluginInstanceState.PENDING
                    && entry.publicationRequirement()
                        == PublicationRequirement.PENDING_ALLOWED;
                if (!accepted) {
                    throw new IllegalStateException("required plugin instance " + instance.id()
                        + " settled as " + state + " for "
                        + entry.publicationRequirement());
                }
            }
        });
    }

    private Mono<Void> publish(DesiredCompilation compilation, Generation candidate,
                               Map<ArtifactId, ArtifactRecord> artifacts) {
        return Mono.fromRunnable(() -> {
            var previous = publishedState.get();
            var nextRevision = nextRevision(previous.view().viewRevision());
            var observed = observe(candidate);
            var engine = new EngineSnapshot(EngineState.RUNNING,
                compilation.snapshot(), compilation.graph(), observed,
                artifacts, normalizeRuntimeSnapshots(candidate.resources.snapshots(), artifacts), null);
            var contributions = candidate.directory().current();
            var view = new PublishedView(nextRevision, candidate.revision(), engine,
                contributions.snapshot(),
                runtimeDiagnostics(candidate.revision(), candidate, null),
                engineDiagnostics(candidate.revision(), null,
                    previous.generation(), null));
            publishedState.set(new PublishedState(
                view, candidate, contributions.routes(), null, previous.generation()));
            if (previous.generation() != null) {
                previous.generation().closeAdmission();
            }
            views.tryEmitNext(view);
            var observations = new ArrayList<Flux<?>>();
            observations.addAll(candidate.instances().values().stream()
                .map(PluginInstance::states).toList());
            observations.add(candidate.directory().views());
            candidate.observation(Flux.merge(observations)
                .subscribe(ignored -> executor.observe(() -> refresh(candidate))));
        });
    }

    private void refresh(Generation expected) {
        var current = publishedState.get();
        if (current.generation() != expected || closeRequested.get()) {
            return;
        }
        var observed = observe(expected);
        var contributions = expected.directory().current();
        if (observed.equals(current.view().engine().instances())
            && contributions.snapshot().equals(current.view().contributions())) {
            return;
        }
        var failures = observed.values().stream()
            .filter(value -> value.state() == PluginInstanceState.FAILED)
            .map(value -> value.instanceId() + ": " + value.failure())
            .toList();
        var nextRevision = nextRevision(current.view().viewRevision());
        var previousEngine = current.view().engine();
        var engine = new EngineSnapshot(EngineState.RUNNING,
            previousEngine.desiredSource(), previousEngine.desiredGraph(), observed,
            previousEngine.artifacts(), previousEngine.runtimes(),
            failures.isEmpty() ? null : String.join("; ", failures));
        var view = new PublishedView(nextRevision, expected.revision(), engine,
            contributions.snapshot(),
            runtimeDiagnostics(expected.revision(), expected, engine.failure()),
            engineDiagnostics(expected.revision(), current.candidate(),
                current.draining(), engine.failure()));
        publishedState.set(new PublishedState(
            view, expected, contributions.routes(), current.candidate(),
            current.draining()));
        views.tryEmitNext(view);
    }

    private static Map<String, PluginInstanceSnapshot> observe(Generation generation) {
        var observed = new LinkedHashMap<String, PluginInstanceSnapshot>();
        generation.instances().forEach((id, instance) -> observed.put(id,
            new PluginInstanceSnapshot(id, instance.definition().name(),
                generation.compilation().graph().require(id).config(),
                instance.state(), instance.failure().map(Throwable::toString).orElse(null))));
        return Map.copyOf(observed);
    }

    private static Map<RuntimeId, RuntimeGenerationSnapshot> normalizeRuntimeSnapshots(
        Map<RuntimeId, RuntimeGenerationSnapshot> runtimes,
        Map<ArtifactId, ArtifactRecord> artifacts) {
        var result = new LinkedHashMap<RuntimeId, RuntimeGenerationSnapshot>();
        runtimes.forEach((runtimeId, runtimeSnapshot) -> {
            var normalized = new LinkedHashMap<ArtifactId, ArtifactRecord>();
            runtimeSnapshot.artifacts().keySet().forEach(id -> {
                var artifact = artifacts.get(id);
                if (artifact != null) {
                    normalized.put(id, artifact);
                }
            });
            result.put(runtimeId, new RuntimeGenerationSnapshot(runtimeId,
                runtimeSnapshot.revision(), normalized, runtimeSnapshot.definitions()));
        });
        return Map.copyOf(result);
    }

    private void checkRevision(String expected) {
        var actual = publishedState.get().view().viewRevision();
        if (expected != null && !expected.equals(actual)) {
            throw new PublishedRevisionConflictException(expected, actual);
        }
    }

    private <D, I, O> Mono<O> invokePublished(
        String expectedViewRevision, ContributionKind<D, I, O> kind,
        ContributionId id, I input) {
        Objects.requireNonNull(expectedViewRevision, "expectedViewRevision");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        return Mono.defer(() -> {
            if (closeRequested.get()) {
                return Mono.error(new IllegalStateException("engine is closing"));
            }
            var state = publishedState.get();
            if (!expectedViewRevision.equals(state.view().viewRevision())) {
                return Mono.error(new PublishedRevisionConflictException(
                    expectedViewRevision, state.view().viewRevision()));
            }
            if (state.generation() == null || state.routes() == null) {
                return Mono.error(new IllegalStateException("engine is not running"));
            }
            var lease = state.generation().tryAcquire();
            if (lease == null) {
                return publishedState.get() != state
                    ? invokePublished(expectedViewRevision, kind, id, input)
                    : Mono.error(new IllegalStateException("generation is not accepting invocations"));
            }
            if (publishedState.get() != state) {
                lease.close();
                return invokePublished(expectedViewRevision, kind, id, input);
            }
            final Scope invocationScope;
            try {
                invocationScope = state.generation().domain().rootScope()
                    .openChild("invocation:" + kind.name());
            } catch (RuntimeException | Error failure) {
                lease.close();
                return Mono.error(failure);
            }
            return Mono.usingWhen(Mono.just(invocationScope),
                scope -> state.routes().invoke(scope.context(), kind, id, input),
                scope -> closeInvocation(scope, lease),
                (scope, failure) -> closeInvocation(scope, lease),
                scope -> closeInvocation(scope, lease));
        });
    }

    private static Mono<Void> closeInvocation(Scope scope, GenerationLease lease) {
        return Mono.defer(scope::closeAsync)
            .doOnSuccess(ignored -> lease.close())
            .doOnError(ignored -> lease.close())
            .cache();
    }

    private static RuntimeDiagnostics runtimeDiagnostics(String generationRevision,
                                                         Generation generation,
                                                         String failure) {
        if (generation == null) {
            return new RuntimeDiagnostics(generationRevision, null,
                List.of(), List.of(), List.of(), failure);
        }
        var domain = generation.domain().snapshot();
        var entries = generation.compilation().graph().entries().stream()
            .collect(java.util.stream.Collectors.toMap(
                DesiredInputEntry::instanceId, value -> value));
        var plugins = domain.plugins().stream().map(plugin -> {
            var requirement = entries.get(plugin.instanceId()).publicationRequirement();
            var impact = plugin.state() == PluginInstanceState.PENDING
                && requirement == PublicationRequirement.ACTIVE_REQUIRED
                ? RuntimeDiagnostics.PublicationImpact.BLOCKING
                : RuntimeDiagnostics.PublicationImpact.NONE;
            return new RuntimeDiagnostics.Plugin(plugin.instanceId(), plugin.pluginId(),
                plugin.state(), requirement, impact, plugin.dependencies(), plugin.failure());
        }).toList();
        return new RuntimeDiagnostics(generationRevision, domain.name(), plugins,
            domain.services(), domain.events(), failure);
    }

    private EngineDiagnostics engineDiagnostics(String generationRevision,
                                                Generation candidate,
                                                Generation draining,
                                                String failure) {
        return new EngineDiagnostics(generationRevision,
            candidate == null ? null : candidate.revision(),
            draining == null ? List.of() : List.of(draining.revision()),
            executor.transactionState(), !closeRequested.get() && executor.acceptsMutations(),
            executor.records(), failure);
    }

    private EngineCommandResult commandResult(ChangeSetResult result) {
        refreshDiagnostics();
        return new EngineCommandResult(publishedState.get().view(), result.warnings());
    }

    private void finishDraining(Generation retired) {
        var current = publishedState.get();
        if (current.draining() == retired) {
            publishDiagnosticRefresh(current, current.candidate(), null);
        }
    }

    private void refreshDiagnostics() {
        var current = publishedState.get();
        var runtime = runtimeDiagnostics(current.view().generationRevision(),
            current.generation(), current.view().engine().failure());
        var engine = engineDiagnostics(current.view().generationRevision(),
            current.candidate(), current.draining(), current.view().engine().failure());
        if (!runtime.equals(current.view().diagnostics())
            || !engine.equals(current.view().engineDiagnostics())) {
            publishDiagnosticRefresh(current, current.candidate(), current.draining());
        }
    }

    private void publishCandidateDiagnostics(Generation candidate) {
        var current = publishedState.get();
        publishDiagnosticRefresh(current, candidate, current.draining());
    }

    private void clearCandidate(Generation candidate) {
        var current = publishedState.get();
        if (current.candidate() == candidate) {
            publishDiagnosticRefresh(current, null, current.draining());
        }
    }

    private void publishDiagnosticRefresh(PublishedState current, Generation candidate,
                                          Generation draining) {
        var revision = nextRevision(current.view().viewRevision());
        var previousEngine = current.view().engine();
        var engine = new EngineSnapshot(previousEngine.state(),
            previousEngine.desiredSource(), previousEngine.desiredGraph(),
            previousEngine.instances(), previousEngine.artifacts(),
            previousEngine.runtimes(), previousEngine.failure());
        var view = new PublishedView(revision, current.view().generationRevision(), engine,
            current.view().contributions(), runtimeDiagnostics(
                current.view().generationRevision(), current.generation(),
                previousEngine.failure()), engineDiagnostics(
                current.view().generationRevision(), candidate, draining,
                previousEngine.failure()));
        publishedState.set(new PublishedState(
            view, current.generation(), current.routes(), candidate, draining));
        views.tryEmitNext(view);
    }

    private static String nextRevision(String current) {
        return Long.toString(Long.parseLong(current) + 1);
    }

    private static PreparedChange noop(String name) {
        return new PreparedChange() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Mono<Void> commit() {
                return Mono.empty();
            }

            @Override
            public Mono<Void> rollback() {
                return Mono.empty();
            }

            @Override
            public Mono<Void> retire() {
                return Mono.empty();
            }
        };
    }

    @Override
    public void close() {
        closeSignal.block();
    }

    private Mono<Void> quiesce() {
        closeRequested.set(true);
        return executor.quiesce();
    }

    private Mono<Void> closeInternal() {
        var failures = new ArrayList<Throwable>();
        java.util.function.Function<Throwable, Mono<Void>> recordFailure = failure -> {
            failures.add(failure);
            return Mono.empty();
        };
        var state = publishedState.get();
        var generations = java.util.stream.Stream.of(state.candidate(), state.generation(), state.draining())
            .filter(Objects::nonNull).distinct().toList();
        return Flux.fromIterable(generations)
            .concatMap(generation -> Mono.defer(generation::close).onErrorResume(recordFailure))
            .then(Mono.defer(runtime::closeAsync).onErrorResume(recordFailure))
            .then(Mono.fromRunnable(this::publishClosedView))
            .then(Mono.defer(executor::closeAsync).onErrorResume(recordFailure))
            .then(Mono.<Void>fromRunnable(() -> {
                if (artifactStore != null) artifactStore.close();
            }).onErrorResume(recordFailure))
            .then(Mono.defer(() -> {
                if (failures.isEmpty()) return Mono.empty();
                var failure = new IllegalStateException("failed to close engine resources");
                failures.forEach(failure::addSuppressed);
                return Mono.error(failure);
            }));
    }

    private void publishClosedView() {
        var current = publishedState.get();
        var engine = current.view().engine();
        var revision = nextRevision(current.view().viewRevision());
        var closedEngine = new EngineSnapshot(EngineState.CLOSED,
            engine.desiredSource(), engine.desiredGraph(), Map.of(),
            engine.artifacts(), engine.runtimes(), engine.failure());
        var closedView = new PublishedView(revision,
            current.view().generationRevision(), closedEngine,
            new ContributionSnapshot(0, List.of()),
            runtimeDiagnostics(current.view().generationRevision(), null, engine.failure()),
            engineDiagnostics(current.view().generationRevision(), null, null,
                engine.failure()));
        publishedState.set(new PublishedState(closedView, null, null, null, null));
        views.tryEmitNext(closedView);
        views.tryEmitComplete();
    }

    private static EngineSnapshot emptySnapshot() {
        return new EngineSnapshot(EngineState.NEW,
            new DesiredSourceSnapshot("unstarted", "0", Set.of()),
            new DesiredInputGraph(List.of()), Map.of(), Map.of(), Map.of(), null);
    }

    public static final class Builder {
        private final DesiredStateRepository desiredRepository;
        private PluginCatalog catalog = PluginCatalog.empty();
        private TransactionJournal journal = new InMemoryTransactionJournal();
        private ArtifactStore artifactStore;
        private HostServiceRegistry hostServices = new HostServiceRegistry();
        private final Map<RuntimeId, PluginRuntimeAdapter> runtimeAdapters =
            new LinkedHashMap<>();

        private Builder(DesiredStateRepository desiredRepository) {
            this.desiredRepository = Objects.requireNonNull(
                desiredRepository, "desiredRepository");
        }

        public Builder catalog(PluginCatalog value) {
            catalog = Objects.requireNonNull(value, "catalog");
            return this;
        }

        public Builder journal(TransactionJournal value) {
            journal = Objects.requireNonNull(value, "journal");
            return this;
        }

        public Builder artifactStore(ArtifactStore value) {
            artifactStore = Objects.requireNonNull(value, "artifactStore");
            return this;
        }

        public Builder hostServices(HostServiceRegistry value) {
            hostServices = Objects.requireNonNull(value, "hostServices");
            return this;
        }

        public Builder runtimeAdapter(PluginRuntimeAdapter value) {
            Objects.requireNonNull(value, "runtimeAdapter");
            var previous = runtimeAdapters.putIfAbsent(value.id(), value);
            if (previous != null) {
                throw new IllegalArgumentException(
                    "duplicate runtime adapter " + value.id().value());
            }
            return this;
        }

        public FibraEngine build() {
            return new FibraEngine(this);
        }
    }

    private static final class Generation {
        private final Object monitor = new Object();
        private final String name;
        private RuntimeDomain domain;
        private ContributionDirectory directory;
        private final DesiredCompilation compilation;
        private Map<String, PluginInstance<?>> instances = Map.of();
        private final Sinks.One<Void> drained = Sinks.one();
        private boolean accepting = true;
        private final RuntimeResources resources = new RuntimeResources();
        private final Mono<Void> close;
        private int inflight;
        private reactor.core.Disposable observation;

        private Generation(String name, DesiredCompilation compilation) {
            this.name = name;
            this.compilation = compilation;
            close = Mono.defer(() -> closeAdmission().then(Mono.defer(() -> {
                if (observation != null) observation.dispose();
                return (domain == null ? Mono.<Void>empty() : Mono.defer(domain::closeAsync))
                    .then(Mono.defer(() -> directory == null ? Mono.empty() : directory.closeAsync()))
                    .then(Mono.defer(resources::closeAsync));
            }))).cache();
        }

        private String revision() {
            return name.substring("engine-generation-".length());
        }

        private RuntimeDomain domain() {
            return domain;
        }

        private ContributionDirectory directory() {
            return directory;
        }

        private DesiredCompilation compilation() {
            return compilation;
        }

        private Map<String, PluginInstance<?>> instances() {
            return instances;
        }

        private void observation(reactor.core.Disposable value) {
            observation = value;
        }

        private GenerationLease tryAcquire() {
            synchronized (monitor) {
                if (!accepting) {
                    return null;
                }
                inflight++;
                return new GenerationLease(this);
            }
        }

        private void release() {
            synchronized (monitor) {
                inflight--;
                if (!accepting && inflight == 0) {
                    drained.tryEmitEmpty();
                }
            }
        }

        private Mono<Void> closeAdmission() {
            synchronized (monitor) {
                if (accepting) {
                    accepting = false;
                    if (inflight == 0) {
                        drained.tryEmitEmpty();
                    }
                }
                return drained.asMono();
            }
        }

        private Mono<Void> close() {
            return close;
        }
    }

    private static final class GenerationLease implements AutoCloseable {
        private final Generation generation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private GenerationLease(Generation generation) {
            this.generation = generation;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                generation.release();
            }
        }
    }

    private record PublishedState(PublishedView view, Generation generation,
                                  ContributionRoutes routes, Generation candidate,
                                  Generation draining) {
    }

    private record BoundDesiredEntry<C>(DesiredInputEntry input,
                                        PluginDefinition.Prepared<C> prepared) { }

    private static final class Holder<T> {
        private T value;
    }
}
