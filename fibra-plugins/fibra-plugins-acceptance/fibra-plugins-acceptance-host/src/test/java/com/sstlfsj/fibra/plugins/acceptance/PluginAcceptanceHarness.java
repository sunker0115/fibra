package com.sstlfsj.fibra.plugins.acceptance;

import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.engine.DeploymentTargetStore;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.PluginSelection;
import com.sstlfsj.fibra.engine.PublishedView;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionKindRegistry;
import com.sstlfsj.fibra.registry.InMemoryPluginAuditRepository;
import com.sstlfsj.fibra.registry.PluginDeploymentRequest;
import com.sstlfsj.fibra.registry.PluginInstallRequest;
import com.sstlfsj.fibra.registry.PluginRegistry;
import com.sstlfsj.fibra.registry.RegistrySnapshot;
import com.sstlfsj.fibra.runtime.java.JavaRuntimeProvider;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
import com.sstlfsj.fibra.plugins.tool.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

final class PluginAcceptanceHarness implements AutoCloseable {
    static final Duration TIMEOUT = Duration.ofSeconds(20);
    static final String[] ALL_PACKAGES = {
        "fibra-fs", "fibra-fs-local", "fibra-tool-fs",
        "fibra-subprocess", "fibra-subprocess-local", "fibra-tool-fs-search",
        "fibra-shell", "fibra-shell-local", "fibra-tool-shell",
        "fibra-storage", "fibra-storage-json", "fibra-tool-storage"
    };

    private static final Map<String, List<String>> DEPENDENCIES = dependencies();

    private final FibraEngine engine;
    private final PluginRegistry registry;
    private final PluginPackageStore packageStore;
    private final DeploymentTargetStore targetStore;
    private final Path packages;

    private PluginAcceptanceHarness(FibraEngine engine, PluginPackageStore packageStore,
                                    DeploymentTargetStore targetStore, Path packages) {
        this.engine = engine;
        this.packageStore = packageStore;
        this.targetStore = targetStore;
        this.packages = packages;
        registry = new PluginRegistry(engine, packageStore, new InMemoryPluginAuditRepository());
    }

    static PluginAcceptanceHarness start(Path storeRoot, DeploymentTargetStore targetStore) {
        var packageStore = new PluginPackageStore(storeRoot);
        var engine = FibraEngine.builder(packageStore, targetStore)
            .runtimeProvider(new JavaRuntimeProvider(List.of()))
            .contributionKinds(ContributionKindRegistry.of(ToolContributions.KIND))
            .hostTerminationPort(request -> { })
            .build();
        engine.startAsync().block(TIMEOUT);
        return new PluginAcceptanceHarness(engine, packageStore, targetStore,
            storeRoot.resolveSibling("package-sources"));
    }

    RegistrySnapshot deploy(DesiredInputGraph graph, String... pluginIds) {
        RegistrySnapshot installed = registry.snapshot();
        for (var pluginId : pluginIds) {
            installed = registry.install(installRequest(packages, stagedJar(pluginId))).block(TIMEOUT);
        }
        return registry.deploy(new PluginDeploymentRequest(
            new ArrayList<PluginSelection>(installed.selections().values()), graph,
            ConfigContextSnapshot.empty())).block(TIMEOUT);
    }

    ToolResult invoke(String provider, String localName, Map<String, ?> arguments) {
        var current = engine.published().current();
        return engine.published().invoke(current.viewRevision(), identity(current, ToolContributions.KIND,
                ToolContributions.id(provider, localName)), ToolContributions.KIND,
            ToolContributions.id(provider, localName), ToolRequest.of(arguments)).block(TIMEOUT);
    }

    private static long identity(PublishedView view, ContributionKind<?, ?, ?> kind, ContributionId id) {
        return view.contributions().entries().stream()
            .filter(entry -> entry.kind().equals(kind.name()) && entry.id().equals(id))
            .map(entry -> entry.registrationIdentity()).findFirst().orElseThrow();
    }

    FibraEngine engine() {
        return engine;
    }

    PluginRegistry registry() {
        return registry;
    }

