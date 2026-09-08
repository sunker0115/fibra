package verification.distribution;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.InstallArtifact;
import com.sstlfsj.fibra.runtime.java.JavaPluginRuntimeAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineConsumerTest {
    @Test
    void installsARealExternalPluginJar(@TempDir Path work) {
        var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(new ArtifactStore(work.resolve("artifacts")))
            .runtimeAdapter(new JavaPluginRuntimeAdapter()).build();
        try (engine) {
            var started = engine.start().block();
            var installed = engine.submit(InstallArtifact.builder()
                .expectedRevision(started.revision())
                .artifactId(new ArtifactId("external"))
                .runtimeId(JavaPluginRuntimeAdapter.RUNTIME_ID)
                .version("1.0.0").source(Path.of(System.getProperty("plugin.jar")))
                .build()).block().snapshot();
            assertTrue(installed.artifacts().containsKey(new ArtifactId("external")));
        }
    }
}
