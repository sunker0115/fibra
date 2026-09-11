package com.sstlfsj.fibra.plugins.shell.local;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.plugins.shell.ShellServices;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellPluginTest {
    @Test void manifestAndDefinitionDeclareBothContractsAndSubprocessService() throws Exception {
        var resource = getClass().getResourceAsStream("/META-INF/fibra/plugin.yaml");
        assertNotNull(resource);
        try (resource) {
            var text = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(text.contains("id: fibra-shell-local"));
            assertTrue(text.contains("id: fibra-shell\n"));
            assertTrue(text.contains("id: fibra-subprocess\n"));
            assertFalse(text.contains("${project.version}"));
        }
        var entrypoint = (PluginEntrypoint<?>) Class.forName(
            "com.sstlfsj.fibra.plugins.shell.local.ShellLocalEntrypoint").getConstructor().newInstance();
        assertTrue(entrypoint.definition().provides().contains(ShellServices.SHELL));
        assertTrue(entrypoint.definition().requires().containsKey(SubprocessServices.SUBPROCESS));
    }
}
