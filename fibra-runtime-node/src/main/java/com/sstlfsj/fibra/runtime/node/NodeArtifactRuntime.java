package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.PluginFacet;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.engine.ArtifactRuntime;
import com.sstlfsj.fibra.engine.DeploymentTargetCompiler;
import com.sstlfsj.fibra.engine.PreparedArtifact;
import com.sstlfsj.fibra.engine.PreparedArtifactUpdate;
import com.sstlfsj.fibra.engine.RuntimeArtifactInspection;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Node facet 的静态制品准备；sidecar 和 contribution 注册属于 execution 面。 */
public final class NodeArtifactRuntime implements ArtifactRuntime {
    public static final RuntimeId RUNTIME_ID = new RuntimeId("node");

    private final NodeFacetDescriptorReader descriptors = new NodeFacetDescriptorReader();
    private final AtomicLong resourceSequence = new AtomicLong();
    private Map<ArtifactId, NodePreparedArtifact> active = Map.of();
    private Update pending;
    private boolean closing;
    private Mono<Void> close;

    @Override
    public RuntimeId id() {
        return RUNTIME_ID;
    }

    @Override
    public Mono<Void> probe(PluginFacet source) {
        return Mono.<Void>fromRunnable(() -> {
            requireRuntime(source);
            descriptors.read(source);
        });
    }

    @Override
    public Mono<RuntimeArtifactInspection> inspect(ManagedFacet facet) {
        return Mono.fromCallable(() -> {
            requireRuntime(facet.facet());
            var descriptor = descriptors.read(facet);
            return new RuntimeArtifactInspection(RUNTIME_ID, facet.artifactId(), Map.of(
                "protocol", descriptor.protocol(),
                "entrypoint", descriptor.entrypoint(),
                "contributions", descriptor.contributions().stream()
                    .map(NodeEndpointManifest::name).toList()));
        });
    }

    @Override
    public synchronized PreparedArtifactUpdate createUpdate(
        List<DeploymentTargetCompiler.CompiledFacet> target) {
        if (closing) {
            throw new IllegalStateException("Node artifact runtime is closing");
        }
        if (pending != null) {
            throw new IllegalStateException("Node artifact update is not closed");
        }
        pending = new Update(index(target), Map.copyOf(active));
        return pending;
    }

    @Override
    public synchronized Snapshot snapshot() {
        var resources = new ArrayList<Resource>();
        active.values().forEach(value -> resources.add(value.resource(ResourceState.ACTIVE)));
        if (pending != null) {
            resources.addAll(pending.resources());
        }
        return new Snapshot(RUNTIME_ID, resources);
    }

    @Override
    public synchronized Mono<Void> closeAsync() {
        if (close == null) {
            closing = true;
            var update = pending;
            close = Mono.defer(() -> update == null ? Mono.<Void>empty()
                    : update.closeAsync())
                .then(Mono.<Void>fromRunnable(() -> {
                    synchronized (NodeArtifactRuntime.this) {
                        active = Map.of();
                    }
                })).cache();
        }
        return close;
    }

    private synchronized void adopt(Update update) {
        if (closing || pending != update) {
            throw new IllegalStateException("Node artifact update is not active");
        }
        active = update.targetResources();
    }

    private synchronized void complete(Update update) {
        if (pending == update) {
            pending = null;
        }
    }

    private static void requireRuntime(PluginFacet facet) {
        Objects.requireNonNull(facet, "facet");
        if (!RUNTIME_ID.equals(facet.runtimeId())) {
            throw new IllegalArgumentException("facet runtime is not Node");
        }
    }

