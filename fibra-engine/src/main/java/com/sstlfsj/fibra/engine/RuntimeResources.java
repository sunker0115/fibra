package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.RuntimeId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Engine 长期持有的 runtime owner 集合；一个 update 只拥有发生变化的资源。 */
final class RuntimeResources {
    private final Map<RuntimeId, PluginRuntimeAdapter> adapters;
    private final PluginCatalog builtIns;
    private final Map<RuntimeId, RuntimeResourceOwner> owners = new LinkedHashMap<>();
    private Map<ArtifactId, ArtifactRecord> artifacts = Map.of();
    private Update pending;
    private boolean closing;
    private final Mono<Void> close = Mono.defer(() -> {
        Update update;
        synchronized (this) {
            closing = true;
            update = pending;
        }
        // 先结束准备/更新清理，再逐个请求 owner 关闭；每个 owner 保护自身失败资源的先决依赖。
        // 一个 runtime 的失败不阻止独立 runtime 回收。
        return closeAll(List.of(Mono.defer(() -> {
                List<Mono<Void>> tasks;
                synchronized (this) {
                    tasks = owners.values().stream()
                        .map(owner -> Mono.defer(owner::closeAsync)).toList();
                }
                return closeAll(tasks);
            }), update == null ? Mono.<Void>empty() : update.closeAsync()));
    }).cache();

    RuntimeResources(Map<RuntimeId, PluginRuntimeAdapter> adapters, PluginCatalog builtIns) {
        this.adapters = Map.copyOf(adapters);
        this.builtIns = Objects.requireNonNull(builtIns, "builtIns");
    }

    synchronized Update createUpdate(Map<ArtifactId, ArtifactRecord> target) {
        if (closing) throw new IllegalStateException("runtime resources are closing");
        if (pending != null) throw new IllegalStateException("runtime resource update is not closed");
        target.forEach((id, record) -> {
            if (!id.equals(record.id())) throw new IllegalArgumentException("artifact identity mismatch");
        });
        pending = new Update(Map.copyOf(target));
        return pending;
    }

    synchronized PluginCatalog catalog() {
        var catalogs = new ArrayList<PluginCatalog>();
        catalogs.add(builtIns);
        owners.values().forEach(owner -> catalogs.add(owner.catalog().plugins()));
        return PluginCatalog.combine(catalogs);
    }

    synchronized boolean matchesTarget(Map<ArtifactId, ArtifactRecord> target) {
        return revisions(new ArrayList<>(artifacts.values()))
            .equals(revisions(new ArrayList<>(target.values())));
    }

    synchronized Map<RuntimeId, RuntimeResourceSnapshot> snapshots() {
        var snapshots = new LinkedHashMap<RuntimeId, RuntimeResourceSnapshot>();
        owners.forEach((id, owner) -> snapshots.put(id, owner.snapshot()));
        return Map.copyOf(snapshots);
    }

    Mono<Void> closeAsync() { return close; }

    final class Update {
        private final Map<ArtifactId, ArtifactRecord> target;
        private final Map<RuntimeId, RuntimeResourceUpdate> changes = new LinkedHashMap<>();
        private boolean started;
        private boolean prepared;
        private boolean adopted;
        private boolean closed;
        private final Mono<Void> preparation = Mono.defer(this::prepare).cache();
        private final Mono<Void> close = Mono.defer(() -> {
            boolean waitForPreparation;
            synchronized (RuntimeResources.this) {
                closed = true;
                waitForPreparation = started;
            }
            return (waitForPreparation ? preparation.onErrorResume(ignored -> Mono.empty()) : Mono.<Void>empty())
                .then(Mono.defer(() -> closeAll(changes.values().stream()
                    .map(update -> Mono.defer(update::closeAsync)).toList())))
                .doOnSuccess(ignored -> {
                    synchronized (RuntimeResources.this) {
                        if (pending == this) pending = null;
                    }
                });
        }).cache();
        private PluginCatalog catalog;

        private Update(Map<ArtifactId, ArtifactRecord> target) { this.target = target; }

        Mono<Void> prepareAsync() {
            return Mono.defer(() -> {
                synchronized (RuntimeResources.this) {
                    if (closed || closing) return Mono.error(
                        new IllegalStateException("runtime resource update is closed"));
                    started = true;
                }
                return preparation;
            });
        }

