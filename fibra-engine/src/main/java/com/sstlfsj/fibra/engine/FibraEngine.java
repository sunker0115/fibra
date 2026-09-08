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
import com.sstlfsj.fibra.config.DesiredEntry;
import com.sstlfsj.fibra.config.DesiredGraph;
import com.sstlfsj.fibra.config.DesiredSourceSnapshot;
import com.sstlfsj.fibra.config.DesiredStateRepository;
import com.sstlfsj.fibra.config.DesiredStateWriteTransaction;
import com.sstlfsj.fibra.runtime.FibraRuntime;
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

public final class FibraEngine implements AutoCloseable {
    private final DesiredStateRepository desiredRepository;
    private final PluginCatalog builtInCatalog;
    private final ArtifactStore artifactStore;
    private final Map<RuntimeId, PluginRuntimeAdapter> runtimeAdapters;
    private final FibraRuntime runtime = FibraRuntime.create();
    private final ChangeSetExecutor executor;
    private final Sinks.Many<EngineSnapshot> snapshots = Sinks.many().replay().latest();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final Mono<EngineSnapshot> startSignal;

    private volatile EngineSnapshot snapshot = emptySnapshot();
    private volatile Generation generation;
    private Map<RuntimeId, PluginCatalog> runtimeCatalogs = Map.of();

    private FibraEngine(Builder builder) {
        desiredRepository = builder.desiredRepository;
        builtInCatalog = builder.catalog;
        artifactStore = builder.artifactStore;
        runtimeAdapters = Map.copyOf(builder.runtimeAdapters);
        executor = new ChangeSetExecutor(builder.journal);
        snapshots.tryEmitNext(snapshot);
        startSignal = Mono.defer(this::bootstrap).cache();
    }

    public static Builder builder(DesiredStateRepository desiredRepository) {
        return new Builder(desiredRepository);
    }

    public Mono<EngineSnapshot> start() {
        return startSignal;
    }

    public Mono<EngineCommandResult> submit(EngineCommand command) {
        Objects.requireNonNull(command, "command");
        if (snapshot.state() == EngineState.NEW) {
            return Mono.error(new IllegalStateException("engine is not started"));
        }
        return Mono.defer(() -> submitInternal(command));
    }

    public EngineSnapshot snapshot() {
        return snapshot;
    }

    public Flux<EngineSnapshot> snapshots() {
        return snapshots.asFlux();
    }

    public FibraRuntime runtime() {
        return runtime;
    }

    private Mono<EngineSnapshot> bootstrap() {
        return Mono.defer(() -> {
            if (closeRequested.get()) {
                return Mono.error(new IllegalStateException("engine is closed"));
            }
            executor.verifyRecovered();
            var installed = artifactStore == null ? List.<ArtifactRecord>of()
                : artifactStore.installed();
            var artifacts = new LinkedHashMap<ArtifactId, ArtifactRecord>();
            var grouped = new LinkedHashMap<RuntimeId, List<ArtifactRecord>>();
            for (var artifact : installed) {
                artifacts.put(artifact.id(), artifact);
                grouped.computeIfAbsent(artifact.runtimeId(), ignored -> new ArrayList<>())
                    .add(artifact);
            }
            var catalogs = new LinkedHashMap<RuntimeId, PluginCatalog>();
            var runtimeSnapshots =
                new LinkedHashMap<RuntimeId, RuntimeGenerationSnapshot>();
            var compilation = new Holder<DesiredCompilation>();
            var candidate = new Holder<Generation>();
            var previous = new Holder<Generation>();
            var builder = ChangeSet.builder(UUID.randomUUID().toString());
            for (var entry : grouped.entrySet()) {
                var adapter = runtimeAdapters.get(entry.getKey());
                if (adapter == null) {
                    return Mono.error(new UnknownRuntimeException(entry.getKey()));
                }
                builder.participant(bootstrapRuntimeParticipant(adapter, entry.getValue(),
                    catalogs, runtimeSnapshots));
            }
            var command = new RefreshDesired(null);
            builder.participant(desiredReadParticipant(command, compilation,
                    () -> combinedCatalog(catalogs)))
                .participant(coreParticipant(compilation, candidate, previous,
                    () -> combinedCatalog(catalogs)))
                .verify(() -> verify(candidate.value))
                .publish(() -> publish(compilation.value, candidate.value,
                    artifacts, runtimeSnapshots, catalogs));
            return executor.execute(builder.build())
                .then(Mono.fromSupplier(() -> snapshot));
        });
    }

