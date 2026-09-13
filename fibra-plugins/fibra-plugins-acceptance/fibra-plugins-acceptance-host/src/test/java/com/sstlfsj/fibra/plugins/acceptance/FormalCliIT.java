package com.sstlfsj.fibra.plugins.acceptance;

import com.sstlfsj.fibra.cli.FibraCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(60)
class FormalCliIT {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void realStorageToolsShareReplChangesAndRecoverValuesWithoutReplayingChanges(@TempDir Path home)
        throws Exception {
        assertDynamicStorageIsNotOnHostClasspath();
        var packages = new ArrayList<String>();
        for (var id : List.of("fibra-storage", "fibra-storage-json", "fibra-tool-storage")) {
            var request = PluginAcceptanceHarness.installRequest(home.resolve("plugins"),
                PluginAcceptanceHarness.stagedJar(id));
            packages.add(request.source().getFileName().toString());
        }
        Files.createDirectory(home.resolve("plugins/unlisted-invalid-package"));
        var storage = home.resolve("data/profiles/default/storage");
        profile(home, List.of(
            Map.of("id", "storage", "plugin", "storage-json", "config", Map.of("root", storage.toString())),
            Map.of("id", "storage-tools", "plugin", "tool-storage")), packages);

        var first = cli(home, """
            plugins list
            tools list
            tools invoke storage-tools put --input '{"key":"theme","value":"dark"}'
            tools invoke storage-tools changes --input '{}'
            exit
            """, "repl");

        assertEquals(4, first.size());
        assertEquals(List.of("fibra-storage", "fibra-storage-json", "fibra-tool-storage"),
            ids(first.get(0).path("artifacts")));
        assertEquals(2, first.get(0).path("instances").size());
        assertTrue(first.get(0).path("instances").valueStream()
            .allMatch(instance -> instance.path("desired").asBoolean() && instance.path("observed").asBoolean()));
        assertEquals(List.of("changes", "load", "put", "remove"), first.get(1).path("tools")
            .valueStream().map(tool -> tool.path("name").asString()).toList());
        assertSuccessfulTool(first.get(2));
        assertEquals("dark", first.get(2).path("structuredContent").path("values").path("theme").asString());
        assertSuccessfulTool(first.get(3));
        assertEquals(JSON.readTree("""
            {"changes":[{"revision":1,"key":"theme","operation":"PUT","value":"dark"}],"dropped":false}
            """), first.get(3).path("structuredContent"));
        assertTrue(Files.isRegularFile(storage.resolve("config.json")));

        // A new public run creates a new host. Neither source is usable during recovery.
        Files.move(home.resolve("plugins"), home.resolve("removed-candidates"));
        Files.writeString(home.resolve("config/profiles/default.yaml"), "invalid source");
        Files.writeString(home.resolve("config/profiles/default.artifacts.yaml"), "invalid selection");
        var recovered = cli(home, """
            tools invoke storage-tools load --input '{}'
            tools invoke storage-tools changes --input '{}'
            exit
            """, "repl");

        assertEquals(2, recovered.size());
        assertSuccessfulTool(recovered.get(0));
        assertEquals("dark", recovered.get(0).path("structuredContent").path("values").path("theme").asString());
        assertEquals(1, recovered.get(0).path("structuredContent").path("revision").asInt());
        assertSuccessfulTool(recovered.get(1));
        assertEquals(JSON.readTree("{\"changes\":[],\"dropped\":false}"),
            recovered.get(1).path("structuredContent"));
        assertDynamicStorageIsNotOnHostClasspath();
    }