        private Mono<Void> prepare() {
            var ids = new LinkedHashSet<RuntimeId>();
            artifacts.values().forEach(artifact -> ids.add(artifact.runtimeId()));
            target.values().forEach(artifact -> ids.add(artifact.runtimeId()));
            return Flux.fromIterable(ids.stream().sorted(Comparator.comparing(RuntimeId::value)).toList())
                .concatMap(id -> Mono.defer(() -> {
                    var previous = selected(artifacts, id);
                    var next = selected(target, id);
                    if (revisions(previous).equals(revisions(next))) return Mono.empty();
                    RuntimeResourceOwner owner;
                    synchronized (RuntimeResources.this) {
                        owner = owners.get(id);
                        if (owner == null) {
                            var adapter = adapters.get(id);
                            if (adapter == null) return Mono.error(new UnknownRuntimeException(id));
                            if (!id.equals(adapter.id())) return Mono.error(
                                new IllegalArgumentException("runtime adapter identity mismatch"));
                            owner = Objects.requireNonNull(adapter.create(), "runtime owner");
                            owners.put(id, owner);
                        }
                    }
                    var update = Objects.requireNonNull(owner.createUpdate(next), "runtime update");
                    changes.put(id, update);
                    return Mono.defer(update::prepareAsync).then(Mono.fromRunnable(() -> {
                        if (!id.equals(update.snapshot().runtimeId())) {
                            throw new IllegalArgumentException("runtime resource identity mismatch");
                        }
                    }));
                })).then(Mono.fromRunnable(() -> {
                    synchronized (RuntimeResources.this) {
                        var catalogs = new ArrayList<PluginCatalog>();
                        catalogs.add(builtIns);
                        owners.forEach((id, owner) -> catalogs.add(changes.containsKey(id)
                            ? changes.get(id).catalog().plugins() : owner.catalog().plugins()));
                        catalog = PluginCatalog.combine(catalogs);
                        prepared = true;
                    }
                }));
        }

        PluginCatalog catalog() {
            synchronized (RuntimeResources.this) {
                requirePrepared();
                return catalog;
            }
        }

        Set<ArtifactId> affectedArtifacts() {
            synchronized (RuntimeResources.this) {
                requirePrepared();
                var affected = new LinkedHashSet<ArtifactId>();
                changes.values().forEach(update -> affected.addAll(update.affectedArtifacts()));
                return Set.copyOf(affected);
            }
        }

        void adopt() {
            synchronized (RuntimeResources.this) {
                requirePrepared();
                if (closed || adopted) throw new IllegalStateException("runtime update cannot be adopted");
                changes.values().forEach(RuntimeResourceUpdate::adopt);
                artifacts = target;
                adopted = true;
            }
        }

        Mono<Void> closeAsync() { return close; }

        private void requirePrepared() {
            if (!prepared) throw new IllegalStateException("runtime update is not prepared");
        }
    }

    private static List<ArtifactRecord> selected(Map<ArtifactId, ArtifactRecord> values, RuntimeId id) {
        return values.values().stream().filter(artifact -> id.equals(artifact.runtimeId()))
            .sorted(Comparator.comparing(artifact -> artifact.id().value())).toList();
    }

    private static Map<ArtifactId, String> revisions(List<ArtifactRecord> records) {
        var result = new LinkedHashMap<ArtifactId, String>();
        records.forEach(record -> result.put(record.id(), record.revision()));
        return result;
    }

    private static Mono<Void> closeAll(List<Mono<Void>> tasks) {
        return Mono.defer(() -> {
            var reverse = new ArrayList<>(tasks);
            Collections.reverse(reverse);
            var failures = new ArrayList<Throwable>();
            return Flux.fromIterable(reverse).concatMap(task -> task.onErrorResume(failure -> {
                failures.add(failure);
                return Mono.empty();
            })).then(Mono.defer(() -> {
                if (failures.isEmpty()) return Mono.empty();
                var failure = new IllegalStateException("cannot close runtime resources");
                failures.forEach(failure::addSuppressed);
                return Mono.error(failure);
            }));
        });
    }
}
