package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactPackage;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.RuntimeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginArtifactProbeTest {
    @Test
    void routesToTheSelectedRuntimeAndKeepsTheWholeInstallationRoot(@TempDir Path work)
        throws Exception {
        var java = new ProbeAdapter("java");
        var node = new ProbeAdapter("node");
        var root = installation(work);

        var result = new PluginArtifactProbe(List.of(java, node)).probe(root)
            .block(Duration.ofSeconds(5));

        assertEquals(new ArtifactId("sample"), result.artifactId());
        assertEquals(new RuntimeId("java"), result.runtimeId());
        assertEquals("1.0.0", result.version());
        assertEquals(root.toRealPath(), result.source());
        assertEquals(root.resolve("plugin.jar").toRealPath(), java.seen.get().payload());
        assertNull(node.seen.get());
    }

    @Test
    void rejectsUnknownRuntimeBeforeInvokingAnotherAdapter(@TempDir Path work) throws Exception {
        var node = new ProbeAdapter("node");
        var probe = new PluginArtifactProbe(List.of(node));
        var root = installation(work);
        assertThrows(IllegalArgumentException.class,
            () -> probe.probe(root).block(Duration.ofSeconds(5)));
        assertNull(node.seen.get());
    }

    @Test
    void rejectsDuplicateRuntimeAdapters() {
        assertThrows(IllegalArgumentException.class, () -> new PluginArtifactProbe(
            List.of(new ProbeAdapter("java"), new ProbeAdapter("java"))));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAdapterResultsThatChangeTheRuntimeOrInstallationRoot(boolean wrongRuntime,
        @TempDir Path work) throws Exception {
        var adapter = new ProbeAdapter("java") {
            @Override
            public Mono<DeploymentArtifact> probe(ArtifactPackage artifact) {
                return Mono.just(DeploymentArtifact.builder().artifactId(new ArtifactId("sample"))
                    .runtimeId(wrongRuntime ? new RuntimeId("node") : id()).version("1.0.0")
                    .source(wrongRuntime ? artifact.root() : artifact.payload()).build());
            }
        };
        var root = installation(work);
        assertThrows(IllegalStateException.class, () -> new PluginArtifactProbe(List.of(adapter))
            .probe(root).block(Duration.ofSeconds(5)));
    }

    private static Path installation(Path work) throws Exception {
        Files.writeString(work.resolve("plugin.properties"),
            "formatVersion=1\nruntime=java\npayload=plugin.jar\n");
        Files.writeString(work.resolve("plugin.jar"), "runtime-owned payload");
        return work;
    }

    private static class ProbeAdapter implements PluginRuntimeAdapter {
        private final RuntimeId id;
        private final AtomicReference<ArtifactPackage> seen = new AtomicReference<>();

        private ProbeAdapter(String runtime) { id = new RuntimeId(runtime); }

        @Override
        public RuntimeId id() { return id; }

        @Override
        public Mono<DeploymentArtifact> probe(ArtifactPackage artifact) {
            seen.set(artifact);
            return Mono.just(DeploymentArtifact.builder().artifactId(new ArtifactId("sample"))
                .runtimeId(id).version("1.0.0").source(artifact.root()).build());
        }

        @Override
        public Mono<RuntimeArtifactInspection> inspect(ArtifactRecord artifact) {
            throw new AssertionError("probe must not inspect an installed artifact");
        }

        @Override
        public RuntimeResourceOwner create() {
            throw new AssertionError("probe must not create runtime resources");
        }
    }
}
