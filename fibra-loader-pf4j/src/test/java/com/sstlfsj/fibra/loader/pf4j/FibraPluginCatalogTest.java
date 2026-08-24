package com.sstlfsj.fibra.loader.pf4j;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import example.fibra.plugin.ConfigurableEntrypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraPluginCatalogTest {
    @Test
    void inspectionAndActiveCatalogAreReadOnly(@TempDir Path work) throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        PluginPackageFixtures.standardDirectory(plugins, "contract", "1.0.0");
        PluginPackageFixtures.executableDirectory(plugins, "fixture", "1.0.0",
            "contract@>=1.0.0", ConfigurableEntrypoint.class);
        var candidateSource = Files.createTempDirectory(work, "candidate-");
        var candidateRoot = PluginPackageFixtures.standardDirectory(candidateSource,
            "new-contract", "2.0.0");
        var candidate = PluginPackageFixtures.zipDirectory(candidateRoot,
            work.resolve("new-contract.zip"));

        try (Context root = FibraRuntime.create();
             var loader = new FibraPluginLoader(root, plugins)) {
            loader.loadArtifacts();

            var inspected = loader.inspectArtifact(candidate);
            assertEquals("new-contract", inspected.id());
            assertEquals("2.0.0", inspected.version());
            assertTrue(inspected.sha256().matches("[0-9a-f]{64}"));
            assertFalse(Files.exists(plugins.resolve(".fibra-preflight")));

            var catalog = loader.catalog();
            assertEquals(List.of("contract", "fixture"), catalog.artifacts().stream()
                .map(FibraArtifactDescriptor::id).toList());
            assertTrue(catalog.configType("contract").isEmpty());
            assertEquals(String.class, catalog.configType("fixture").orElseThrow());
            assertEquals(List.of("contract", "fixture"), loader.artifactIds());
            assertEquals(List.of(), loader.entryIds());
        }
    }
}
