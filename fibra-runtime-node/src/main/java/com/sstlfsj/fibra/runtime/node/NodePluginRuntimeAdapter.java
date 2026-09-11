package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.bridge.ContributionBinding;
import com.sstlfsj.fibra.bridge.ContributionCodec;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.engine.PluginCatalog;
import com.sstlfsj.fibra.engine.PluginCatalogEntry;
import com.sstlfsj.fibra.engine.PluginRuntimeAdapter;
import com.sstlfsj.fibra.engine.RuntimeArtifactInspection;
import com.sstlfsj.fibra.engine.RuntimeCatalog;
import com.sstlfsj.fibra.engine.RuntimeResourceOwner;
import com.sstlfsj.fibra.engine.RuntimeResourceSnapshot;
import com.sstlfsj.fibra.engine.RuntimeResourceUpdate;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class NodePluginRuntimeAdapter implements PluginRuntimeAdapter {
    public static final RuntimeId RUNTIME_ID = new RuntimeId("node");

    private final NodeContributionKindResolver kinds;
    private final NodeRuntimeOptions options;
    private final NodeManifestReader manifests = new NodeManifestReader();

    public NodePluginRuntimeAdapter(NodeContributionKindResolver kinds,
                                    NodeRuntimeOptions options) {
        this.kinds = Objects.requireNonNull(kinds, "kinds");
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public RuntimeId id() {
        return RUNTIME_ID;
    }

    @Override
    public Mono<RuntimeArtifactInspection> inspect(ArtifactRecord artifact) {
        return Mono.fromCallable(() -> {
            requireRuntime(artifact);
            var manifest = manifests.read(artifact);
            validateKinds(manifest);
            return new RuntimeArtifactInspection(RUNTIME_ID, artifact.id(), Map.of(
                "entrypoint", manifest.entrypoint(),
                "protocol", manifest.protocol(),
                "contributions", manifest.contributions().stream()
                    .map(NodeEndpointManifest::name).toList()));
        });
    }

    @Override
    public RuntimeResourceOwner create() {
        return new Owner();
    }

    private PluginCatalogEntry<Object> catalogEntry(ArtifactRecord artifact,
                                                     NodePluginManifest manifest) {
        var definition = PluginDefinition.builder(manifest.artifactId().value(),
            Object.class, () -> (context, config) -> {
                var provider = context.plugins().current().orElseThrow(() ->
                    new IllegalStateException("Node plugin requires a plugin instance"));
                var registrar = context.services().require(
                    ContributionServices.REGISTRAR);
                return NodeSidecar.start(
                        artifact.location().resolve(manifest.entrypoint()), options,
                        context.plugins()::requestDisable)
                    .flatMap(sidecar -> sidecar.request("fibra.start", Map.of(
                            "protocol", manifest.protocol(),
                            "config", config == null ? Map.of() : config),
                            options.defaultRequestTimeout())
                        .then(registrar.registerAll(context, provider.id(),
                            bindings(manifest, sidecar), closeAfterDrain(sidecar)))
                        .then(Mono.fromRunnable(() -> context.effects().supervise(
                            sidecar.termination(), "node-sidecar:" + provider.id())))
                        .then()
                        .onErrorResume(failure -> {
                            sidecar.close();
                            return Mono.error(failure);
                        }));
            }).require(ContributionServices.REGISTRAR).build();
        return new PluginCatalogEntry<>(definition, value -> value);
    }

    private Disposable closeAfterDrain(NodeSidecar sidecar) {
        return () -> sidecar.request("fibra.stop", Map.of(),
                Duration.ofMillis(Math.min(1000,
                    options.defaultRequestTimeout().toMillis())))
            .onErrorResume(ignored -> Mono.empty())
            .then(Mono.fromRunnable(sidecar::close));
    }

    private List<ContributionBinding<?, ?, ?>> bindings(
        NodePluginManifest manifest, NodeSidecar sidecar) {
        List<ContributionBinding<?, ?, ?>> result = new ArrayList<>();
        manifest.contributions().forEach(endpoint ->
            result.add(binding(endpoint, sidecar)));
        return List.copyOf(result);
    }

    private <D, I, O> ContributionBinding<D, I, O> binding(
        NodeEndpointManifest endpoint, NodeSidecar sidecar) {
        @SuppressWarnings("unchecked")
        var kind = (ContributionKind<D, I, O>) kinds.find(endpoint.kind())
            .orElseThrow(() -> new NodeRuntimeException(null,
                "unknown contribution kind " + endpoint.kind(), null));
        var codec = kind.codec().orElseThrow(() -> new NodeRuntimeException(null,
            "contribution kind is not remote: " + endpoint.kind(), null));
        if (codec.schemaVersion() != endpoint.schemaVersion()) {
            throw new NodeRuntimeException(null,
                "schema version mismatch for contribution " + endpoint.name(), null);
        }
        var descriptor = codec.decodeDescriptor(endpoint.descriptor());
        return new ContributionBinding<>(kind, endpoint.name(), descriptor,
            (invocation, input) -> sidecar.request(endpoint.method(), Map.of(
                    "schemaVersion", codec.schemaVersion(),
                    "input", codec.encodeInput(input)))
                .map(codec::decodeOutput));
    }

    private void validateKinds(NodePluginManifest manifest) {
        manifest.contributions().forEach(endpoint -> {
            var kind = kinds.find(endpoint.kind()).orElseThrow(() ->
                new NodeRuntimeException(manifest.artifactId(),
                    "unknown contribution kind " + endpoint.kind(), null));
            var codec = kind.codec().orElseThrow(() ->
                new NodeRuntimeException(manifest.artifactId(),
                    "contribution kind is not remote: " + endpoint.kind(), null));
            if (codec.schemaVersion() != endpoint.schemaVersion()) {
                throw new NodeRuntimeException(manifest.artifactId(),
                    "schema version mismatch for contribution " + endpoint.name(), null);
            }
            try {
                codec.decodeDescriptor(endpoint.descriptor());
            } catch (RuntimeException failure) {
                throw new NodeRuntimeException(manifest.artifactId(),
                    "invalid descriptor for contribution " + endpoint.name(), failure);
            }
        });
    }

    private static void requireRuntime(ArtifactRecord artifact) {
        if (!artifact.runtimeId().equals(RUNTIME_ID)) {
            throw new IllegalArgumentException("artifact runtime is not Node");
        }
    }

    private final class Owner implements RuntimeResourceOwner {
        private Map<ArtifactId, ArtifactResource> active = Map.of();
        private RuntimeCatalog catalog = RuntimeCatalog.empty();
        private Update pending;
        private boolean closed;
        private Mono<Void> close;

        @Override
        public synchronized RuntimeResourceUpdate createUpdate(List<ArtifactRecord> target) {
            if (closed) {
                throw new IllegalStateException("Node runtime resource owner is closed");
            }
            if (pending != null) {
                throw new IllegalStateException("Node runtime resource update is not closed");
            }
            pending = new Update(this, target, active);
            return pending;
        }

        @Override
        public synchronized RuntimeCatalog catalog() {
            return catalog;
        }

        @Override
        public synchronized RuntimeResourceSnapshot snapshot() {
            var resources = new ArrayList<>(active.values().stream()
                .map(resource -> resource.snapshot(RuntimeResourceSnapshot.State.ACTIVE)).toList());
            if (pending != null) {
                resources.addAll(pending.resourcesUnsafe());
            }
            return new RuntimeResourceSnapshot(RUNTIME_ID, resources);
        }

        @Override
        public synchronized Mono<Void> closeAsync() {
            if (close == null) {
                closed = true;
                var current = pending;
                close = Mono.defer(() -> current == null ? Mono.<Void>empty()
                    : current.closeAsync())
                    .then(Mono.<Void>fromRunnable(() -> {
                        synchronized (Owner.this) {
                            active = Map.of();
                            catalog = RuntimeCatalog.empty();
                        }
                    })).cache();
            }
            return close;
        }

        private synchronized void adopt(Update update) {
            if (closed) {
                throw new IllegalStateException("Node runtime resource owner is closed");
            }
            if (pending != update) {
                throw new IllegalStateException("Node runtime resource update is not active");
            }
            active = update.targetResourcesUnsafe();
            catalog = update.targetCatalogUnsafe();
        }

        private synchronized void complete(Update update) {
            if (pending == update) {
                pending = null;
            }
        }
    }

    private final class Update implements RuntimeResourceUpdate {
        private final Owner owner;
        private final Map<ArtifactId, ArtifactRecord> target;
        private final Map<ArtifactId, ArtifactResource> current;
        private final Set<ArtifactId> affected;
        private Map<ArtifactId, ArtifactResource> targetResources;
        private Map<ArtifactId, ArtifactResource> freshResources = Map.of();
        private Map<ArtifactId, ArtifactResource> retiredResources = Map.of();
        private RuntimeCatalog targetCatalog;
        private List<RuntimeResourceSnapshot.Resource> closedResources = List.of();
        private Mono<Void> preparation;
        private Mono<Void> close;
        private boolean prepared;
        private boolean adopted;
        private boolean closed;
        private boolean released;

        private Update(Owner owner, List<ArtifactRecord> target,
                       Map<ArtifactId, ArtifactResource> current) {
            this.owner = owner;
            this.target = index(target);
            this.current = Map.copyOf(current);
            affected = affected(this.current, this.target);
        }

        @Override
        public Mono<Void> prepareAsync() {
            return Mono.defer(() -> {
                synchronized (owner) {
                    if (closed) {
                        return Mono.error(new IllegalStateException(
                            "Node runtime resource update is closed"));
                    }
                    if (preparation == null) {
                        preparation = Mono.<Void>fromRunnable(this::prepare).cache();
                    }
                    return preparation;
                }
            });
        }

        @Override
        public Set<ArtifactId> affectedArtifacts() {
            return affected;
        }

        @Override
        public RuntimeCatalog catalog() {
            synchronized (owner) {
                requirePrepared();
                return targetCatalog;
            }
        }

        @Override
        public RuntimeResourceSnapshot snapshot() {
            synchronized (owner) {
                return new RuntimeResourceSnapshot(RUNTIME_ID, resourcesUnsafe());
            }
        }

        @Override
        public void adopt() {
            synchronized (owner) {
                if (closed) {
                    throw new IllegalStateException("Node runtime resource update is closed");
                }
                requirePrepared();
                if (adopted) {
                    throw new IllegalStateException("Node runtime resource update is adopted");
                }
                retiredResources = current.entrySet().stream()
                    .filter(entry -> affected.contains(entry.getKey()))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        Map.Entry::getValue, (left, right) -> right, LinkedHashMap::new));
                owner.adopt(this);
                adopted = true;
            }
        }

        @Override
        public Mono<Void> closeAsync() {
            synchronized (owner) {
                if (close == null) {
                    closed = true;
                    var started = preparation == null ? Mono.<Void>empty()
                        : preparation.onErrorResume(ignored -> Mono.empty());
                    close = started.then(Mono.<Void>fromRunnable(this::release)).cache();
                }
                return close;
            }
        }

        private void prepare() {
            synchronized (owner) {
                if (closed) {
                    throw new IllegalStateException("Node runtime resource update is closed");
                }
            }
            var next = new LinkedHashMap<ArtifactId, ArtifactResource>();
            var fresh = new LinkedHashMap<ArtifactId, ArtifactResource>();
            for (var artifact : target.values()) {
                var existing = current.get(artifact.id());
                if (existing != null && !affected.contains(artifact.id())) {
                    next.put(artifact.id(), existing);
                    continue;
                }
                requireRuntime(artifact);
                var manifest = manifests.read(artifact);
                validateKinds(manifest);
                var resource = new ArtifactResource(artifact, catalogEntry(artifact, manifest));
                next.put(artifact.id(), resource);
                fresh.put(artifact.id(), resource);
            }
            var catalog = NodePluginRuntimeAdapter.catalog(next);
            synchronized (owner) {
                if (closed) {
                    throw new IllegalStateException("Node runtime resource update is closed");
                }
                targetResources = Map.copyOf(next);
                freshResources = Map.copyOf(fresh);
                targetCatalog = catalog;
                prepared = true;
            }
        }

        private void release() {
            synchronized (owner) {
                var owned = adopted ? retiredResources : freshResources;
                closedResources = owned.values().stream()
                    .map(resource -> resource.snapshot(RuntimeResourceSnapshot.State.CLOSED)).toList();
                released = true;
                owner.complete(this);
            }
        }

        private Map<ArtifactId, ArtifactResource> targetResourcesUnsafe() {
            requirePrepared();
            return targetResources;
        }

        private RuntimeCatalog targetCatalogUnsafe() {
            requirePrepared();
            return targetCatalog;
        }

        private List<RuntimeResourceSnapshot.Resource> resourcesUnsafe() {
            if (released) {
                return closedResources;
            }
            if (adopted) {
                return retiredResources.values().stream()
                    .map(resource -> resource.snapshot(RuntimeResourceSnapshot.State.RETIRED)).toList();
            }
            return freshResources.values().stream()
                .map(resource -> resource.snapshot(RuntimeResourceSnapshot.State.PREPARED)).toList();
        }

        private void requirePrepared() {
            if (!prepared) {
                throw new IllegalStateException("Node runtime resource update is not prepared");
            }
        }
    }

    private static Map<ArtifactId, ArtifactRecord> index(List<ArtifactRecord> artifacts) {
        Objects.requireNonNull(artifacts, "target");
        var result = new LinkedHashMap<ArtifactId, ArtifactRecord>();
        for (var artifact : artifacts) {
            Objects.requireNonNull(artifact, "artifact");
            if (result.putIfAbsent(artifact.id(), artifact) != null) {
                throw new IllegalArgumentException("duplicate Node artifact " + artifact.id().value());
            }
        }
        return Map.copyOf(result);
    }

    private static Set<ArtifactId> affected(Map<ArtifactId, ArtifactResource> current,
                                            Map<ArtifactId, ArtifactRecord> target) {
        var result = new LinkedHashSet<ArtifactId>();
        current.forEach((id, resource) -> {
            var replacement = target.get(id);
            if (replacement == null || !resource.artifact().revision().equals(replacement.revision())) {
                result.add(id);
            }
        });
        target.forEach((id, artifact) -> {
            if (!current.containsKey(id)) {
                result.add(id);
            }
        });
        return Set.copyOf(result);
    }

    private static RuntimeCatalog catalog(Map<ArtifactId, ArtifactResource> resources) {
        var entries = resources.values().stream().map(ArtifactResource::entry).toList();
        var plugins = entries.isEmpty() ? PluginCatalog.empty()
            : PluginCatalog.combine(entries.stream().map(PluginCatalog::of).toList());
        var owners = new LinkedHashMap<String, ArtifactId>();
        resources.forEach((artifactId, resource) -> {
            var name = resource.entry().definition().name();
            if (owners.putIfAbsent(name, artifactId) != null) {
                throw new IllegalArgumentException("duplicate Node plugin definition " + name);
            }
        });
        return new RuntimeCatalog(plugins, owners);
    }

    private record ArtifactResource(ArtifactRecord artifact, PluginCatalogEntry<?> entry) {
        private ArtifactResource {
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(entry, "entry");
        }

        private RuntimeResourceSnapshot.Resource snapshot(RuntimeResourceSnapshot.State state) {
            return RuntimeResourceSnapshot.Resource.builder().artifact(artifact)
                .identity("node:" + artifact.id().value() + ':' + artifact.revision())
                .state(state).build();
        }
    }
}
