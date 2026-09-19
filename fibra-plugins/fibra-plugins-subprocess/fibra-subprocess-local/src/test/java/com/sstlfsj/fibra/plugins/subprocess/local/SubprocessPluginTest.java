package com.sstlfsj.fibra.plugins.subprocess.local;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubprocessPluginTest {
    @Test void manifestAndDefinitionDeclareContractAndService() throws Exception {
        var resource = getClass().getResourceAsStream("/META-INF/fibra/plugin.yaml");
        assertNotNull(resource);
        try (resource) {
            var text = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("entrypoint: " + SubprocessLocalEntrypoint.class.getName()
                + "\n", text);
        }
        var entrypoint = (PluginEntrypoint<?>) Class.forName(
            "com.sstlfsj.fibra.plugins.subprocess.local.SubprocessLocalEntrypoint").getConstructor().newInstance();
        assertTrue(entrypoint.definition().provides().contains(SubprocessServices.SUBPROCESS));
    }
}
