package com.sstlfsj.fibra.plugins.fs.tool;

import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.plugins.fs.FileSystemServices;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileToolPluginTest {
    @Test
    void exposesConsumerRequirementsAndFormalManifest() throws Exception {
        var definition = new FileToolEntrypoint().definition();

        assertEquals(FileToolConfig.class, definition.configType());
        assertEquals(java.util.Set.of(FileSystemServices.FILE_SYSTEM, ContributionServices.REGISTRAR),
            definition.requires().keySet());

        var config = new FileToolConfig();
        var descriptors = FileToolEntrypoint.descriptors(config);
        assertEquals(Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", List.of("path"),
            "properties", Map.of(
                "path", Map.of("type", "string"),
                "cwd", Map.of("type", "string"),
                "offset", Map.of("type", "integer", "minimum", java.math.BigDecimal.ONE),
                "limit", Map.of("type", "integer", "minimum", java.math.BigDecimal.ONE,
                    "maximum", java.math.BigDecimal.valueOf(config.readLimit())
                        .stripTrailingZeros()))),
            descriptors.get("read").inputSchema().toJava());
        assertEquals("object", ((Map<?, ?>) descriptors.get("read").outputSchema().toJava())
            .get("type"));
        try (var input = FileToolPluginTest.class.getResourceAsStream("/META-INF/fibra/plugin.yaml")) {
            var manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(manifest.contains("id: fibra-tool-fs"));
            assertTrue(manifest.contains("version: "
                + System.getProperty("fibra.test.projectVersion")));
            assertTrue(manifest.contains("entrypoint: com.sstlfsj.fibra.plugins.fs.tool.FileToolEntrypoint"));
            assertTrue(manifest.contains("id: fibra-fs"));
            assertFalse(manifest.contains("${project.version}"));
        }
    }
}