    static PluginInstallRequest installRequest(Path packages, Path source) {
        try {
            var pluginId = pluginId(source);
            var version = source.getFileName().toString()
                .substring(pluginId.length() + 1, source.getFileName().toString().length() - 4);
            var root = Files.createTempDirectory(Files.createDirectories(packages), "plugin-");
            var lib = Files.createDirectory(root.resolve("lib"));
            Files.copy(source, lib.resolve(source.getFileName()));
            Files.writeString(root.resolve("fibra-package.yaml"), packageManifest(pluginId,
                version, "lib/" + source.getFileName(), DEPENDENCIES.get(pluginId)));
            return new PluginInstallRequest(root, true);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot package staged plugin " + source, failure);
        }
    }

    static Path stagedJar(String artifactId) {
        var root = Path.of(System.getProperty("fibra.pluginArtifacts"));
        try (var paths = Files.list(root)) {
            var matches = paths.filter(path -> path.getFileName().toString().endsWith(".jar"))
                .filter(path -> pluginId(path).equals(artifactId))
                .toList();
            if (matches.size() != 1) {
                throw new IllegalStateException("expected one staged plugin artifact "
                    + artifactId + " but found " + matches);
            }
            return matches.getFirst();
        } catch (IOException failure) {
            throw new IllegalStateException("cannot inspect staged plugin artifacts", failure);
        }
    }

    static String manifest(Path jar) {
        try (var archive = new JarFile(jar.toFile(), true)) {
            var entry = archive.getJarEntry("META-INF/fibra/plugin.yaml");
            if (entry == null) throw new IllegalStateException("missing plugin manifest in " + jar);
            try (var input = archive.getInputStream(entry)) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read plugin manifest " + jar, failure);
        }
    }

    private static String pluginId(Path jar) {
        var name = jar.getFileName().toString();
        return DEPENDENCIES.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .filter(id -> name.startsWith(id + '-') && name.endsWith(".jar"))
            .findFirst().orElseThrow(() -> new IllegalArgumentException(
                "unknown staged plugin JAR " + jar));
    }

    private static String packageManifest(String pluginId, String version, String payload,
                                          List<String> dependencies) {
        var manifest = new StringBuilder()
            .append("format: 1\n")
            .append("id: ").append(pluginId).append('\n')
            .append("version: \"").append(version).append("\"\n")
            .append("facets:\n")
            .append("  - id: main\n")
            .append("    role: host\n")
            .append("    runtime: java\n")
            .append("    target: host\n")
            .append("    payload: ").append(payload).append('\n');
        if (dependencies.isEmpty()) {
            manifest.append("    dependencies: []\n");
        } else {
            manifest.append("    dependencies:\n");
            for (var dependency : dependencies) {
                manifest.append("      - pluginId: ").append(dependency).append('\n')
                    .append("        facetId: main\n");
            }
        }
        return manifest.append("    capabilities: []\n").toString();
    }

    private static Map<String, List<String>> dependencies() {
        var result = new LinkedHashMap<String, List<String>>();
        result.put("fibra-fs", List.of());
        result.put("fibra-fs-local", List.of("fibra-fs"));
        result.put("fibra-tool-fs", List.of("fibra-fs"));
        result.put("fibra-subprocess", List.of());
        result.put("fibra-subprocess-local", List.of("fibra-subprocess"));
        result.put("fibra-tool-fs-search", List.of("fibra-subprocess"));
        result.put("fibra-shell", List.of());
        result.put("fibra-shell-local", List.of("fibra-shell", "fibra-subprocess"));
        result.put("fibra-tool-shell", List.of("fibra-shell"));
        result.put("fibra-storage", List.of());
        result.put("fibra-storage-json", List.of("fibra-storage"));
        result.put("fibra-tool-storage", List.of("fibra-storage"));
        return Map.copyOf(result);
    }

    static Path executable(String name) {
        for (var directory : System.getenv().getOrDefault("PATH", "").split(":")) {
            var candidate = Path.of(directory, name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("required executable is unavailable: " + name);
    }

    @Override
    public void close() {
        Throwable failure = null;
        try { engine.close(); } catch (Throwable error) { failure = error; }
        try { targetStore.close(); } catch (Throwable error) { failure = append(failure, error); }
        try { packageStore.close(); } catch (Throwable error) { failure = append(failure, error); }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("cannot close plugin acceptance harness", failure);
    }

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }
}
