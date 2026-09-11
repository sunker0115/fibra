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
import com.sstlfsj.fibra.engine.RuntimeGeneration;
import com.sstlfsj.fibra.engine.RuntimeArtifactInspection;
import com.sstlfsj.fibra.engine.RuntimeGenerationRequest;
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
    public RuntimeGeneration create(RuntimeGenerationRequest request) {
        if (!request.runtimeId().equals(RUNTIME_ID)) {
            throw new IllegalArgumentException("runtime request is not for Node");
        }
        return new Generation(request);
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
                        artifact.location().resolve(manifest.entrypoint()), options)
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

    private final class Generation implements RuntimeGeneration {
        private final RuntimeGenerationRequest request;
        private final Mono<Void> preparation = Mono.<Void>fromRunnable(this::initialize).cache();
        private final Mono<Void> close = Mono.<Void>fromRunnable(this::release).cache();
        private PluginCatalog catalog;
        private RuntimeGenerationSnapshot snapshot;
        private boolean closed;

        private Generation(RuntimeGenerationRequest request) { this.request = request; }

        private synchronized void initialize() {
            if (closed) throw new IllegalStateException("Node runtime generation is closed");
            var entries = new ArrayList<PluginCatalogEntry<?>>();
            for (var artifact : request.artifacts()) {
                requireRuntime(artifact);
                var manifest = manifests.read(artifact);
                validateKinds(manifest);
                entries.add(catalogEntry(artifact, manifest));
            }
            catalog = PluginCatalog.combine(entries.stream()
                .map(PluginCatalog::of).toList());
            var artifactMap = request.artifacts().stream().collect(Collectors.toMap(
                ArtifactRecord::id, value -> value, (left, right) -> right,
                LinkedHashMap::new));
            snapshot = new RuntimeGenerationSnapshot(RUNTIME_ID,
                revision(request.artifacts()), artifactMap,
                entries.stream().map(value -> value.definition().name())
                    .collect(Collectors.toSet()));
        }

        private synchronized void release() {
            // sidecar 属于插件实例的贡献注册；domain 先关闭它们。
            closed = true;
        }

        @Override public Mono<Void> prepareAsync() {
            return Mono.defer(() -> {
                synchronized (this) {
                    if (closed) return Mono.error(new IllegalStateException("Node runtime generation is closed"));
                }
                return preparation;
            });
        }
        @Override public synchronized RuntimeGenerationSnapshot snapshot() {
            if (snapshot == null) throw new IllegalStateException("Node runtime generation is not prepared");
            return snapshot;
        }
        @Override public synchronized PluginCatalog catalog() {
            snapshot();
            return catalog;
        }
        @Override public Mono<Void> closeAsync() { return close; }
    }
}
