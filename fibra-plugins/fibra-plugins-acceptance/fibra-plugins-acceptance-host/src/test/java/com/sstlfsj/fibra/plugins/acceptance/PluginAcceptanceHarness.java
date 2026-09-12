package com.sstlfsj.fibra.plugins.acceptance;

import com.sstlfsj.fibra.artifact.ArtifactPackage;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.DesiredStateRepository;
import com.sstlfsj.fibra.engine.EngineStateStore;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.registry.InMemoryPluginAuditRepository;
import com.sstlfsj.fibra.registry.PluginDeploymentRequest;
import com.sstlfsj.fibra.registry.PluginInstallRequest;
import com.sstlfsj.fibra.registry.PluginRegistry;
import com.sstlfsj.fibra.registry.RegistrySnapshot;
import com.sstlfsj.fibra.runtime.java.JavaPluginRuntimeAdapter;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
import com.sstlfsj.fibra.plugins.tool.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

final class PluginAcceptanceHarness implements AutoCloseable {
    static final Duration TIMEOUT = Duration.ofSeconds(20);
    static final String[] ALL_ARTIFACTS = {
        "fibra-fs", "fibra-fs-local", "fibra-tool-fs",
        "fibra-subprocess", "fibra-subprocess-local", "fibra-tool-fs-search",
        "fibra-shell", "fibra-shell-local", "fibra-tool-shell",
        "fibra-storage", "fibra-storage-json", "fibra-tool-storage"
    };

    private static final Pattern ID = Pattern.compile("(?m)^id: ([^\\s]+)$");

    private final FibraEngine engine;
    private final PluginRegistry registry;
    private final Path packages;

    private PluginAcceptanceHarness(FibraEngine engine, Path packages) {
        this.engine = engine;
        this.packages = packages;
        registry = new PluginRegistry(engine, new InMemoryPluginAuditRepository());
    }

    static PluginAcceptanceHarness start(Path artifactStore, EngineStateStore stateStore,
                                         DesiredStateRepository desired) {
        var engine = FibraEngine.builder(desired)
            .artifactStore(new ArtifactStore(artifactStore))
            .stateStore(stateStore)
            .runtimeAdapter(new JavaPluginRuntimeAdapter())
            .build();
        engine.start().block(TIMEOUT);
        return new PluginAcceptanceHarness(engine, artifactStore.resolveSibling("packages"));
    }

    RegistrySnapshot deploy(DesiredInputGraph graph, String... artifactIds) {
        var artifacts = new ArrayList<PluginInstallRequest>();
        for (var artifactId : artifactIds) artifacts.add(installRequest(packages, stagedJar(artifactId)));
        return registry.deploy(new PluginDeploymentRequest(artifacts, graph)).block(TIMEOUT);
    }

    ToolResult invoke(String provider, String localName, Map<String, ?> arguments) {
        var current = engine.published().current();
        return engine.published().invoke(current.viewRevision(), ToolContributions.KIND,
            ToolContributions.id(provider, localName), ToolRequest.of(arguments)).block(TIMEOUT);
    }

    FibraEngine engine() {
        return engine;
    }

    PluginRegistry registry() {
        return registry;
    }

    static PluginInstallRequest installRequest(Path packages, Path source) {
        try {
            var root = Files.createTempDirectory(Files.createDirectories(packages), "plugin-");
            var lib = Files.createDirectory(root.resolve("lib"));
            Files.copy(source, lib.resolve(source.getFileName()));
            Files.writeString(root.resolve("plugin.properties"),
                "formatVersion=1\nruntime=java\npayload=lib/" + source.getFileName() + "\n");
            var candidate = new JavaPluginRuntimeAdapter().probe(ArtifactPackage.read(root)).block(TIMEOUT);
            return PluginInstallRequest.builder().artifactId(candidate.artifactId())
                .runtimeId(candidate.runtimeId()).version(candidate.version()).source(candidate.source()).build();
        } catch (IOException failure) {
            throw new IllegalStateException("cannot package staged plugin " + source, failure);
        }
    }

    static Path stagedJar(String artifactId) {
        var root = Path.of(System.getProperty("fibra.pluginArtifacts"));
        try (var paths = Files.list(root)) {
            var matches = paths.filter(path -> path.getFileName().toString().endsWith(".jar"))
                .filter(path -> artifactId.equals(manifestId(path))).toList();
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

    private static String manifestId(Path jar) {
        try {
            var match = ID.matcher(manifest(jar));
            return match.find() ? match.group(1) : null;
        } catch (IllegalStateException ignored) {
            return null;
        }
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
        engine.close();
    }
}
