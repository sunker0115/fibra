package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactPackage;
import com.sstlfsj.fibra.artifact.RuntimeId;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 按安装单元声明的 runtime 路由探测，不创建运行资源或安装制品。 */
public final class PluginArtifactProbe {
    private final Map<RuntimeId, PluginRuntimeAdapter> adapters;

    public PluginArtifactProbe(List<? extends PluginRuntimeAdapter> adapters) {
        var registered = new LinkedHashMap<RuntimeId, PluginRuntimeAdapter>();
        for (var adapter : List.copyOf(adapters)) {
            var id = Objects.requireNonNull(adapter.id(), "runtime id");
            if (registered.putIfAbsent(id, adapter) != null) {
                throw new IllegalArgumentException("duplicate runtime adapter: " + id);
            }
        }
        this.adapters = Map.copyOf(registered);
    }

    public Mono<DeploymentArtifact> probe(Path source) {
        Objects.requireNonNull(source, "source");
        return Mono.fromCallable(() -> ArtifactPackage.read(source))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(artifact -> {
                var adapter = adapters.get(artifact.runtimeId());
                if (adapter == null) {
                    return Mono.error(new IllegalArgumentException(
                        "unknown runtime: " + artifact.runtimeId()));
                }
                return adapter.probe(artifact).map(result -> {
                    if (!result.runtimeId().equals(artifact.runtimeId())
                        || !result.source().equals(artifact.root())) {
                        throw new IllegalStateException(
                            "runtime probe must preserve the installation root and runtime id");
                    }
                    return result;
                });
            });
    }
}
