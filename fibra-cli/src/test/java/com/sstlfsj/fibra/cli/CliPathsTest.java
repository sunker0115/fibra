package com.sstlfsj.fibra.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliPathsTest {
    @Test
    void resolvesTheDistributionLayoutAndProfileDataNamespace(@TempDir Path work) {
        var paths = CliPaths.resolve(work, "default", null, null, null, null);

        assertEquals(work.resolve("config/profiles/default.yaml"), paths.profileFile());
        assertEquals(work.resolve("config/profiles/default.artifacts.yaml"),
            paths.profileArtifactsFile());
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
        assertEquals(config.resolve("profiles/team-a.artifacts.yaml"), paths.profileArtifactsFile());
        assertEquals(plugins, paths.pluginsRoot());
        assertEquals(data, paths.dataRoot());
        assertEquals(node, paths.nodeExecutable());
    }

    @Test
    void resolvesBundledRuntimeExecutablesAndProfileContext(@TempDir Path work) throws Exception {
        var home = work.resolve("fibra");
        var runtime = Files.createDirectories(home.resolve("runtime/bin"));
        for (var executable : new String[] {"node", "rg", "bash"}) {
            Files.writeString(runtime.resolve(executable), executable);
        }

        var paths = CliPaths.resolve(home, "default", null, null, null, null);

        assertEquals(runtime.resolve("node"), paths.nodeExecutable());
        assertEquals(runtime.resolve("rg"), paths.rgExecutable());
        assertEquals(runtime.resolve("bash"), paths.bashExecutable());
        assertEquals(Map.of("fibra", Map.of(
            "home", home.toString(),
            "dataRoot", home.resolve("data").toString(),
            "workspaceRoot", home.resolve("data/profiles/default/workspace").toString(),
            "storageRoot", home.resolve("data/profiles/default/storage").toString(),
            "nodeExecutable", runtime.resolve("node").toString(),
            "rgExecutable", runtime.resolve("rg").toString(),
            "bashExecutable", runtime.resolve("bash").toString())), paths.configContext());
    }

    @Test
    void rejectsProfileNamesThatCouldEscapeTheirNamespace(@TempDir Path work) {
        for (var profile : new String[] {"", ".", "..", "../other", "a/b", "a\\b"}) {
            assertThrows(IllegalArgumentException.class,
                () -> CliPaths.resolve(work, profile, null, null, null, null));
        }
    }
}