    @Test
    void completeApplyReplacesArtifactsAndInstancesWhilePublicPluginCommandsRemainWired(@TempDir Path home)
        throws Exception {
        var sources = new java.util.LinkedHashMap<String, Path>();
        var initialPackages = packages(home.resolve("plugins"), sources,
            "fibra-storage", "fibra-storage-json", "fibra-tool-storage", "fibra-fs");
        var storage = home.resolve("data/profiles/default/storage");
        profile(home, storageEntries(storage, "A", "B"), initialPackages);

        var initial = cli(home, "", "plugins", "list").getFirst();
        assertEquals(List.of("fibra-fs", "fibra-storage", "fibra-storage-json", "fibra-tool-storage"),
            ids(initial.path("artifacts")));
        assertEquals(List.of("A", "B", "storage"), ids(initial.path("instances")));
        assertActive(initial, "A");
        assertActive(initial, "B");

        var upgraded = cli(home, "", "plugins", "upgrade", sources.get("fibra-fs").toString()).getFirst();
        assertEquals(ids(initial.path("artifacts")), ids(upgraded.path("artifacts")));
        var uninstalled = cli(home, "", "plugins", "uninstall", "fibra-fs").getFirst();
        assertEquals(List.of("fibra-storage", "fibra-storage-json", "fibra-tool-storage"),
            ids(uninstalled.path("artifacts")));
        assertEquals(List.of("A", "B", "storage"), ids(uninstalled.path("instances")));

        var restored = cli(home, "", "apply").getFirst();
        assertEquals(ids(initial.path("artifacts")), ids(restored.path("artifacts")));
        var disabled = cli(home, "", "plugins", "disable", "B").getFirst();
        assertInactive(disabled, "B");
        var enabled = cli(home, "", "plugins", "enable", "B").getFirst();
        assertActive(enabled, "B");
        assertSuccessfulTool(cli(home, "", "tools", "invoke", "B", "put",
            "--input", "{\"key\":\"retained\",\"value\":\"B\"}").getFirst());

        var targetPackages = packages(home.resolve("plugins"), sources,
            "fibra-storage", "fibra-storage-json", "fibra-tool-storage", "fibra-subprocess");
        profile(home, storageEntries(storage, "B", "C"), targetPackages);
        var replacement = cli(home, "plugins list\napply\nexit\n", "repl");
        assertEquals(2, replacement.size());
        var beforeApply = replacement.get(0);
        var applied = replacement.get(1);

        assertEquals(ids(initial.path("artifacts")), ids(beforeApply.path("artifacts")));
        assertEquals(List.of("A", "B", "storage"), ids(beforeApply.path("instances")));
        assertEquals(List.of("fibra-storage", "fibra-storage-json", "fibra-subprocess", "fibra-tool-storage"),
            ids(applied.path("artifacts")));
        assertEquals(List.of("B", "C", "storage"), ids(applied.path("instances")));
        assertEquals(identity(beforeApply, "B"), identity(applied, "B"),
            "完整 apply 必须保留未变化 B 的运行实例");
        assertActive(applied, "B");
        assertActive(applied, "C");
        var retained = cli(home, "", "tools", "invoke", "B", "load", "--input", "{}").getFirst();
        assertSuccessfulTool(retained);
        assertEquals("B", retained.path("structuredContent").path("values").path("retained").asString());

        Files.move(home.resolve("plugins"), home.resolve("removed-candidates"));
        Files.writeString(home.resolve("config/profiles/default.yaml"), "invalid source");
        Files.writeString(home.resolve("config/profiles/default.artifacts.yaml"), "invalid selection");
        var recovered = cli(home, "", "plugins", "list").getFirst();
        assertEquals(applied.path("artifacts"), recovered.path("artifacts"));
        assertEquals(ids(applied.path("instances")), ids(recovered.path("instances")));
        assertActive(recovered, "B");
        assertActive(recovered, "C");
    }

    @Test
    void localInstallPersistsWithoutEnablingAndCompleteApplyReplacesThatSelection(@TempDir Path home)
        throws Exception {
        profile(home, List.of(), List.of());
        var request = PluginAcceptanceHarness.installRequest(home.resolve("plugins"),
            PluginAcceptanceHarness.stagedJar("fibra-storage"));

        var installed = cli(home, "", "plugins", "install", request.source().toString()).getFirst();

        assertEquals(List.of("fibra-storage"), ids(installed.path("artifacts")));
        assertTrue(installed.path("instances").isEmpty());
        assertEquals("[]", Files.readString(home.resolve("config/profiles/default.yaml")));
        assertEquals("[]", Files.readString(home.resolve("config/profiles/default.artifacts.yaml")));
        Files.move(home.resolve("plugins"), home.resolve("removed-candidates"));
        var recovered = cli(home, "", "plugins", "list").getFirst();
        assertEquals(installed.path("artifacts"), recovered.path("artifacts"));

        var applied = cli(home, "", "apply").getFirst();

        assertTrue(applied.path("artifacts").isEmpty());
        assertTrue(applied.path("instances").isEmpty());
        Files.writeString(home.resolve("config/profiles/default.yaml"), "invalid source");
        Files.writeString(home.resolve("config/profiles/default.artifacts.yaml"), "invalid selection");
        var emptyRecovered = cli(home, "", "plugins", "list").getFirst();
        assertTrue(emptyRecovered.path("artifacts").isEmpty());
        assertTrue(emptyRecovered.path("instances").isEmpty());
    }

