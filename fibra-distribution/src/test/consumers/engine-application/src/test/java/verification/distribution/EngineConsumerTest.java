package verification.distribution;

import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.engine.ExecutionObservation;
import com.sstlfsj.fibra.engine.FileDeploymentTargetStore;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.PluginSelection;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
import com.sstlfsj.fibra.plugins.tool.ToolResult;
import com.sstlfsj.fibra.registry.InMemoryPluginAuditRepository;
import com.sstlfsj.fibra.registry.PluginDeploymentRequest;
import com.sstlfsj.fibra.registry.PluginInstallRequest;
import com.sstlfsj.fibra.registry.PluginRegistry;
import com.sstlfsj.fibra.runtime.java.JavaRuntimeProvider;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineConsumerTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final List<String> FORMAL_ARTIFACTS = List.of(
        "fibra-fs", "fibra-fs-local", "fibra-tool-fs",
        "fibra-subprocess", "fibra-subprocess-local", "fibra-tool-fs-search",
        "fibra-shell", "fibra-shell-local", "fibra-tool-shell",
        "fibra-storage", "fibra-storage-json", "fibra-tool-storage");
    private static final Map<String, LiteralValue> FS_REALM = realm("fibra.fs", "files");
    private static final Map<String, LiteralValue> PROCESS_REALM =
        realm("fibra.subprocess", "processes");
    private static final Map<String, LiteralValue> SHELL_REALM =
        realm("fibra.shell", "shells");
    private static final Map<String, LiteralValue> STORAGE_REALM =
        realm("fibra.storage", "shared");

    @Test
    void resolvesAndInvokesPublishedFormalPluginsFromAnEmptyConsumerRepository(@TempDir Path work)
        throws Exception {
        var pluginArtifacts = Path.of(System.getProperty("fibra.pluginArtifacts"));
        assertEquals(FORMAL_ARTIFACTS.size(), countJars(pluginArtifacts));
        var classpath = List.of(System.getProperty("java.class.path").split(
            Pattern.quote(File.pathSeparator))).stream()
            .map(Path::of)
            .map(Path::getFileName)
            .map(Path::toString)
            .toList();
        FORMAL_ARTIFACTS.forEach(id -> assertFalse(classpath.stream()
                .anyMatch(name -> name.equals(id + ".jar")
                    || name.startsWith(id + "-") && name.endsWith(".jar")),
            () -> id + " leaked onto the host classpath"));
        var externalPluginJar = Path.of(System.getProperty("plugin.jar")).getFileName().toString();
        assertFalse(classpath.contains(externalPluginJar),
            "dynamic external plugin leaked onto the host classpath");

        var content = Files.createDirectories(work.resolve("content"));
        Files.createDirectories(content.resolve("nested"));
        Files.writeString(content.resolve("nested/input.txt"), "needle from distribution\n");
        try (var packages = new PluginPackageStore(work.resolve("packages"));
             var targets = new FileDeploymentTargetStore(work.resolve("targets"));
             var engine = FibraEngine.builder(packages, targets)
                 .runtimeProvider(new JavaRuntimeProvider(List.of()))
                 .contributionKinds(com.sstlfsj.fibra.bridge.ContributionKindRegistry.of(
                     ToolContributions.KIND))
                 .hostTerminationPort(request -> { })
                 .build()) {
            engine.startAsync().block(TIMEOUT);
            var registry = new PluginRegistry(engine, packages, new InMemoryPluginAuditRepository());
            for (var id : FORMAL_ARTIFACTS) {
                registry.install(installRequest(work.resolve("package-sources"), id,
                    pluginArtifacts.resolve(id + ".jar"))).block(TIMEOUT);
            }
            registry.install(installRequest(work.resolve("package-sources"), "external",
                Path.of(System.getProperty("plugin.jar")))).block(TIMEOUT);
            var deployed = registry.deploy(new PluginDeploymentRequest(
                new ArrayList<PluginSelection>(registry.snapshot().selections().values()),
                applicationGraph(content, work.resolve("storage")), ConfigContextSnapshot.empty()))
                .block(TIMEOUT);
            assertTrue(deployed.observed().values().stream()
                .allMatch(unit -> unit.aggregateState() == ExecutionObservation.State.ACTIVE));

            var written = map(invoke(engine, "fs-tools", "write", Map.of(
                "path", "created.txt", "content", "created externally")).structuredContent().orElseThrow().toJava());
            assertEquals("create", written.get("operation"));
            var read = map(invoke(engine, "fs-tools", "read", Map.of(
                "path", "created.txt")).structuredContent().orElseThrow().toJava());
            assertEquals("created externally", map(list(read.get("lines")).getFirst()).get("text"));

            var grep = map(invoke(engine, "search-tools", "grep", Map.of(
                "pattern", "needle", "path", ".")).structuredContent().orElseThrow().toJava());
            assertEquals(1, ((Number) grep.get("seen")).intValue());
            assertEquals("./nested/input.txt", map(list(grep.get("matches")).getFirst()).get("path"));

            var shell = invoke(engine, "shell-tools", "bash", Map.of(
                "command", "printf 'out'; printf 'err' >&2; exit 7",
                "workdir", content.toString(), "timeoutMs", 5_000));
            var shellData = map(shell.structuredContent().orElseThrow().toJava());
            assertEquals(7, ((Number) shellData.get("exitCode")).intValue());
            assertEquals("out", map(shellData.get("stdout")).get("text"));
            assertEquals("err", map(shellData.get("stderr")).get("text"));

            invoke(engine, "config-client", "config", Map.of(
                "operation", "put", "key", "theme", "value", "dark"));
            var formalConfig = map(invoke(engine, "storage-tools", "load", Map.of())
                .structuredContent().orElseThrow().toJava());
            assertEquals("dark", map(formalConfig.get("values")).get("theme"));
            var changes = map(invoke(engine, "storage-tools", "changes", Map.of())
                .structuredContent().orElseThrow().toJava());
            assertEquals(false, changes.get("dropped"));
            var firstChange = map(list(changes.get("changes")).getFirst());
            assertEquals("theme", firstChange.get("key"));
            assertEquals("PUT", firstChange.get("operation"));
            assertEquals("dark", firstChange.get("value"));

            invoke(engine, "storage-tools", "put", Map.of(
                "key", "language", "value", "zh-CN"));
            var externalConfig = map(invoke(engine, "config-client", "config",
                Map.of("operation", "load")).structuredContent().orElseThrow().toJava());
            assertEquals("dark", map(externalConfig.get("values")).get("theme"));
            assertEquals("zh-CN", map(externalConfig.get("values")).get("language"));
        }
    }

    private static DesiredInputGraph applicationGraph(Path content, Path storage) {
        var entries = new ArrayList<DesiredInputEntry>();
        entries.add(entry("fs-provider", "fibra-fs-local", "fs-local", Map.of(
            "root", content.toString(), "spillDirectory", ".fibra-spill"), FS_REALM));
        entries.add(entry("fs-tools", "fibra-tool-fs", "tool-fs", null, FS_REALM));
        entries.add(entry("subprocess-provider", "fibra-subprocess-local", "fibra-subprocess-local", Map.of(
            "nodeExecutable", executable("node").toString()), PROCESS_REALM));
        entries.add(entry("search-tools", "fibra-tool-fs-search", "tool-fs-search", Map.of(
            "rgExecutable", executable("rg").toString(), "workdir", content.toString()),
            PROCESS_REALM));
        var shellRealms = new LinkedHashMap<>(PROCESS_REALM);
        shellRealms.putAll(SHELL_REALM);
        entries.add(entry("shell-provider", "fibra-shell-local", "fibra-shell-local", Map.of(
            "bashExecutable", executable("bash").toString(), "outputMaxBytes", 1_048_576,
            "graceMillis", 500), shellRealms));
        entries.add(entry("shell-tools", "fibra-tool-shell", "fibra-tool-shell", null, shellRealms));
        entries.add(entry("storage-provider", "fibra-storage-json", "storage-json", Map.of(
            "root", storage.toString()), STORAGE_REALM));
        entries.add(entry("storage-tools", "fibra-tool-storage", "tool-storage", null, STORAGE_REALM));
        entries.add(entry("config-client", "external", "external", null, STORAGE_REALM));
        return new DesiredInputGraph(entries);
    }

    private static DesiredInputEntry entry(String id, String plugin, String definition, Object config,
                                           Map<String, LiteralValue> realms) {
        var builder = DesiredInputEntry.builder(id, new PluginDefinitionRef(plugin, "main", definition))
            .realms(realms);
        if (config != null) builder.config(LiteralValue.of(config));
        return builder.build();
    }

    private static PluginInstallRequest installRequest(Path packages, String pluginId, Path source) throws IOException {
        var root = Files.createTempDirectory(Files.createDirectories(packages), "plugin-");
        var lib = Files.createDirectory(root.resolve("lib"));
        var payload = lib.resolve(source.getFileName());
        if ("external".equals(pluginId)) rewriteJavaDescriptor(source, payload,
            "verification.distribution.plugin.Entrypoint");
        else Files.copy(source, payload);
        Files.writeString(root.resolve("fibra-package.yaml"), packageManifest(pluginId,
            "lib/" + source.getFileName(), dependencies(pluginId)));
        return new PluginInstallRequest(root, true);
    }

    private static void rewriteJavaDescriptor(Path source, Path destination, String entrypoint)
        throws IOException {
        try (var input = new java.util.jar.JarFile(source.toFile());
             var output = new java.util.jar.JarOutputStream(Files.newOutputStream(destination))) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.getName().equals("META-INF/fibra/plugin.yaml")) continue;
                output.putNextEntry(new java.util.jar.JarEntry(entry.getName()));
                if (!entry.isDirectory()) input.getInputStream(entry).transferTo(output);
                output.closeEntry();
            }
            output.putNextEntry(new java.util.jar.JarEntry("META-INF/fibra/plugin.yaml"));
            output.write(("entrypoint: " + entrypoint + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static String packageManifest(String id, String payload, List<String> dependencies) {
        var yaml = new StringBuilder("format: 1\nid: ").append(id)
            .append("\nversion: \"1.0.0\"\nfacets:\n  - id: main\n    role: host\n")
            .append("    runtime: java\n    target: host\n    payload: ").append(payload).append('\n');
        if (dependencies.isEmpty()) yaml.append("    dependencies: []\n");
        else {
            yaml.append("    dependencies:\n");
            for (var dependency : dependencies) yaml.append("      - pluginId: ").append(dependency)
                .append("\n        facetId: main\n");
        }
        return yaml.append("    capabilities: []\n").toString();
    }

    private static List<String> dependencies(String id) {
        return switch (id) {
            case "fibra-fs-local", "fibra-tool-fs" -> List.of("fibra-fs");
            case "fibra-subprocess-local", "fibra-tool-fs-search" -> List.of("fibra-subprocess");
            case "fibra-shell-local" -> List.of("fibra-shell", "fibra-subprocess");
            case "fibra-tool-shell" -> List.of("fibra-shell");
            case "fibra-storage-json", "fibra-tool-storage", "external" -> List.of("fibra-storage");
            default -> List.of();
        };
    }

    private static ToolResult invoke(FibraEngine engine, String provider, String localName,
                                     Map<String, ?> arguments) {
        var current = engine.published().current();
        var id = ToolContributions.id(provider, localName);
        var identity = current.contributions().entries().stream()
            .filter(entry -> entry.kind().equals(ToolContributions.KIND.name())
                && entry.id().equals(id))
            .findFirst().orElseThrow().registrationIdentity();
        return engine.published().invoke(current.viewRevision(), identity,
            ToolContributions.KIND, id, ToolRequest.of(arguments)).block(TIMEOUT);
    }

    private static long countJars(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".jar")).count();
        }
    }

    private static Path executable(String name) {
        for (var directory : System.getenv().getOrDefault("PATH", "").split(":")) {
            var candidate = Path.of(directory, name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("required executable is unavailable: " + name);
    }

    private static Map<String, LiteralValue> realm(String service, String value) {
        return Map.of(service, LiteralValue.of(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
