package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliHostTest {
    @TempDir Path work;
    private static final AtomicReference<String> CONFIG = new AtomicReference<>();

    @Test
    void firstOpenBootstrapsBothInputsAndPersistsTheEvaluatedHostContext()
        throws Exception {
        var paths = paths();
        javaPackage(paths.pluginsRoot().resolve("alpha"), "alpha", "1.0.0");
        writeProfile(paths, desired("alpha-entry", "alpha",
            "{$ref: /fibra/workspaceRoot}"), "- alpha\n");

        var host = CliHost.open(paths);
        var target = host.published().current().engine().target().orElseThrow();

        assertEquals(1, target.targetRevision());
        assertEquals(Set.of(new PluginId("alpha")), target.selections().keySet());
        assertEquals(Set.of("alpha-entry"), target.desiredGraph().plugins().keySet());
        assertEquals(paths.configContext(), target.configContext());
        assertEquals(paths.workspaceRoot().toString(), CONFIG.get());
        assertThrows(RuntimeException.class, () -> CliHost.open(paths));
        host.close();

        Files.delete(paths.profileFile());
        Files.delete(paths.profilePackagesFile());
        var changedPaths = CliPaths.resolve(work.resolve("other-home"), "default",
            work.resolve("missing-config"), work.resolve("missing-packages"),
            paths.dataRoot(), work.resolve("other-node"));
        try (var reopened = CliHost.open(changedPaths)) {
            var restored = reopened.published().current().engine().target()
                .orElseThrow();
            assertEquals(target, restored);
            assertEquals(paths.configContext(), restored.configContext());
            assertNotEquals(changedPaths.configContext(), restored.configContext());
        }
    }

    @Test
    void applyAtomicallyReplacesTheCompletePackageAndDesiredSelection()
        throws Exception {
        var paths = paths();
        javaPackage(paths.pluginsRoot().resolve("alpha"), "alpha", "1.0.0");
        javaPackage(paths.pluginsRoot().resolve("beta"), "beta", "1.0.0");
        writeProfile(paths, desired("alpha-entry", "alpha", "alpha"),
            "- alpha\n");

        try (var host = CliHost.open(paths)) {
            var before = host.registry().snapshot().target().orElseThrow();
            writeProfile(paths, desired("beta-entry", "beta", "beta"),
                "- beta\n");

            var applied = host.apply().target().orElseThrow();

            assertEquals(before.targetRevision() + 1,
                applied.targetRevision());
            assertEquals(Set.of(new PluginId("beta")),
                applied.selections().keySet());
            assertEquals(Set.of("beta-entry"),
                applied.desiredGraph().plugins().keySet());
            assertFalse(applied.selections().containsKey(new PluginId("alpha")));
            assertFalse(applied.desiredGraph().plugins()
                .containsKey("alpha-entry"));
            assertEquals(paths.configContext(), applied.configContext());
        }
    }

    @Test
    void duplicatePluginIdsAndFailedDeploymentDoNotChangeTheDurableTarget()
        throws Exception {
        var paths = paths();
        javaPackage(paths.pluginsRoot().resolve("alpha"), "alpha", "1.0.0");
        writeProfile(paths, desired("alpha-entry", "alpha", "alpha"),
            "- alpha\n");

        try (var host = CliHost.open(paths)) {
            var before = host.registry().snapshot().target().orElseThrow();
            javaPackage(paths.pluginsRoot().resolve("duplicate-a"), "duplicate",
                "1.0.0");
            javaPackage(paths.pluginsRoot().resolve("duplicate-b"), "duplicate",
                "2.0.0");
            writeProfile(paths, "[]\n",
                "- duplicate-a\n- duplicate-b\n");

            var duplicate = assertThrows(IllegalArgumentException.class,
                host::apply);

            assertTrue(duplicate.getMessage().contains("duplicate plugin id"));
            assertEquals(before,
                host.registry().snapshot().target().orElseThrow());

            javaPackage(paths.pluginsRoot().resolve("orphan"), "orphan",
                "1.0.0");
            writeProfile(paths, desired("missing-entry", "missing", "missing"),
                "- orphan\n");

            assertThrows(RuntimeException.class, host::apply);
            assertEquals(before,
                host.registry().snapshot().target().orElseThrow());
        }
        try (var packages = new PluginPackageStore(paths.packageStoreRoot())) {
            assertEquals(1, packages.history(new PluginId("orphan")).size(),
                "published package content may remain as unreferenced history");
        }
    }

    private CliPaths paths() {
        return CliPaths.resolve(work.resolve("home"), "default", null, null, null, null);
    }

    private static void writeProfile(CliPaths paths, String desired,
                                     String packages) throws Exception {
        Files.createDirectories(paths.profileFile().getParent());
        Files.writeString(paths.profileFile(), desired);
        Files.writeString(paths.profilePackagesFile(), packages);
    }

    private static String desired(String entryId, String pluginId,
                                  String config) {
        return "- id: " + entryId + "\n"
            + "  plugin: {id: " + pluginId
            + ", facet: main, definition: context}\n"
            + "  config: " + config + "\n";
    }

    private static void javaPackage(Path root, String pluginId, String version)
        throws Exception {
        Files.createDirectories(root);
        var jar = root.resolve("plugin.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/fibra/plugin.yaml"));
            output.write(("entrypoint: " + ContextEntrypoint.class.getName()
                + '\n').getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
            var className = ContextEntrypoint.class.getName()
                .replace('.', '/') + ".class";
            output.putNextEntry(new JarEntry(className));
            try (InputStream input = ContextEntrypoint.class
                .getResourceAsStream('/' + className)) {
                output.write(java.util.Objects.requireNonNull(input,
                    className).readAllBytes());
            }
            output.closeEntry();
        }
        Files.writeString(root.resolve("fibra-package.yaml"),
            "format: 1\n"
                + "id: " + pluginId + "\n"
                + "version: " + version + "\n"
                + "facets:\n"
                + "  - id: main\n"
                + "    role: host\n"
                + "    runtime: java\n"
                + "    target: host\n"
                + "    payload: plugin.jar\n"
                + "    dependencies: []\n"
                + "    capabilities: []\n");
    }

    public static final class ContextEntrypoint
        implements PluginEntrypoint<String> {
        @Override public PluginDefinition<String> definition() {
            return PluginDefinition.builder("context", String.class,
                () -> (context, config) -> {
                    CONFIG.set(config);
                    return Mono.empty();
                }).build();
        }
    }
}