    private ChangeParticipant bootstrapRuntimeParticipant(
        PluginRuntimeAdapter adapter, List<ArtifactRecord> artifacts,
        Map<RuntimeId, PluginCatalog> catalogs,
        Map<RuntimeId, RuntimeGenerationSnapshot> runtimeSnapshots) {
        return new ChangeParticipant() {
            @Override
            public String name() {
                return "runtime:" + adapter.id().value();
            }

            @Override
            public Mono<PreparedChange> prepare() {
                return Flux.fromIterable(artifacts)
                    .concatMap(adapter::inspect)
                    .then(adapter.prepare(new RuntimeChangeRequest(
                        adapter.id(), artifacts, null)))
                    .doOnNext(prepared -> {
                        catalogs.put(adapter.id(), prepared.catalog());
                        runtimeSnapshots.put(adapter.id(), prepared.snapshot());
                    })
                    .map(prepared -> prepared);
            }
        };
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
        var previous = new Holder<Generation>();
        var id = UUID.randomUUID().toString();
        var effectiveCatalog = combinedCatalog(runtimeCatalogs);
        var builder = ChangeSet.builder(id);
        if (command instanceof ReplaceDesiredGraph replace) {
            builder.participant(desiredWriteParticipant(replace.expectedRevision(),
                replace.expectedDesiredRevision(), replace.graph(), compilation,
                desiredWrite));
        } else {
            builder.participant(desiredReadParticipant(command, compilation,
                () -> effectiveCatalog));
        }
        builder.participant(coreParticipant(compilation, candidate, previous,
                () -> effectiveCatalog))
            .verify(() -> verify(candidate.value))
            .publish(() -> publish(compilation.value, candidate.value,
                snapshot.artifacts(), snapshot.runtimes(), runtimeCatalogs));
        return executor.execute(builder.build())
            .map(result -> new EngineCommandResult(snapshot, result.warnings()));
    }