    private static Map<ArtifactId, DeploymentTargetCompiler.CompiledFacet> index(
        List<DeploymentTargetCompiler.CompiledFacet> target) {
        Objects.requireNonNull(target, "target");
        var result = new LinkedHashMap<ArtifactId, DeploymentTargetCompiler.CompiledFacet>();
        for (var compiled : target) {
            Objects.requireNonNull(compiled, "compiled facet");
            requireRuntime(compiled.facet().facet());
            if (result.putIfAbsent(compiled.facet().artifactId(), compiled) != null) {
                throw new IllegalArgumentException("duplicate Node facet "
                    + compiled.facet().artifactId());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private final class Update implements PreparedArtifactUpdate {
        private final Map<ArtifactId, DeploymentTargetCompiler.CompiledFacet> target;
        private final Map<ArtifactId, NodePreparedArtifact> current;
        private final Set<ArtifactId> affected;
        private Map<ArtifactId, NodePreparedArtifact> targetResources;
        private Map<ArtifactId, NodePreparedArtifact> fresh = Map.of();
        private Map<ArtifactId, NodePreparedArtifact> retired = Map.of();
        private Mono<Void> preparation;
        private Mono<Void> close;
        private boolean prepared;
        private boolean adopted;
        private boolean closed;

        private Update(Map<ArtifactId, DeploymentTargetCompiler.CompiledFacet> target,
                       Map<ArtifactId, NodePreparedArtifact> current) {
            this.target = target;
            this.current = current;
            affected = affected(current, target);
        }

        @Override
        public Mono<Void> prepareAsync() {
            synchronized (NodeArtifactRuntime.this) {
                if (closed || closing) {
                    return Mono.error(new IllegalStateException("Node artifact update is closed"));
                }
                if (preparation == null) {
                    preparation = Mono.<Void>fromRunnable(this::prepare).cache();
                }
                return preparation;
            }
        }

        @Override
        public Map<ArtifactId, PreparedArtifact> preparedArtifacts() {
            synchronized (NodeArtifactRuntime.this) {
                requirePrepared();
                return Collections.unmodifiableMap(new LinkedHashMap<ArtifactId,
                    PreparedArtifact>(targetResources));
            }
        }

        @Override
        public Set<ArtifactId> affectedArtifacts() {
            return affected;
        }

        @Override
        public void adopt() {
            synchronized (NodeArtifactRuntime.this) {
                if (closed || adopted) {
                    throw new IllegalStateException("Node artifact update cannot be adopted");
                }
                requirePrepared();
                var previous = new LinkedHashMap<ArtifactId, NodePreparedArtifact>();
                current.forEach((artifactId, resource) -> {
                    if (affected.contains(artifactId)) {
                        previous.put(artifactId, resource);
                    }
                });
                retired = Collections.unmodifiableMap(previous);
                NodeArtifactRuntime.this.adopt(this);
                adopted = true;
            }
        }

        @Override
        public Mono<Void> closeAsync() {
            synchronized (NodeArtifactRuntime.this) {
                if (close == null) {
                    closed = true;
                    var started = preparation == null ? Mono.<Void>empty()
                        : preparation.onErrorResume(ignored -> Mono.empty());
                    close = started.then(Mono.<Void>fromRunnable(() -> {
                        synchronized (NodeArtifactRuntime.this) {
                            fresh = Map.of();
                            retired = Map.of();
                            complete(this);
                        }
                    })).cache();
                }
                return close;
            }
        }

        private void prepare() {
            var next = new LinkedHashMap<ArtifactId, NodePreparedArtifact>();
            var created = new LinkedHashMap<ArtifactId, NodePreparedArtifact>();
            for (var compiled : target.values()) {
                var existing = current.get(compiled.facet().artifactId());
                if (existing != null && existing.matches(compiled)) {
                    next.put(compiled.facet().artifactId(), existing);
                    continue;
                }
                var descriptor = descriptors.read(compiled.facet());
                var prepared = new NodePreparedArtifact(compiled.facet(), compiled.dependencies(), descriptor,
                    "node:" + compiled.facet().artifactId().value() + ':'
                        + resourceSequence.incrementAndGet());
                next.put(compiled.facet().artifactId(), prepared);
                created.put(compiled.facet().artifactId(), prepared);
            }
            synchronized (NodeArtifactRuntime.this) {
                if (closed || closing) {
                    throw new IllegalStateException("Node artifact update is closed");
                }
                targetResources = Collections.unmodifiableMap(next);
                fresh = Collections.unmodifiableMap(created);
                prepared = true;
            }
        }

        private Map<ArtifactId, NodePreparedArtifact> targetResources() {
            requirePrepared();
            return targetResources;
        }

        private List<Resource> resources() {
            if (closed) return List.of();
            var values = adopted ? retired.values() : fresh.values();
            var state = adopted ? ResourceState.RETIRED : ResourceState.PREPARED;
            return values.stream().map(value -> value.resource(state)).toList();
        }

        private void requirePrepared() {
            if (!prepared) {
                throw new IllegalStateException("Node artifact update is not prepared");
            }
        }
    }

    private static Set<ArtifactId> affected(Map<ArtifactId, NodePreparedArtifact> current,
                                            Map<ArtifactId,
                                                DeploymentTargetCompiler.CompiledFacet> target) {
        var result = new LinkedHashSet<ArtifactId>();
        current.forEach((artifactId, prepared) -> {
            var replacement = target.get(artifactId);
            if (replacement == null || !prepared.matches(replacement)) {
                result.add(artifactId);
            }
        });
        target.keySet().stream().filter(artifactId -> !current.containsKey(artifactId))
            .forEach(result::add);
        return Set.copyOf(result);
    }

}
