package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.engine.ApplyDeployment;
import com.sstlfsj.fibra.engine.DeploymentTarget;
import com.sstlfsj.fibra.engine.EngineChangeException;
import com.sstlfsj.fibra.engine.EngineCommand;
import com.sstlfsj.fibra.engine.EngineCommandResult;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.PluginSelection;
import com.sstlfsj.fibra.engine.PublishedView;
import com.sstlfsj.fibra.engine.ReconcileCurrent;
import com.sstlfsj.fibra.engine.TargetSaveState;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** package 与 desired entry 管理用例；完整 target 的 CAS 和生命周期由 Engine 独占。 */
public final class PluginRegistry {
    private final FibraEngine engine;
    private final PluginPackageStore packages;
    private final PluginAuditRepository audit;
    private final List<PluginAuditDeliveryFailure> auditFailures = new CopyOnWriteArrayList<>();

    /** packages 必须是构造 engine 时使用的同一 store；Registry 不接管任何资源的关闭所有权。 */
    public PluginRegistry(FibraEngine engine, PluginPackageStore packages, PluginAuditRepository audit) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.packages = Objects.requireNonNull(packages, "packages");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public Mono<RegistrySnapshot> install(PluginInstallRequest request) {
        Objects.requireNonNull(request, "request");
        return mutate("install", request.source().toString(), snapshot -> {
            try (var transaction = packages.prepareInstall(request.source())) {
                var candidate = transaction.candidate();
                var selections = selections(snapshot);
                if (selections.containsKey(candidate.pluginId())) {
                    throw new IllegalArgumentException("package is already selected: " + candidate.pluginId().value());
                }
                var published = transaction.save();
                selections.put(published.pluginId(), new PluginSelection(published.pluginId(),
                    published.packageRevision(), request.enabled()));
                return replace(snapshot, selections.values(), graph(snapshot), context(snapshot));
            }
        });
    }

    public Mono<RegistrySnapshot> upgrade(Path source) {
        var packageSource = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        return mutate("upgrade", packageSource.toString(), snapshot -> {
            try (var transaction = packages.prepareInstall(packageSource)) {
                var candidate = transaction.candidate();
                var current = requireSelection(snapshot, candidate.pluginId());
                var published = transaction.save();
                var selections = selections(snapshot);
                selections.put(published.pluginId(), new PluginSelection(published.pluginId(),
                    published.packageRevision(), current.enabled()));
                return replace(snapshot, selections.values(), graph(snapshot), context(snapshot));
            }
        });
    }

    public Mono<RegistrySnapshot> enablePackage(PluginId pluginId) {
        return packageGate(pluginId, true);
    }

    public Mono<RegistrySnapshot> disablePackage(PluginId pluginId) {
        return packageGate(pluginId, false);
    }

    private Mono<RegistrySnapshot> packageGate(PluginId pluginId, boolean enabled) {
        Objects.requireNonNull(pluginId, "pluginId");
        return mutate(enabled ? "enable-package" : "disable-package", pluginId.value(), snapshot -> {
            var current = requireSelection(snapshot, pluginId);
            var selections = selections(snapshot);
            selections.put(pluginId, new PluginSelection(pluginId, current.packageRevision(), enabled));
            return replace(snapshot, selections.values(), graph(snapshot), context(snapshot));
        });
    }

    /** 逻辑卸载仅移除 selection；不可变 package 内容由其 store 保留。 */
    public Mono<RegistrySnapshot> uninstall(PluginId pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        return mutate("uninstall", pluginId.value(), snapshot -> {
            requireSelection(snapshot, pluginId);
            var graph = graph(snapshot);
            if (graph.plugins().values().stream().anyMatch(entry ->
                entry.definitionRef().pluginId().equals(pluginId.value()))) {
                throw new IllegalArgumentException("remove all desired entries referencing package before uninstall: " + pluginId.value());
            }
            var selections = selections(snapshot);
            selections.remove(pluginId);
            return replace(snapshot, selections.values(), graph, context(snapshot));
        });
    }

    public Mono<RegistrySnapshot> deploy(PluginDeploymentRequest request) {
        Objects.requireNonNull(request, "request");
        return mutate("deploy", "deployment", snapshot -> replace(snapshot, request.selections(),
            request.graph(), request.configContext()));
    }

    public Mono<RegistrySnapshot> upsert(String parentId, DesiredInputEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return mutate("upsert", entry.id(), snapshot -> replaceGraph(snapshot, graph(snapshot).upsert(parentId, entry)));
    }

    public Mono<RegistrySnapshot> enable(String entryId) {
        requireName(entryId, "entryId");
        return mutate("enable-entry", entryId, snapshot -> replaceGraph(snapshot, graph(snapshot).withEnabled(entryId, true)));
    }

    public Mono<RegistrySnapshot> disable(String entryId) {
        requireName(entryId, "entryId");
        return mutate("disable-entry", entryId, snapshot -> replaceGraph(snapshot, graph(snapshot).withEnabled(entryId, false)));
    }

    public Mono<RegistrySnapshot> move(String entryId, String parentId, int position) {
        requireName(entryId, "entryId");
        return mutate("move-entry", entryId, snapshot -> replaceGraph(snapshot, graph(snapshot).move(entryId, parentId, position)));
    }

    public Mono<RegistrySnapshot> remove(String entryId) {
        requireName(entryId, "entryId");
        return mutate("remove-entry", entryId, snapshot -> replaceGraph(snapshot, graph(snapshot).remove(entryId)));
    }

