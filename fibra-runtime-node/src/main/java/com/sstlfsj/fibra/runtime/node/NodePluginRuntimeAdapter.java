package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.bridge.ContributionBinding;
import com.sstlfsj.fibra.bridge.ContributionBridge;
import com.sstlfsj.fibra.bridge.ContributionCodec;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.engine.PluginCatalog;
import com.sstlfsj.fibra.engine.PluginCatalogEntry;
import com.sstlfsj.fibra.engine.PluginRuntimeAdapter;
import com.sstlfsj.fibra.engine.PreparedRuntimeGeneration;
import com.sstlfsj.fibra.engine.RuntimeArtifactInspection;
import com.sstlfsj.fibra.engine.RuntimeChangeRequest;
import com.sstlfsj.fibra.engine.RuntimeGenerationSnapshot;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class NodePluginRuntimeAdapter implements PluginRuntimeAdapter {
    public static final RuntimeId RUNTIME_ID = new RuntimeId("node");

    private final ContributionBridge bridge;
    private final NodeContributionKindResolver kinds;
    private final NodeRuntimeOptions options;
    private final NodeManifestReader manifests = new NodeManifestReader();
    private RuntimeGenerationSnapshot currentSnapshot;

    public NodePluginRuntimeAdapter(ContributionBridge bridge,
                                    NodeContributionKindResolver kinds,
                                    NodeRuntimeOptions options) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
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
    public Mono<PreparedRuntimeGeneration> prepare(RuntimeChangeRequest request) {
        return Mono.fromCallable(() -> {
            if (!request.runtimeId().equals(RUNTIME_ID)) {
                throw new IllegalArgumentException("runtime request is not for Node");
            }
            var entries = new ArrayList<PluginCatalogEntry<?>>();
            for (var artifact : request.artifacts()) {
                requireRuntime(artifact);
                var manifest = manifests.read(artifact);
                validateKinds(manifest);
                entries.add(catalogEntry(artifact, manifest));
            }
            var catalog = PluginCatalog.combine(entries.stream()
                .map(PluginCatalog::of).toList());
            var artifactMap = request.artifacts().stream().collect(Collectors.toMap(
                ArtifactRecord::id, value -> value, (left, right) -> right,
                LinkedHashMap::new));
            var snapshot = new RuntimeGenerationSnapshot(RUNTIME_ID,
                revision(request.artifacts()), artifactMap,
                entries.stream().map(value -> value.definition().name())
                    .collect(Collectors.toSet()));
            return new Prepared(catalog, snapshot, currentSnapshot);
        });
    }

    private PluginCatalogEntry<Object> catalogEntry(ArtifactRecord artifact,
                                                     NodePluginManifest manifest) {
        var definition = PluginDefinition.builder(manifest.artifactId().value(),
            Object.class, () -> (context, config) -> {
                var provider = context.plugins().current().orElseThrow(() ->
                    new IllegalStateException("Node plugin requires a plugin instance"));
                return NodeSidecar.start(
                        artifact.location().resolve(manifest.entrypoint()), options)
                    .flatMap(sidecar -> sidecar.request("fibra.start", Map.of(
                            "protocol", manifest.protocol(),
                            "config", config == null ? Map.of() : config),
                            options.defaultRequestTimeout())
                        .then(bridge.registerAll(context, provider.id(),
                            bindings(manifest, sidecar), closeAfterDrain(sidecar)))
                        .then(Mono.fromRunnable(() -> context.effects().supervise(
                            sidecar.termination(), "node-sidecar:" + provider.id())))
                        .then()
                        .onErrorResume(failure -> {
                            sidecar.close();
                            return Mono.error(failure);
                        }));
            }).build();
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

    private static String revision(List<ArtifactRecord> artifacts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            artifacts.stream().sorted(Comparator.comparing(value -> value.id().value()))
                .forEach(value -> digest.update((value.id().value() + "\0"
                    + value.revision() + "\0").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private final class Prepared implements PreparedRuntimeGeneration {
        private final PluginCatalog catalog;
        private final RuntimeGenerationSnapshot snapshot;
        private final RuntimeGenerationSnapshot previous;
        private State state = State.PREPARED;

        private Prepared(PluginCatalog catalog, RuntimeGenerationSnapshot snapshot,
                         RuntimeGenerationSnapshot previous) {
            this.catalog = catalog;
            this.snapshot = snapshot;
            this.previous = previous;
        }

        @Override
        public RuntimeGenerationSnapshot snapshot() {
            return snapshot;
        }

        @Override
        public PluginCatalog catalog() {
            return catalog;
        }

        @Override
        public Mono<Void> commit() {
            return Mono.fromRunnable(() -> {
                synchronized (NodePluginRuntimeAdapter.this) {
                    ensure(State.PREPARED);
                    currentSnapshot = snapshot;
                    state = State.COMMITTED;
                }
            });
        }

        @Override
        public Mono<Void> rollback() {
            return Mono.fromRunnable(() -> {
                synchronized (NodePluginRuntimeAdapter.this) {
                    if (state == State.ROLLED_BACK) {
                        return;
                    }
                    if (state == State.RETIRED) {
                        throw new IllegalStateException("Node generation is retired");
                    }
                    if (state == State.COMMITTED) {
                        currentSnapshot = previous;
                    }
                    state = State.ROLLED_BACK;
                }
            });
        }

        @Override
        public Mono<Void> retire() {
            return Mono.fromRunnable(() -> {
                synchronized (NodePluginRuntimeAdapter.this) {
                    ensure(State.COMMITTED);
                    state = State.RETIRED;
                }
            });
        }

        private void ensure(State expected) {
            if (state != expected) {
                throw new IllegalStateException("Node generation is " + state);
            }
        }
    }

    private enum State {
        PREPARED,
        COMMITTED,
        ROLLED_BACK,
        RETIRED
    }
}