    private Mono<EngineCommandResult> submitDeployment(ApplyDeployment command) {
        var transactions = new Holder<List<ArtifactInstallTransaction>>();
        var previousArtifacts = new Holder<Map<ArtifactId, ArtifactRecord>>();
        var candidateArtifacts = new Holder<Map<ArtifactId, ArtifactRecord>>();
        var candidateCatalogs = new Holder<Map<RuntimeId, PluginCatalog>>();
        var candidateRuntimeSnapshots =
            new Holder<Map<RuntimeId, RuntimeGenerationSnapshot>>();
        var compilation = new Holder<DesiredCompilation>();
        var desiredWrite = new Holder<DesiredStateWriteTransaction>();
        var candidate = new Holder<Generation>();
        var previous = new Holder<Generation>();
        candidateCatalogs.value = new LinkedHashMap<>(runtimeCatalogs);
        candidateRuntimeSnapshots.value = new LinkedHashMap<>(snapshot.runtimes());

        var changeSet = ChangeSet.builder(UUID.randomUUID().toString())
            .participant(deploymentArtifactParticipant(command, transactions,
                previousArtifacts, candidateArtifacts));
        var runtimeIds = new LinkedHashSet<RuntimeId>();
        command.artifacts().forEach(artifact -> runtimeIds.add(artifact.runtimeId()));
        for (var runtimeId : runtimeIds) {
            var adapter = runtimeAdapters.get(runtimeId);
            if (adapter == null) {
                return Mono.error(new UnknownRuntimeException(runtimeId));
            }
            changeSet.participant(deploymentRuntimeParticipant(command, runtimeId,
                adapter, candidateArtifacts, candidateCatalogs,
                candidateRuntimeSnapshots));
        }
        changeSet.participant(desiredWriteParticipant(command.expectedRevision(),
                command.expectedDesiredRevision(), command.graph(), compilation,
                desiredWrite))
            .participant(coreParticipant(compilation, candidate, previous,
                () -> combinedCatalog(candidateCatalogs.value)))
            .verify(() -> verify(candidate.value))
            .publish(() -> publish(compilation.value, candidate.value,
                candidateArtifacts.value, candidateRuntimeSnapshots.value,
                candidateCatalogs.value));
        return executor.execute(changeSet.build())
            .map(result -> new EngineCommandResult(snapshot, result.warnings()));
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
                var candidates = new LinkedHashMap<>(snapshot.artifacts());
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

    private ChangeParticipant deploymentRuntimeParticipant(
        ApplyDeployment command, RuntimeId runtimeId, PluginRuntimeAdapter adapter,
        Holder<Map<ArtifactId, ArtifactRecord>> artifacts,
        Holder<Map<RuntimeId, PluginCatalog>> catalogs,
        Holder<Map<RuntimeId, RuntimeGenerationSnapshot>> snapshotsOutput) {
        return new ChangeParticipant() {
            @Override public String name() { return "runtime:" + runtimeId.value(); }

            @Override
            public Mono<PreparedChange> prepare() {
                var candidates = artifacts.value.values().stream()
                    .filter(value -> value.runtimeId().equals(runtimeId)).toList();
                var changed = command.artifacts().stream()
                    .filter(value -> value.runtimeId().equals(runtimeId))
                    .map(value -> artifacts.value.get(value.artifactId())).toList();
                return Flux.fromIterable(changed).concatMap(adapter::inspect).then(
                        adapter.prepare(new RuntimeChangeRequest(runtimeId, candidates,
                            snapshot.runtimes().get(runtimeId))))
                    .doOnNext(prepared -> {
                        if (!prepared.snapshot().runtimeId().equals(runtimeId)) {
                            throw new IllegalArgumentException(
                                "runtime generation identity mismatch");
                        }
                        var nextCatalogs = new LinkedHashMap<>(catalogs.value);
                        var nextSnapshots = new LinkedHashMap<>(snapshotsOutput.value);
                        nextCatalogs.put(runtimeId, prepared.catalog());
                        nextSnapshots.put(runtimeId, prepared.snapshot());
                        catalogs.value = Map.copyOf(nextCatalogs);
                        snapshotsOutput.value = Map.copyOf(nextSnapshots);
                    }).map(value -> value);
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
        var runtimePrepared = new Holder<PreparedRuntimeGeneration>();
        var candidateCatalog = new Holder<PluginCatalog>();
        var candidateRuntimeCatalogs = new Holder<Map<RuntimeId, PluginCatalog>>();
        var candidateRuntimeSnapshots =
            new Holder<Map<RuntimeId, RuntimeGenerationSnapshot>>();
        var compilation = new Holder<DesiredCompilation>();
        var candidate = new Holder<Generation>();
        var previous = new Holder<Generation>();
        var runtimeId = uninstall
            ? runtimeFor(((UninstallArtifact) command).artifactId())
            : ((InstallArtifact) command).runtimeId();
        var adapter = runtimeAdapters.get(runtimeId);
        if (adapter == null) {
            return Mono.error(new UnknownRuntimeException(runtimeId));
        }

        var changeSet = ChangeSet.builder(UUID.randomUUID().toString())
            .participant(artifactParticipant(command, uninstall, artifactTransaction,
                previousArtifact, candidateArtifacts))
            .participant(runtimeParticipant(command, runtimeId, adapter, candidateArtifacts,
                runtimePrepared, candidateCatalog, candidateRuntimeCatalogs,
                candidateRuntimeSnapshots))
            .participant(desiredReadParticipant(command, compilation,
                () -> candidateCatalog.value))
            .participant(coreParticipant(compilation, candidate, previous,
                () -> candidateCatalog.value))
            .verify(() -> verify(candidate.value))
            .publish(() -> publish(compilation.value, candidate.value,
                candidateArtifacts.value, candidateRuntimeSnapshots.value,
                candidateRuntimeCatalogs.value))
            .build();
        return executor.execute(changeSet)
            .map(result -> new EngineCommandResult(snapshot, result.warnings()));
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
                var artifacts = new LinkedHashMap<>(snapshot.artifacts());
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

    private ChangeParticipant runtimeParticipant(EngineCommand command, RuntimeId runtimeId,
                                                 PluginRuntimeAdapter adapter,
                                                 Holder<Map<ArtifactId, ArtifactRecord>> artifacts,
                                                 Holder<PreparedRuntimeGeneration> prepared,
                                                 Holder<PluginCatalog> catalogOutput,
                                                 Holder<Map<RuntimeId, PluginCatalog>> catalogs,
                                                 Holder<Map<RuntimeId,
                                                     RuntimeGenerationSnapshot>> snapshotsOutput) {
        return new ChangeParticipant() {
            @Override
            public String name() {
                return "runtime:" + runtimeId.value();
            }

            @Override
            public Mono<PreparedChange> prepare() {
                var candidates = artifacts.value.values().stream()
                    .filter(value -> value.runtimeId().equals(runtimeId)).toList();
                Mono<Void> inspection = Mono.empty();
                if (command instanceof InstallArtifact install) {
                    var candidate = artifacts.value.get(install.artifactId());
                    inspection = adapter.inspect(candidate).flatMap(result -> {
                        if (!result.runtimeId().equals(runtimeId)
                            || !result.artifactId().equals(install.artifactId())) {
                            return Mono.error(new IllegalArgumentException(
                                "runtime inspection identity mismatch"));
                        }
                        return Mono.empty();
                    });
                }
                return inspection.then(adapter.prepare(new RuntimeChangeRequest(runtimeId,
                        candidates, snapshot.runtimes().get(runtimeId))))
                    .doOnNext(value -> {
                        if (!value.snapshot().runtimeId().equals(runtimeId)) {
                            throw new IllegalArgumentException(
                                "runtime generation identity mismatch");
                        }
                        prepared.value = value;
                        var nextCatalogs = new LinkedHashMap<>(runtimeCatalogs);
                        var nextSnapshots = new LinkedHashMap<>(snapshot.runtimes());
                        if (candidates.isEmpty() && value.catalog().entries().isEmpty()) {
                            nextCatalogs.remove(runtimeId);
                            nextSnapshots.remove(runtimeId);
                        } else {
                            nextCatalogs.put(runtimeId, value.catalog());
                            nextSnapshots.put(runtimeId, value.snapshot());
                        }
                        catalogs.value = nextCatalogs;
                        snapshotsOutput.value = nextSnapshots;
                        catalogOutput.value = combinedCatalog(nextCatalogs);
                    })
                    .map(value -> value);
            }
        };
    }

    private RuntimeId runtimeFor(ArtifactId artifactId) {
        var artifact = snapshot.artifacts().get(artifactId);
        if (artifact == null) {
            throw new IllegalArgumentException(
                "artifact is not installed: " + artifactId.value());
        }
        return artifact.runtimeId();
    }

    private PluginCatalog combinedCatalog(Map<RuntimeId, PluginCatalog> catalogs) {
        var all = new ArrayList<PluginCatalog>(catalogs.size() + 1);
        all.add(builtInCatalog);
        all.addAll(catalogs.values());
        return PluginCatalog.combine(all);
    }

    private static Map<ArtifactId, ArtifactRecord> replaceArtifact(
        Map<ArtifactId, ArtifactRecord> artifacts, ArtifactRecord replacement) {
        var result = new LinkedHashMap<>(artifacts);
        result.put(replacement.id(), replacement);
        return Map.copyOf(result);
    }

    private ChangeParticipant desiredReadParticipant(EngineCommand command,
                                                     Holder<DesiredCompilation> output,
                                                     java.util.function.Supplier<PluginCatalog>
                                                         effectiveCatalog) {
        return new ChangeParticipant() {
            @Override
            public String name() {
                return "desired";
            }

            @Override
            public Mono<PreparedChange> prepare() {
                checkRevision(command.expectedRevision());
                output.value = desiredRepository.load(effectiveCatalog.get().resolver());
                return Mono.just(noop("desired"));
            }
        };
    }

    private ChangeParticipant desiredWriteParticipant(String expectedRevision,
                                                      String expectedDesiredRevision,
                                                      DesiredGraph graph,
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

    private ChangeParticipant coreParticipant(Holder<DesiredCompilation> compilation,
                                              Holder<Generation> candidate,
                                              Holder<Generation> previous,
                                              java.util.function.Supplier<PluginCatalog>
                                                  effectiveCatalog) {
        return new ChangeParticipant() {
            @Override
            public String name() {
                return "core";
            }

            @Override
            public Mono<PreparedChange> prepare() {
                previous.value = generation;
                return prepareGeneration(compilation.value, effectiveCatalog.get())
                    .doOnNext(value -> candidate.value = value)
                    .map(value -> new PreparedChange() {
                        @Override
                        public String name() {
                            return "core";
                        }

                        @Override
                        public Mono<Void> commit() {
                            return Mono.empty();
                        }

                        @Override
                        public Mono<Void> rollback() {
                            return value.scope().closeAsync();
                        }

                        @Override
                        public Mono<Void> retire() {
                            return previous.value == null ? Mono.empty()
                                : previous.value.retire();
                        }
                    });
            }
        };
    }

    private Mono<Generation> prepareGeneration(DesiredCompilation compilation,
                                               PluginCatalog effectiveCatalog) {
        return Mono.defer(() -> {
            var name = "engine-generation-" + (Long.parseLong(snapshot.revision()) + 1);
            var scope = runtime.rootScope().openChild(name);
            var mounted = new LinkedHashMap<String, PluginInstance<?>>();
            try {
                for (var entry : compilation.graph().entries()) {
                    if (!entry.enabled()) {
                        continue;
                    }
                    var catalogEntry = effectiveCatalog.find(entry.definitionName())
                        .orElseThrow(() ->
                        new IllegalArgumentException("unknown plugin definition "
                            + entry.definitionName()));
                    mounted.put(entry.instanceId(), mount(scope, entry, catalogEntry, name));
                }
            } catch (RuntimeException | Error failure) {
                return scope.closeAsync().then(Mono.error(failure));
            }
            return Flux.fromIterable(mounted.values())
                .flatMap(PluginInstance::settled)
                .then(Mono.fromCallable(() -> new Generation(scope, compilation, mounted)))
                .onErrorResume(failure -> scope.closeAsync().then(Mono.error(failure)));
        });
    }

    private static PluginInstance<?> mount(Scope scope, DesiredEntry entry,
                                           PluginCatalogEntry<?> catalogEntry,
                                           String generation) {
        var context = context(scope.context(), entry, catalogEntry.definition(), generation);
        return mountTyped(context, entry.instanceId(), catalogEntry, entry.config());
    }

    private static Context context(Context initial, DesiredEntry entry,
                                   PluginDefinition<?> definition, String generation) {
        var result = initial;
        var keys = new LinkedHashSet<ServiceKey<?>>();
        keys.addAll(definition.requires().keySet());
        keys.addAll(definition.provides());
        for (var key : keys) {
            result = result.withRealm(key, new GenerationRealm(generation,
                entry.realms().get(key.name())));
            if (entry.intercepts().containsKey(key.name())) {
                result = result.withIntercept(key, entry.intercepts().get(key.name()));
            }
        }
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PluginInstance<?> mountTyped(Context context, String instanceId,
                                                PluginCatalogEntry catalogEntry,
                                                Object config) {
        return context.plugins().mount(instanceId, catalogEntry.definition(), config);
    }

    private Mono<Void> verify(Generation candidate) {
        return Mono.fromRunnable(() -> {
            for (var instance : candidate.instances().values()) {
                if (instance.state() != PluginInstanceState.ACTIVE) {
                    throw new IllegalStateException("required plugin instance " + instance.id()
                        + " settled as " + instance.state());
                }
            }
        });
    }

    private Mono<Void> publish(DesiredCompilation compilation, Generation candidate,
                               Map<ArtifactId, ArtifactRecord> artifacts,
                               Map<RuntimeId, RuntimeGenerationSnapshot> runtimes,
                               Map<RuntimeId, PluginCatalog> nextRuntimeCatalogs) {
        return Mono.fromRunnable(() -> {
            generation = candidate;
            var nextRevision = Long.toString(Long.parseLong(snapshot.revision()) + 1);
            var observed = observe(candidate);
            snapshot = new EngineSnapshot(nextRevision, EngineState.RUNNING,
                compilation.snapshot(), compilation.graph(), observed,
                artifacts, normalizeRuntimeSnapshots(runtimes, artifacts), null);
            runtimeCatalogs = Map.copyOf(nextRuntimeCatalogs);
            snapshots.tryEmitNext(snapshot);
            candidate.observation(Flux.merge(candidate.instances().values().stream()
                    .map(PluginInstance::states).toList())
                .subscribe(ignored -> executor.observe(() -> refresh(candidate))));
        });
    }

    private void refresh(Generation expected) {
        if (generation != expected || closeRequested.get()) {
            return;
        }
        var observed = observe(expected);
        if (observed.equals(snapshot.instances())) {
            return;
        }
        var failures = observed.values().stream()
            .filter(value -> value.state() == PluginInstanceState.FAILED)
            .map(value -> value.instanceId() + ": " + value.failure())
            .toList();
        var nextRevision = Long.toString(Long.parseLong(snapshot.revision()) + 1);
        snapshot = new EngineSnapshot(nextRevision,
            failures.isEmpty() ? EngineState.RUNNING : EngineState.FAILED,
            snapshot.desiredSource(), snapshot.desiredGraph(), observed,
            snapshot.artifacts(), snapshot.runtimes(),
            failures.isEmpty() ? null : String.join("; ", failures));
        snapshots.tryEmitNext(snapshot);
    }

    private static Map<String, PluginInstanceSnapshot> observe(Generation generation) {
        var observed = new LinkedHashMap<String, PluginInstanceSnapshot>();
        generation.instances().forEach((id, instance) -> observed.put(id,
            new PluginInstanceSnapshot(id, instance.definition().name(), instance.config(),
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
        if (expected != null && !expected.equals(snapshot.revision())) {
            throw new EngineConflictException(expected, snapshot.revision());
        }
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
        if (!closeRequested.compareAndSet(false, true)) {
            return;
        }
        var active = generation;
        if (active != null) {
            active.retire().block();
        }
        RuntimeException closeFailure = null;
        for (var adapter : runtimeAdapters.values()) {
            try {
                adapter.close();
            } catch (RuntimeException failure) {
                if (closeFailure == null) {
                    closeFailure = new IllegalStateException(
                        "failed to close runtime adapters");
                }
                closeFailure.addSuppressed(failure);
            }
        }
        runtime.close();
        snapshot = new EngineSnapshot(snapshot.revision(), EngineState.CLOSED,
            snapshot.desiredSource(), snapshot.desiredGraph(), Map.of(),
            snapshot.artifacts(), snapshot.runtimes(), snapshot.failure());
        snapshots.tryEmitNext(snapshot);
        snapshots.tryEmitComplete();
        executor.close();
        if (artifactStore != null) {
            artifactStore.close();
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private static EngineSnapshot emptySnapshot() {
        return new EngineSnapshot("0", EngineState.NEW,
            new DesiredSourceSnapshot("unstarted", "0", Set.of()),
            new DesiredGraph(List.of()), Map.of(), Map.of(), Map.of(), null);
    }

    public static final class Builder {
        private final DesiredStateRepository desiredRepository;
        private PluginCatalog catalog = PluginCatalog.empty();
        private TransactionJournal journal = new InMemoryTransactionJournal();
        private ArtifactStore artifactStore;
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
        private final Scope scope;
        private final DesiredCompilation compilation;
        private final Map<String, PluginInstance<?>> instances;
        private reactor.core.Disposable observation;

        private Generation(Scope scope, DesiredCompilation compilation,
                           Map<String, PluginInstance<?>> instances) {
            this.scope = scope;
            this.compilation = compilation;
            this.instances = Map.copyOf(instances);
        }

        private Scope scope() {
            return scope;
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

        private Mono<Void> retire() {
            if (observation != null) {
                observation.dispose();
            }
            return scope.closeAsync();
        }
    }

    private record GenerationRealm(String generation, Object configured) {
    }

    private static final class Holder<T> {
        private T value;
    }
}
