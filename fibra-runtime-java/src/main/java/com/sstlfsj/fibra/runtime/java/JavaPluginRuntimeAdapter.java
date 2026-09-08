package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.engine.PluginCatalog;
import com.sstlfsj.fibra.engine.PluginRuntimeAdapter;
import com.sstlfsj.fibra.engine.PreparedRuntimeGeneration;
import com.sstlfsj.fibra.engine.RuntimeArtifactInspection;
import com.sstlfsj.fibra.engine.RuntimeChangeRequest;
import com.sstlfsj.fibra.engine.RuntimeGenerationSnapshot;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JavaPluginRuntimeAdapter implements PluginRuntimeAdapter {
    public static final RuntimeId RUNTIME_ID = new RuntimeId("java");
    private static final List<String> DEFAULT_PARENT_PACKAGES = List.of(
        "java.", "javax.", "jdk.", "sun.",
        "com.sstlfsj.fibra.", "reactor.", "org.reactivestreams.", "org.slf4j.");

    private final ClassLoader parent;
    private final List<String> parentPackages;
    private final JavaManifestReader manifests = new JavaManifestReader();
    private JavaClassSpace current;
    private RuntimeGenerationSnapshot currentSnapshot;

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
            return new RuntimeArtifactInspection(RUNTIME_ID, artifact.id(), Map.of(
                "entrypoint", manifest.entrypoint(),
                "requires", manifest.requires().stream()
                    .map(requirement -> requirement.artifactId().value()).toList()));
        });
    }

    @Override
    public Mono<PreparedRuntimeGeneration> prepare(RuntimeChangeRequest request) {
        return Mono.fromCallable(() -> {
            if (!request.runtimeId().equals(RUNTIME_ID)) {
                throw new IllegalArgumentException("runtime request is not for Java");
            }
            var parsed = new LinkedHashMap<ArtifactId, JavaPluginManifest>();
            for (var artifact : request.artifacts()) {
                requireRuntime(artifact);
                parsed.put(artifact.id(), manifests.read(artifact));
            }
            var candidate = JavaClassSpace.open(request.artifacts(), parsed,
                parent, parentPackages);
            var artifactMap = new LinkedHashMap<ArtifactId, ArtifactRecord>();
            request.artifacts().forEach(value -> artifactMap.put(value.id(), value));
            var snapshot = new RuntimeGenerationSnapshot(RUNTIME_ID,
                revision(request.artifacts()), artifactMap,
                candidate.catalog().entries().stream()
                    .map(value -> value.definition().name())
                    .collect(java.util.stream.Collectors.toSet()));
            return new Prepared(candidate, snapshot, current, currentSnapshot);
        });
    }

    @Override
    public synchronized void close() {
        if (current != null) {
            current.close();
            current = null;
            currentSnapshot = null;
        }
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

    private final class Prepared implements PreparedRuntimeGeneration {
        private final JavaClassSpace candidate;
        private final RuntimeGenerationSnapshot snapshot;
        private final JavaClassSpace previous;
        private final RuntimeGenerationSnapshot previousSnapshot;
        private State state = State.PREPARED;

        private Prepared(JavaClassSpace candidate, RuntimeGenerationSnapshot snapshot,
                         JavaClassSpace previous,
                         RuntimeGenerationSnapshot previousSnapshot) {
            this.candidate = candidate;
            this.snapshot = snapshot;
            this.previous = previous;
            this.previousSnapshot = previousSnapshot;
        }

        @Override
        public RuntimeGenerationSnapshot snapshot() {
            return snapshot;
        }

        @Override
        public PluginCatalog catalog() {
            return candidate.catalog();
        }

        @Override
        public Mono<Void> commit() {
            return Mono.fromRunnable(() -> {
                synchronized (JavaPluginRuntimeAdapter.this) {
                    ensure(State.PREPARED);
                    current = candidate;
                    currentSnapshot = snapshot;
                    state = State.COMMITTED;
                }
            });
        }

        @Override
        public Mono<Void> rollback() {
            return Mono.fromRunnable(() -> {
                synchronized (JavaPluginRuntimeAdapter.this) {
                    if (state == State.ROLLED_BACK) {
                        return;
                    }
                    if (state == State.RETIRED) {
                        throw new IllegalStateException("Java generation is retired");
                    }
                    if (state == State.COMMITTED) {
                        current = previous;
                        currentSnapshot = previousSnapshot;
                    }
                    candidate.close();
                    state = State.ROLLED_BACK;
                }
            });
        }

        @Override
        public Mono<Void> retire() {
            return Mono.fromRunnable(() -> {
                synchronized (JavaPluginRuntimeAdapter.this) {
                    ensure(State.COMMITTED);
                    if (previous != null) {
                        previous.close();
                    }
                    state = State.RETIRED;
                }
            });
        }

        private void ensure(State expected) {
            if (state != expected) {
                throw new IllegalStateException("Java generation is " + state);
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
