package com.sstlfsj.fibra.plugins.fs.local;

import com.sstlfsj.fibra.plugins.fs.FileSystemServices;
import com.sstlfsj.fibra.plugins.tool.ToolServices;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileSystemPluginTest {
    @Test
    void exposesFormalProviderDefinitionAndManifest() throws Exception {
        var definition = new LocalFileSystemEntrypoint().definition();

        assertEquals(LocalFileSystemConfig.class, definition.configType());
        assertEquals(java.util.Set.of(FileSystemServices.FILE_SYSTEM, ToolServices.RESULT_SPILL_STORE),
            definition.provides());
        try (var input = LocalFileSystemPluginTest.class.getResourceAsStream("/META-INF/fibra/plugin.yaml")) {
            var manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(manifest.contains("id: fibra-fs-local"));
            assertTrue(manifest.contains("version: "
                + System.getProperty("fibra.test.projectVersion")));
            assertTrue(manifest.contains("entrypoint: com.sstlfsj.fibra.plugins.fs.local.LocalFileSystemEntrypoint"));
            assertTrue(manifest.contains("id: fibra-fs"));
            assertFalse(manifest.contains("${project.version}"));
        }
    }
}
