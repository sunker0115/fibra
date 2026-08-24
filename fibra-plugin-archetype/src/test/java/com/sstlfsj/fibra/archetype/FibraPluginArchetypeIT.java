package com.sstlfsj.fibra.archetype;

import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.FibraEngineState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraPluginArchetypeIT {
    @Test
    void generatedDeploymentIsAcceptedAndActivatedByFibraEngine(@TempDir Path work)
        throws Exception {
        var generated = Path.of("target/test-classes/projects/basic/project",
            "sample-fibra-plugin");
        var deployment = generated.resolve(
            "deployment/target/sample-fibra-plugin-deployment-1.0.0.zip");
        assertTrue(Files.isRegularFile(deployment),
            () -> "generated deployment is missing: " + deployment);

        var installed = Files.createDirectory(work.resolve("plugins"));
        var config = work.resolve("config/fibra.yaml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "[]\n");

        try (var engine = FibraEngine.builder(installed, config).build()) {
            engine.start();
            var result = engine.applyDeployment(deployment);

            assertEquals("sample-fibra-plugin", result.deploymentId());
            assertEquals("1.0.0", result.deploymentVersion());
            assertEquals(FibraEngineState.RUNNING, engine.status().state());
            assertEquals(1, engine.root().registry().size());
            assertEquals(result.appliedRevision(),
                engine.status().appliedRevision().orElseThrow());
            assertEquals(java.util.List.of("sample-fibra-plugin",
                    "sample-fibra-plugin-contract"),
                result.changedArtifactIds().stream().sorted().toList());
        }
    }
}
