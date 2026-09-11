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
import java.util.List;
import java.util.Map;

/** 候选代取得的全部 runtime 句柄；不保存任何其他代的资源。 */
final class RuntimeResources {
    private final List<RuntimeGeneration> owned = new ArrayList<>();
    private final Map<RuntimeId, RuntimeGeneration> generations = new LinkedHashMap<>();
    private final Mono<Void> close = closeAll(owned).cache();
    private PluginCatalog catalog;
    private Map<RuntimeId, RuntimeGenerationSnapshot> snapshots = Map.of();

    Mono<Void> prepare(Map<ArtifactId, ArtifactRecord> artifacts,
                        Map<RuntimeId, PluginRuntimeAdapter> adapters, PluginCatalog builtIns) {
        return Mono.defer(() -> {
            var grouped = new LinkedHashMap<RuntimeId, List<ArtifactRecord>>();
            artifacts.values().stream().sorted(Comparator.comparing(
                (ArtifactRecord value) -> value.runtimeId().value())
                .thenComparing(value -> value.id().value()))
                .forEach(value -> grouped.computeIfAbsent(value.runtimeId(),
                    ignored -> new ArrayList<>()).add(value));
            return Flux.fromIterable(grouped.entrySet()).concatMap(group -> Mono.defer(() -> {
                var id = group.getKey();
                var adapter = adapters.get(id);
                if (adapter == null) return Mono.error(new UnknownRuntimeException(id));
                if (!id.equals(adapter.id())) {
                    return Mono.error(new IllegalArgumentException("runtime adapter identity mismatch"));
                }
                return Flux.fromIterable(group.getValue()).concatMap(artifact ->
                    Mono.defer(() -> adapter.inspect(artifact))
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("runtime inspection is empty")))
                        .doOnNext(inspection -> {
                            if (!id.equals(inspection.runtimeId()) || !artifact.id().equals(inspection.artifactId())) {
                                throw new IllegalArgumentException("runtime inspection identity mismatch");
                            }
                        }))
                    .then(Mono.fromSupplier(() -> adapter.create(new RuntimeGenerationRequest(id, group.getValue()))))
                    .switchIfEmpty(Mono.error(new IllegalArgumentException("runtime generation is empty")))
                    .doOnNext(owned::add)
                    .flatMap(generation -> Mono.defer(generation::prepareAsync).thenReturn(generation))
                    .doOnNext(generation -> {
                        if (!id.equals(generation.snapshot().runtimeId())) {
                            throw new IllegalArgumentException("runtime generation identity mismatch");
                        }
                        generations.put(id, generation);
                    });
            })).then(Mono.fromRunnable(() -> {
                var catalogs = new ArrayList<PluginCatalog>();
                catalogs.add(builtIns);
                var projection = new LinkedHashMap<RuntimeId, RuntimeGenerationSnapshot>();
                generations.forEach((id, generation) -> {
                    catalogs.add(generation.catalog());
                    projection.put(id, generation.snapshot());
                });
                catalog = PluginCatalog.combine(catalogs);
                snapshots = Map.copyOf(projection);
            }));
        });
    }

    PluginCatalog catalog() { return catalog; }
    Map<RuntimeId, RuntimeGenerationSnapshot> snapshots() { return snapshots; }
    Mono<Void> closeAsync() { return close; }

    private static Mono<Void> closeAll(List<RuntimeGeneration> generations) {
        return Mono.defer(() -> {
            var reverse = new ArrayList<>(generations);
            Collections.reverse(reverse);
            var failures = new ArrayList<Throwable>();
            return Flux.fromIterable(reverse).concatMap(generation ->
                Mono.defer(generation::closeAsync).onErrorResume(failure -> {
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
