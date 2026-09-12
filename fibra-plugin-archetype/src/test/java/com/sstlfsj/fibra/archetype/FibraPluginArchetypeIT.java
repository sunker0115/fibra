package com.sstlfsj.fibra.archetype;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.artifact.ArtifactPackage;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactState;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import com.sstlfsj.fibra.runtime.java.JavaPluginRuntimeAdapter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraPluginArchetypeIT {
    @Test
    void generatedInstallationLoadsThroughTheJavaRuntime() throws Exception {
        var generated = Path.of("target/test-classes/projects/basic/project",
            "sample-fibra-plugin");
        var jar = generated.resolve("target/sample-fibra-plugin-1.0.0.jar");
        assertTrue(Files.isRegularFile(jar),
            () -> "generated plugin JAR is missing: " + jar);
        var root = generated.resolve("target/sample-fibra-plugin-1.0.0-plugin");
        assertTrue(Files.isDirectory(root), () -> "generated installation is missing: " + root);
        var installation = ArtifactPackage.read(root);
        assertEquals(root.resolve("lib/plugin.jar").toRealPath(), installation.payload());
        assertEquals(-1, Files.mismatch(jar, installation.payload()));
        var adapter = new JavaPluginRuntimeAdapter();
        var candidate = adapter.probe(installation).block();
        assertEquals("sample-fibra-plugin", candidate.artifactId().value());
        assertEquals("1.0.0", candidate.version());
        assertEquals(root.toRealPath(), candidate.source());
        var artifact = ArtifactRecord.builder()
            .id(candidate.artifactId())
            .runtimeId(candidate.runtimeId())
            .version(candidate.version())
            .checksum("verified-by-archetype-it")
            .revision("1")
            .location(candidate.source())
            .state(ArtifactState.INSTALLED)
            .updatedAt(Instant.now())
            .build();
        var owner = adapter.create();
        var update = owner.createUpdate(List.of(artifact));
        try {
            update.prepareAsync().block();
            update.adopt();
            try (var runtime = FibraRuntime.create()) {
                @SuppressWarnings("unchecked")
                var definition = (PluginDefinition<Object>) update.catalog().plugins()
                    .find("sample-fibra-plugin").orElseThrow().definition();
                var instance = runtime.rootScope().context().plugins()
                    .mount("generated-plugin", definition.prepare(null));
                instance.settled().block();

                assertEquals(PluginInstanceState.ACTIVE, instance.state());
            }
        } finally {
            try {
                update.closeAsync().block();
            } finally {
                owner.closeAsync().block();
            }
        }
    }
}
