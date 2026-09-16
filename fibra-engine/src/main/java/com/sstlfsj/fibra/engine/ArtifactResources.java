package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.RuntimeId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Engine 长期持有的制品 runtime 集合；一个 update 只拥有发生变化的资源闭包。 */
final class ArtifactResources {
    private final Map<RuntimeId, ArtifactRuntime> runtimes;
    private Map<ArtifactId, PreparedArtifact> active = Map.of();
    private Update pending;
    private boolean closing;
    private final Mono<Void> close;

    ArtifactResources(Map<RuntimeId, ArtifactRuntime> runtimes) {
        Objects.requireNonNull(runtimes, "runtimes");
        var ordered = new LinkedHashMap<RuntimeId, ArtifactRuntime>();
        runtimes.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(
                Comparator.comparing(RuntimeId::value)))
            .forEach(entry -> {
                if (!entry.getKey().equals(entry.getValue().id())) {
                    throw new IllegalArgumentException(
                        "artifact runtime identity mismatch");
                }
                ordered.put(entry.getKey(), entry.getValue());
        });
        this.runtimes = Collections.unmodifiableMap(ordered);
        close = Mono.defer(() -> {
            Update update;
            synchronized (this) {
                closing = true;
                update = pending;
            }
            return closeAll(List.of(
                Mono.defer(() -> closeAll(this.runtimes.values().stream()
                    .map(runtime -> Mono.defer(runtime::closeAsync)).toList())),
                update == null ? Mono.<Void>empty() : update.closeAsync()));
        }).cache();
    }

    synchronized Update createUpdate(DeploymentTargetCompiler.Compilation target) {
        Objects.requireNonNull(target, "target");
        if (closing) throw new IllegalStateException("artifact resources are closing");
        if (pending != null) {
            throw new IllegalStateException("artifact resource update is not closed");
        }
        pending = new Update(target);
        return pending;
    }

    synchronized Map<ArtifactId, PreparedArtifact> preparedArtifacts() {
        return active;
    }

    synchronized Map<RuntimeId, ArtifactRuntime.Snapshot> snapshots() {
        var result = new LinkedHashMap<RuntimeId, ArtifactRuntime.Snapshot>();
        runtimes.forEach((id, runtime) -> {
            var snapshot = Objects.requireNonNull(runtime.snapshot(),
                "artifact runtime snapshot");
            if (!id.equals(snapshot.runtimeId())) {
                throw new IllegalArgumentException(
                    "artifact runtime snapshot identity mismatch");
            }
            result.put(id, snapshot);
        });
        return Collections.unmodifiableMap(result);
    }

    Mono<Void> closeAsync() { return close; }

    final class Update implements PreparedArtifactUpdate {
        private final DeploymentTargetCompiler.Compilation target;
        private final Map<RuntimeId, PreparedArtifactUpdate> changes =
            new LinkedHashMap<>();
        private final Map<RuntimeId, Map<ArtifactId, PreparedArtifact>>
            preparedChanges = new LinkedHashMap<>();
        private Map<ArtifactId, PreparedArtifact> prepared;
        private boolean started;
        private boolean preparedSuccessfully;
        private boolean adopted;
        private boolean adoptFailed;
        private boolean closed;
        private final Mono<Void> preparation = Mono.defer(this::prepare).cache();
        private final Mono<Void> close = Mono.defer(() -> {
            boolean waitForPreparation;
            synchronized (ArtifactResources.this) {
                closed = true;
                waitForPreparation = started;
            }
            return (waitForPreparation
                ? preparation.onErrorResume(ignored -> Mono.empty())
                : Mono.<Void>empty())
                .then(Mono.defer(() -> closeAll(changes.values().stream()
                    .map(update -> Mono.defer(update::closeAsync)).toList())))
                .doOnSuccess(ignored -> {
                    synchronized (ArtifactResources.this) {
                        if (pending == this && !adoptFailed) pending = null;
                    }
                });
        }).cache();

        private Update(DeploymentTargetCompiler.Compilation target) {
            this.target = target;
        }

        @Override
        public Mono<Void> prepareAsync() {
            return Mono.defer(() -> {
                synchronized (ArtifactResources.this) {
                    if (closed || closing) {
                        return Mono.error(new IllegalStateException(
                            "artifact resource update is closed"));
                    }
                    started = true;
                }
                return preparation;
            });
        }

        private Mono<Void> prepare() {
            var targetByRuntime = targetByRuntime(target.facets().values());
            var activeByRuntime = activeByRuntime(active.values());
            var runtimeIds = new LinkedHashSet<RuntimeId>();
            runtimeIds.addAll(activeByRuntime.keySet());
            runtimeIds.addAll(targetByRuntime.keySet());
            var orderedIds = runtimeIds.stream()
                .sorted(Comparator.comparing(RuntimeId::value)).toList();
            return Flux.fromIterable(orderedIds).concatMap(runtimeId -> Mono.defer(() -> {
                var previous = activeByRuntime.getOrDefault(runtimeId, List.of());
                var next = targetByRuntime.getOrDefault(runtimeId, List.of());
                if (matches(previous, next)) return Mono.empty();
                var runtime = runtimes.get(runtimeId);
                if (runtime == null) {
                    return Mono.error(new IllegalArgumentException(
                        "unknown artifact runtime " + runtimeId));
                }
                var update = Objects.requireNonNull(
                    runtime.createUpdate(List.copyOf(next)),
                    "prepared artifact update");
                changes.put(runtimeId, update);
                return Mono.defer(update::prepareAsync).then(Mono.fromRunnable(() -> {
                    var snapshot = Map.copyOf(Objects.requireNonNull(
                        update.preparedArtifacts(), "preparedArtifacts"));
                    validatePrepared(runtimeId, next, snapshot);
                    preparedChanges.put(runtimeId, snapshot);
                }));
            })).then(Mono.fromRunnable(() -> {
                synchronized (ArtifactResources.this) {
                    var next = new LinkedHashMap<ArtifactId, PreparedArtifact>();
                    for (var entry : targetByRuntime.entrySet()) {
                        var runtimeUpdate = changes.get(entry.getKey());
                        if (runtimeUpdate == null) {
                            activeByRuntime.getOrDefault(entry.getKey(), List.of())
                                .forEach(value -> next.put(
                                    value.facet().artifactId(), value));
                        } else {
                            next.putAll(preparedChanges.get(entry.getKey()));
                        }
                    }
                    prepared = Collections.unmodifiableMap(next);
                    preparedSuccessfully = true;
                }
            }));
        }

        @Override
        public Map<ArtifactId, PreparedArtifact> preparedArtifacts() {
            synchronized (ArtifactResources.this) {
                requirePrepared();
                return prepared;
            }
        }

        @Override
        public Set<ArtifactId> affectedArtifacts() {
            synchronized (ArtifactResources.this) {
                requirePrepared();
                var result = new LinkedHashSet<ArtifactId>();
                changes.values().forEach(update ->
                    result.addAll(update.affectedArtifacts()));
                return Set.copyOf(result);
            }
        }

        @Override
        public void adopt() {
            synchronized (ArtifactResources.this) {
                requirePrepared();
                if (closed || adopted || adoptFailed) {
                    throw new IllegalStateException(
                        "artifact resource update cannot be adopted");
                }
                for (var entry : changes.entrySet()) {
                    try {
                        entry.getValue().adopt();
                    } catch (RuntimeException | Error failure) {
                        adoptFailed = true;
                        throw failure;
                    }
                    active = replaceRuntime(active, entry.getKey(),
                        preparedChanges.get(entry.getKey()));
                }
                active = prepared;
                adopted = true;
            }
        }

        @Override
        public Mono<Void> closeAsync() { return close; }

        private void requirePrepared() {
            if (!preparedSuccessfully) {
                throw new IllegalStateException(
                    "artifact resource update is not prepared");
            }
        }
    }

    private static Map<RuntimeId, List<DeploymentTargetCompiler.CompiledFacet>>
    targetByRuntime(Collection<DeploymentTargetCompiler.CompiledFacet> values) {
        var result = new LinkedHashMap<RuntimeId,
            List<DeploymentTargetCompiler.CompiledFacet>>();
        values.stream().sorted(Comparator.comparing(value ->
                value.facet().artifactId().value()))
            .forEach(value -> result.computeIfAbsent(
                value.facet().facet().runtimeId(), ignored -> new ArrayList<>()).add(value));
        return result;
    }

    private static Map<RuntimeId, List<PreparedArtifact>> activeByRuntime(
        Collection<PreparedArtifact> values) {
        var result = new LinkedHashMap<RuntimeId, List<PreparedArtifact>>();
        values.stream().sorted(Comparator.comparing(value ->
                value.facet().artifactId().value()))
            .forEach(value -> result.computeIfAbsent(
                value.facet().facet().runtimeId(), ignored -> new ArrayList<>()).add(value));
        return result;
    }

    private static boolean matches(List<PreparedArtifact> active,
                                   List<DeploymentTargetCompiler.CompiledFacet> target) {
        if (active.size() != target.size()) return false;
        var indexed = new LinkedHashMap<ArtifactId, PreparedArtifact>();
        active.forEach(value -> indexed.put(value.facet().artifactId(), value));
        return target.stream().allMatch(value -> {
            var current = indexed.get(value.facet().artifactId());
            return current != null && current.facet().equals(value.facet())
                && current.dependencies().equals(value.dependencies());
        });
    }

    private static Map<ArtifactId, PreparedArtifact> replaceRuntime(
        Map<ArtifactId, PreparedArtifact> current,
        RuntimeId runtimeId,
        Map<ArtifactId, PreparedArtifact> replacement) {
        var next = new LinkedHashMap<ArtifactId, PreparedArtifact>();
        current.forEach((artifactId, value) -> {
            if (!runtimeId.equals(value.facet().facet().runtimeId())) {
                next.put(artifactId, value);
            }
        });
        next.putAll(replacement);
        return Collections.unmodifiableMap(next);
    }

    private static void validatePrepared(
        RuntimeId runtimeId,
        List<DeploymentTargetCompiler.CompiledFacet> target,
        Map<ArtifactId, PreparedArtifact> prepared) {
        Objects.requireNonNull(prepared, "preparedArtifacts");
        var expected = target.stream().collect(java.util.stream.Collectors.toMap(
            value -> value.facet().artifactId(), value -> value));
        if (!prepared.keySet().equals(expected.keySet())) {
            throw new IllegalArgumentException(
                "prepared artifacts do not match the runtime target");
        }
        prepared.forEach((artifactId, value) -> {
            var compiled = expected.get(artifactId);
            if (!artifactId.equals(value.facet().artifactId())
                || !runtimeId.equals(value.facet().facet().runtimeId())
                || !compiled.facet().equals(value.facet())
                || !compiled.dependencies().equals(value.dependencies())) {
                throw new IllegalArgumentException(
                    "prepared artifact identity mismatch");
            }
        });
    }

    private static Mono<Void> closeAll(List<Mono<Void>> tasks) {
        return Mono.defer(() -> {
            var reverse = new ArrayList<>(tasks);
            Collections.reverse(reverse);
            var failures = new ArrayList<Throwable>();
            return Flux.fromIterable(reverse).concatMap(task ->
                task.onErrorResume(failure -> {
                    failures.add(failure);
                    return Mono.empty();
                })).then(Mono.defer(() -> {
                    if (failures.isEmpty()) return Mono.empty();
                    var failure = new IllegalStateException(
                        "cannot close artifact resources");
                    failures.forEach(failure::addSuppressed);
                    return Mono.error(failure);
                }));
        });
    }
}
