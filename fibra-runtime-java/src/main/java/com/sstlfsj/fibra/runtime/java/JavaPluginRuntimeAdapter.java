package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.engine.PluginCatalog;
import com.sstlfsj.fibra.engine.PluginRuntimeAdapter;
import com.sstlfsj.fibra.engine.RuntimeGeneration;
import com.sstlfsj.fibra.engine.RuntimeArtifactInspection;
import com.sstlfsj.fibra.engine.RuntimeGenerationRequest;
import com.sstlfsj.fibra.engine.RuntimeGenerationSnapshot;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public final class JavaPluginRuntimeAdapter implements PluginRuntimeAdapter {
    public static final RuntimeId RUNTIME_ID = new RuntimeId("java");
    private static final List<String> DEFAULT_PARENT_PACKAGES = List.of(
        "java.", "javax.", "jdk.", "sun.",
        "com.sstlfsj.fibra.", "reactor.", "org.reactivestreams.", "org.slf4j.");

    private final ClassLoader parent;
    private final List<String> parentPackages;
    private final JavaManifestReader manifests = new JavaManifestReader();

    public JavaPluginRuntimeAdapter() {
        this(JavaPluginRuntimeAdapter.class.getClassLoader(), DEFAULT_PARENT_PACKAGES);
    }

    public JavaPluginRuntimeAdapter(ClassLoader parent, List<String> parentPackages) {
        this.parent = Objects.requireNonNull(parent, "parent");
        this.parentPackages = List.copyOf(parentPackages);
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
            var attributes = new LinkedHashMap<String, Object>();
            manifest.entrypoint().ifPresent(value ->
                attributes.put("entrypoint", value));
            attributes.put("requires", manifest.requires().stream()
                .map(requirement -> requirement.artifactId().value()).toList());
            return new RuntimeArtifactInspection(RUNTIME_ID, artifact.id(), attributes);
        });
    }

    @Override
    public RuntimeGeneration create(RuntimeGenerationRequest request) {
        if (!request.runtimeId().equals(RUNTIME_ID)) {
            throw new IllegalArgumentException("runtime request is not for Java");
        }
        return new Generation(request);
    }

    private static void requireRuntime(ArtifactRecord artifact) {
        if (!artifact.runtimeId().equals(RUNTIME_ID)) {
            throw new IllegalArgumentException("artifact runtime is not Java");
        }
    }

    private static String revision(List<ArtifactRecord> artifacts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            artifacts.stream().sorted(java.util.Comparator.comparing(
                    value -> value.id().value()))
                .forEach(value -> digest.update((value.id().value() + "\0"
                    + value.revision() + "\0").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private final class Generation implements RuntimeGeneration {
        private final RuntimeGenerationRequest request;
        private final JavaClassSpace classSpace = new JavaClassSpace();
        private final Mono<Void> preparation = Mono.<Void>fromRunnable(this::initialize).cache();
        private final Mono<Void> close = Mono.<Void>fromRunnable(this::release).cache();
        private RuntimeGenerationSnapshot snapshot;
        private boolean closed;

        private Generation(RuntimeGenerationRequest request) { this.request = request; }

        private synchronized void initialize() {
            if (closed) throw new IllegalStateException("Java runtime generation is closed");
            var parsed = new LinkedHashMap<ArtifactId, JavaPluginManifest>();
            for (var artifact : request.artifacts()) {
                requireRuntime(artifact);
                parsed.put(artifact.id(), manifests.read(artifact));
            }
            classSpace.prepare(request.artifacts(), parsed, parent, parentPackages);
            var artifacts = new LinkedHashMap<ArtifactId, ArtifactRecord>();
            request.artifacts().forEach(value -> artifacts.put(value.id(), value));
            snapshot = new RuntimeGenerationSnapshot(RUNTIME_ID, revision(request.artifacts()), artifacts,
                classSpace.catalog().entries().stream().map(value -> value.definition().name())
                    .collect(java.util.stream.Collectors.toSet()));
        }

        private synchronized void release() {
            closed = true;
            classSpace.close();
        }

        @Override public Mono<Void> prepareAsync() {
            return Mono.defer(() -> {
                synchronized (this) {
                    if (closed) return Mono.error(new IllegalStateException("Java runtime generation is closed"));
                }
                return preparation;
            });
        }
        @Override public synchronized RuntimeGenerationSnapshot snapshot() {
            if (snapshot == null) throw new IllegalStateException("Java runtime generation is not prepared");
            return snapshot;
        }
        @Override public synchronized PluginCatalog catalog() {
            snapshot();
            return classSpace.catalog();
        }
        @Override public Mono<Void> closeAsync() { return close; }
    }
}
