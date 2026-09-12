package com.sstlfsj.fibra.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliPathsTest {
    @Test
    void resolvesTheDistributionLayoutAndProfileDataNamespace(@TempDir Path work) {
        var paths = CliPaths.resolve(work, "default", null, null, null, null);

        assertEquals(work.resolve("config/profiles/default.yaml"), paths.profileFile());
        assertEquals(work.resolve("plugins"), paths.pluginsRoot());
        assertEquals(work.resolve("data/profiles/default/state"), paths.stateRoot());
        assertEquals(work.resolve("data/profiles/default/artifacts"), paths.artifactRoot());
        assertEquals(work.resolve("data/profiles/default/audit.log"), paths.auditFile());
        assertEquals(work.resolve("data/profiles/default/node-sessions"),
            paths.nodeSessionRoot());
        assertEquals(Path.of("node"), paths.nodeExecutable());
    }

    @Test
    void keepsExplicitRootsIndependentFromTheDistributionHome(@TempDir Path work) {
        var config = work.resolve("outside/config");
        var plugins = work.resolve("outside/plugins");
        var data = work.resolve("outside/data");
        var node = work.resolve("runtime/node");

        var paths = CliPaths.resolve(work.resolve("home"), "team-a", config,
            plugins, data, node);

        assertEquals(config, paths.configRoot());
        assertEquals(plugins, paths.pluginsRoot());
        assertEquals(data, paths.dataRoot());
        assertEquals(node, paths.nodeExecutable());
    }

    @Test
    void rejectsProfileNamesThatCouldEscapeTheirNamespace(@TempDir Path work) {
        for (var profile : new String[] {"", ".", "..", "../other", "a/b", "a\\b"}) {
            assertThrows(IllegalArgumentException.class,
                () -> CliPaths.resolve(work, profile, null, null, null, null));
        }
    }
}
