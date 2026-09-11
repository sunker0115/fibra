package com.sstlfsj.fibra.plugins.subprocess.local;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubprocessPluginTest {
    @Test void manifestAndDefinitionDeclareContractAndService() throws Exception {
        var resource = getClass().getResourceAsStream("/META-INF/fibra/plugin.yaml");
        assertNotNull(resource);
        try (resource) {
            var text = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(text.contains("id: fibra-subprocess-local"));
            assertTrue(text.contains("id: fibra-subprocess\n"));
            assertFalse(text.contains("${project.version}"));
        }
        var entrypoint = (PluginEntrypoint<?>) Class.forName(
            "com.sstlfsj.fibra.plugins.subprocess.local.SubprocessLocalEntrypoint").getConstructor().newInstance();
        assertTrue(entrypoint.definition().provides().contains(SubprocessServices.SUBPROCESS));
    }
}
