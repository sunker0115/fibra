package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.PluginFacet;
import com.sstlfsj.fibra.artifact.RuntimeId;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

/** Engine 长期独占的制品探测、准备和不可变资源所有者。 */
public interface ArtifactRuntime {
    RuntimeId id();
    Mono<Void> probe(PluginFacet source);
    Mono<RuntimeArtifactInspection> inspect(ManagedFacet facet);
    /** 只创建可登记句柄，不执行 I/O。 */
    PreparedArtifactUpdate createUpdate(
        List<DeploymentTargetCompiler.CompiledFacet> target);
    Snapshot snapshot();
    Mono<Void> closeAsync();

    /** 当前活动资源及清理失败后保留资源的不可变诊断快照。 */
    record Snapshot(RuntimeId runtimeId, List<Resource> resources) {
        public Snapshot {
            Objects.requireNonNull(runtimeId, "runtimeId");
            resources = List.copyOf(resources);
            if (resources.stream().map(Resource::identity).distinct().count()
                != resources.size()) {
                throw new IllegalArgumentException(
                    "duplicate artifact resource identity");
            }
            if (resources.stream().anyMatch(resource -> !runtimeId.equals(
                resource.facet().facet().runtimeId()))) {
                throw new IllegalArgumentException(
                    "artifact resource belongs to another runtime");
            }
        }
    }

    record Resource(ManagedFacet facet, String identity, ResourceState state,
                    String failure) {
        public Resource {
            Objects.requireNonNull(facet, "facet");
            if (identity == null || identity.isBlank()) {
                throw new IllegalArgumentException(
                    "resource identity must not be blank");
            }
            Objects.requireNonNull(state, "state");
            if ((state == ResourceState.CLOSE_FAILED) != (failure != null)) {
                throw new IllegalArgumentException(
                    "only CLOSE_FAILED resources carry failure");
            }
            if (failure != null && failure.isBlank()) {
                throw new IllegalArgumentException(
                    "resource failure must not be blank");
            }
        }
    }

    enum ResourceState { PREPARED, ACTIVE, RETIRED, CLOSED, CLOSE_FAILED }
}