    private static void profile(Path home, List<?> entries, List<String> packages) throws Exception {
        var profiles = Files.createDirectories(home.resolve("config/profiles"));
        Files.writeString(profiles.resolve("default.yaml"), JSON.writeValueAsString(entries));
        Files.writeString(profiles.resolve("default.artifacts.yaml"), JSON.writeValueAsString(packages));
    }

    private static List<String> packages(Path plugins, Map<String, Path> sources, String... artifactIds) {
        var packages = new ArrayList<String>();
        for (var artifactId : artifactIds) {
            var request = PluginAcceptanceHarness.installRequest(plugins,
                PluginAcceptanceHarness.stagedJar(artifactId));
            sources.put(artifactId, request.source());
            packages.add(request.source().getFileName().toString());
        }
        return packages;
    }

    private static List<Map<String, Object>> storageEntries(Path storage, String... toolIds) {
        var entries = new ArrayList<Map<String, Object>>();
        entries.add(Map.of("id", "storage", "plugin", "storage-json", "config", Map.of("root", storage.toString())));
        for (var toolId : toolIds) entries.add(Map.of("id", toolId, "plugin", "tool-storage"));
        return entries;
    }

    private static List<JsonNode> cli(Path home, String input, String... command) {
        var args = new ArrayList<>(List.of("--home", home.toString(), "--profile", "default"));
        args.addAll(List.of(command));
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var code = FibraCli.run(args.toArray(String[]::new),
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
            new PrintWriter(output, true, StandardCharsets.UTF_8),
            new PrintWriter(error, true, StandardCharsets.UTF_8));
        var stdout = output.toString(StandardCharsets.UTF_8);
        var stderr = error.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, () -> "stdout:\n" + stdout + "\nstderr:\n" + stderr);
        assertEquals("", stderr, () -> "stdout:\n" + stdout);
        var results = stdout.lines().map(String::strip)
            .filter(line -> line.startsWith("{"))
            .map(JSON::readTree).toList();
        assertFalse(results.isEmpty(), () -> "no JSON result in CLI output:\n" + stdout);
        return results;
    }

    private static List<String> ids(JsonNode entries) {
        assertTrue(entries.isArray());
        return entries.valueStream().map(entry -> entry.path("id").asString()).toList();
    }

    private static void assertActive(JsonNode snapshot, String id) {
        var instance = snapshot.path("instances").valueStream()
            .filter(entry -> id.equals(entry.path("id").asString())).findFirst().orElseThrow();
        assertTrue(instance.path("desired").asBoolean());
        assertTrue(instance.path("enabled").asBoolean());
        assertTrue(instance.path("observed").asBoolean());
        assertEquals("ACTIVE", instance.path("state").asString());
        assertTrue(instance.path("requirementSatisfied").asBoolean());
        assertTrue(instance.path("failure").isNull());
    }

    private static void assertInactive(JsonNode snapshot, String id) {
        var instance = snapshot.path("instances").valueStream()
            .filter(entry -> id.equals(entry.path("id").asString())).findFirst().orElseThrow();
        assertTrue(instance.path("desired").asBoolean());
        assertFalse(instance.path("enabled").asBoolean());
        assertFalse(instance.path("observed").asBoolean());
        assertTrue(instance.path("state").isNull());
        assertTrue(instance.path("requirementSatisfied").asBoolean());
        assertTrue(instance.path("failure").isNull());
    }

    private static String identity(JsonNode snapshot, String id) {
        return snapshot.path("instances").valueStream()
            .filter(entry -> id.equals(entry.path("id").asString())).findFirst().orElseThrow()
            .path("identity").asString();
    }

    private static void assertSuccessfulTool(JsonNode result) {
        assertTrue(result.has("isError"));
        assertFalse(result.path("isError").asBoolean());
        assertTrue(result.has("viewRevision"));
        assertTrue(result.path("content").isArray());
        assertTrue(result.has("structuredContent"));
    }

    private static void assertDynamicStorageIsNotOnHostClasspath() {
        for (var name : List.of("com.sstlfsj.fibra.plugins.storage.ConfigStore",
            "com.sstlfsj.fibra.plugins.storage.json.JsonStorageEntrypoint",
            "com.sstlfsj.fibra.plugins.storage.tool.StorageToolEntrypoint")) {
            assertThrows(ClassNotFoundException.class,
                () -> Class.forName(name, false, FibraCli.class.getClassLoader()));
        }
    }
}