    public Mono<RegistrySnapshot> reconcileCurrent() {
        return mutate("reconcile", "deployment", snapshot -> new ReconcileCurrent());
    }

    public RegistrySnapshot snapshot() { return project(engine.published().current()); }

    public Optional<RegistryPluginState> get(String entryId) {
        requireName(entryId, "entryId");
        var snapshot = snapshot();
        var desired = snapshot.desiredGraph().plugins().get(entryId);
        var observed = snapshot.observed().get(entryId);
        return desired == null && observed == null ? Optional.empty()
            : Optional.of(new RegistryPluginState(entryId, desired, observed));
    }

    public List<RegistryPluginState> list() {
        var snapshot = snapshot();
        var desired = snapshot.desiredGraph().plugins();
        var observed = snapshot.observed();
        var ids = new TreeSet<String>();
        ids.addAll(desired.keySet());
        ids.addAll(observed.keySet());
        return ids.stream().map(id -> new RegistryPluginState(id, desired.get(id), observed.get(id))).toList();
    }

    /** 跟随 Engine 事实变化；当前事实由 snapshot() 读取，审计失败不单独触发此流。 */
    public Flux<RegistrySnapshot> watch() { return engine.published().views().map(this::project); }

    public List<PluginAuditEntry> history() { return audit.history(); }
    public List<PluginAuditDeliveryFailure> auditFailures() { return List.copyOf(auditFailures); }

    private Mono<RegistrySnapshot> mutate(String operation, String target,
                                          Function<PublishedView, EngineCommand> command) {
        return Mono.defer(() -> {
            var before = engine.published().current();
            final EngineCommand prepared;
            try {
                prepared = command.apply(before);
            } catch (RuntimeException failure) {
                append(operation, target, false, TargetSaveState.NOT_SAVED,
                    before.viewRevision(), failure.toString());
                return Mono.error(failure);
            }
            return engine.submit(prepared)
                .doOnSuccess(result -> append(operation, target, true,
                    completedSaveState(prepared, before, result.view()), result.view().viewRevision(), "accepted"))
                .doOnError(failure -> append(operation, target, false,
                    failure instanceof EngineChangeException change ? change.targetSaveState()
                        : prepared instanceof ReconcileCurrent ? TargetSaveState.NOT_APPLICABLE : TargetSaveState.NOT_SAVED,
                    failure instanceof EngineChangeException change ? change.view().viewRevision() : before.viewRevision(),
                    failure.toString()))
                .map(EngineCommandResult::view).map(this::project);
        });
    }

    private static TargetSaveState completedSaveState(EngineCommand command, PublishedView before, PublishedView after) {
        if (command instanceof ReconcileCurrent) return TargetSaveState.NOT_APPLICABLE;
        var previous = before.engine().target();
        var current = after.engine().target();
        return previous.map(DeploymentTarget::targetRevision).equals(current.map(DeploymentTarget::targetRevision))
            && previous.map(DeploymentTarget::targetDigest).equals(current.map(DeploymentTarget::targetDigest))
            ? TargetSaveState.NOT_APPLICABLE : TargetSaveState.SAVED;
    }

    private static ApplyDeployment replaceGraph(PublishedView snapshot, DesiredInputGraph graph) {
        return replace(snapshot, selections(snapshot).values(), graph, context(snapshot));
    }

    private static ApplyDeployment replace(PublishedView snapshot, Collection<PluginSelection> selections,
                                            DesiredInputGraph graph, ConfigContextSnapshot context) {
        return ApplyDeployment.builder(graph)
            .expectedRevision(snapshot.engine().target().map(DeploymentTarget::targetRevision).orElse(0L))
            .selections(selections).configContext(context).build();
    }

    private static Map<PluginId, PluginSelection> selections(PublishedView snapshot) {
        return new LinkedHashMap<>(snapshot.engine().target().map(DeploymentTarget::selections).orElse(Map.of()));
    }

    private static PluginSelection requireSelection(PublishedView snapshot, PluginId pluginId) {
        var selection = selections(snapshot).get(pluginId);
        if (selection == null) throw new IllegalArgumentException("package is not selected: " + pluginId.value());
        return selection;
    }

    private static DesiredInputGraph graph(PublishedView snapshot) {
        return snapshot.engine().target().map(DeploymentTarget::desiredGraph).orElseGet(() -> new DesiredInputGraph(List.of()));
    }

    private static ConfigContextSnapshot context(PublishedView snapshot) {
        return snapshot.engine().target().map(DeploymentTarget::configContext).orElse(ConfigContextSnapshot.empty());
    }

    private void append(String operation, String target, boolean succeeded,
                        TargetSaveState targetSaveState, String revision, String detail) {
        try {
            audit.append(operation, target, succeeded, targetSaveState, revision, detail);
        } catch (RuntimeException failure) {
            auditFailures.add(PluginAuditDeliveryFailure.builder()
                .timestamp(java.time.Instant.now()).operation(operation).target(target)
                .succeeded(succeeded).targetSaveState(targetSaveState)
                .viewRevision(revision).detail(failure.toString()).build());
        }
    }

    private RegistrySnapshot project(PublishedView view) {
        return RegistrySnapshot.builder().viewRevision(view.viewRevision()).engine(view.engine())
            .engineDiagnostics(view.engineDiagnostics()).auditFailures(auditFailures()).build();
    }

    private static void requireName(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
