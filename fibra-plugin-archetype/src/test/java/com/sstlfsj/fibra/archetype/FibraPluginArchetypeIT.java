package com.sstlfsj.fibra.archetype;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactState;
import com.sstlfsj.fibra.engine.RuntimeChangeRequest;
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
    void generatedJarLoadsThroughTheJavaRuntime() {
        var generated = Path.of("target/test-classes/projects/basic/project",
            "sample-fibra-plugin");
        var jar = generated.resolve("target/sample-fibra-plugin-1.0.0.jar");
        assertTrue(Files.isRegularFile(jar),
            () -> "generated plugin JAR is missing: " + jar);
        var artifact = ArtifactRecord.builder()
            .id(new ArtifactId("sample-fibra-plugin"))
            .runtimeId(JavaPluginRuntimeAdapter.RUNTIME_ID)
            .version("1.0.0")
            .checksum("verified-by-archetype-it")
            .revision("1")
            .location(jar)
            .state(ArtifactState.INSTALLED)
            .updatedAt(Instant.now())
            .build();
        var adapter = new JavaPluginRuntimeAdapter();
        var prepared = adapter.prepare(new RuntimeChangeRequest(
            JavaPluginRuntimeAdapter.RUNTIME_ID, List.of(artifact), null)).block();
        prepared.commit().block();

        try (var runtime = FibraRuntime.create()) {
            @SuppressWarnings("unchecked")
            var definition = (PluginDefinition<Object>) prepared.catalog()
                .find("sample-fibra-plugin").orElseThrow().definition();
            var instance = runtime.rootScope().context().plugins()
                .mount("generated-plugin", definition.prepare(null));
            instance.settled().block();

            assertEquals(PluginInstanceState.ACTIVE, instance.state());
        }
        prepared.retire().block();
        adapter.close();
    }
}
