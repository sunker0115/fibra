package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.runtime.client.ClientArtifactRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientArtifactInstallGateTest {
    @Test
    void invalidClientDescriptorRollsBackTheRealPackageTransaction(
        @TempDir Path work) throws Exception {
        var source = Files.createDirectory(work.resolve("source"));
        var payload = Files.createDirectory(source.resolve("web"));
        Files.writeString(source.resolve("fibra-package.yaml"), """
            format: 1
            id: example
            version: 1.0.0
            facets:
              - id: web
                role: client
                runtime: client
                target: client:web
                payload: web
                dependencies: []
                capabilities: [client.web.module.blob.v1]
            """);
        Files.writeString(payload.resolve("index.js"), "export default {};\n");
        Files.writeString(payload.resolve("fibra-client.yaml"), """
            format: 1
            entryModule: index.js
            resources:
              - path: index.js
                digest: %s
                byteLength: 19
            """.formatted("0".repeat(64)));

        try (var store = new PluginPackageStore(work.resolve("store"));
             var transaction = store.prepareInstall(source)) {
            var runtime = new ClientArtifactRuntime();
            var resources = new ArtifactResources(Map.of(runtime.id(), runtime));

            assertThrows(IllegalArgumentException.class,
                () -> resources.inspectAndSave(transaction).block());

            assertTrue(store.history(new PluginId("example")).isEmpty());
        }
    }
}
