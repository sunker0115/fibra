package com.sstlfsj.fibra.plugins.fs.local;

import com.sstlfsj.fibra.plugins.fs.FileSystemServices;
import com.sstlfsj.fibra.plugins.tool.ToolServices;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalFileSystemPluginTest {
    @Test
    void exposesFormalProviderDefinitionAndManifest() throws Exception {
        var definition = new LocalFileSystemEntrypoint().definition();

        assertEquals(LocalFileSystemConfig.class, definition.configType());
        assertEquals(java.util.Set.of(FileSystemServices.FILE_SYSTEM, ToolServices.RESULT_SPILL_STORE),
            definition.provides());
        try (var input = LocalFileSystemPluginTest.class.getResourceAsStream("/META-INF/fibra/plugin.yaml")) {
            var manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("entrypoint: " + LocalFileSystemEntrypoint.class.getName()
                + "\n", manifest);
        }
    }
}
