package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaArtifactGraphTest {
    @Test
    void validatesConstraintsAndProducesStableDependencyFirstOrder() {
        var base = manifest("base", "1.2.0");
        var consumer = manifest("consumer", "2.0.0",
            new JavaArtifactRequirement(new ArtifactId("base"), "^1.0.0"));

        var graph = JavaArtifactGraph.resolve(List.of(consumer, base));

        assertEquals(List.of(new ArtifactId("base"), new ArtifactId("consumer")),
            graph.dependencyFirst());
    }

    @Test
    void rejectsMissingIncompatibleAndCyclicDependencies() {
        assertThrows(JavaRuntimeException.class, () -> JavaArtifactGraph.resolve(List.of(
            manifest("consumer", "1.0.0",
                new JavaArtifactRequirement(new ArtifactId("missing"), "*")))));
        assertThrows(JavaRuntimeException.class, () -> JavaArtifactGraph.resolve(List.of(
            manifest("base", "2.0.0"),
            manifest("consumer", "1.0.0",
                new JavaArtifactRequirement(new ArtifactId("base"), "^1.0.0")))));
        assertThrows(JavaRuntimeException.class, () -> JavaArtifactGraph.resolve(List.of(
            manifest("first", "1.0.0",
                new JavaArtifactRequirement(new ArtifactId("second"), "*")),
            manifest("second", "1.0.0",
                new JavaArtifactRequirement(new ArtifactId("first"), "*")))));
    }

    private static JavaPluginManifest manifest(String id, String version,
                                               JavaArtifactRequirement... requirements) {
        return new JavaPluginManifest(new ArtifactId(id), version, "example.Entrypoint",
            List.of(requirements));
    }
}
