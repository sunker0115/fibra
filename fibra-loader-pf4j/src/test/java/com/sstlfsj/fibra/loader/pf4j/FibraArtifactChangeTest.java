package com.sstlfsj.fibra.loader.pf4j;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import example.fibra.plugin.ConfigurableEntrypoint;
import example.fibra.plugin.ConfigurableReplacementEntrypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraArtifactChangeTest {
    private static final ServiceKey<String> VALUE = ServiceKey.of("fixture.value", String.class);

    @Test
    void prepareHasNoRuntimeSideEffectsAndCompleteReleasesPreviousData(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        PluginPackageFixtures.executableDirectory(plugins, "fixture", "1.0.0", "",
            ConfigurableEntrypoint.class);
        var candidate = candidate(work, "fixture", "2.0.0",
            ConfigurableReplacementEntrypoint.class);
        var workspace = Files.createDirectory(work.resolve("change"));

        try (Context root = FibraRuntime.create();
             var loader = new FibraPluginLoader(root, plugins)) {
            loader.loadArtifacts();
            loader.mount(PluginInstanceSpec.builder("fixture", "fixture")
                .parentContext(root).config("one").build());
            var oldClassLoader = loader.pluginClassLoader("fixture");

            loader.runExclusive(() -> {
                try (var change = loader.prepareArtifacts(List.of(candidate), workspace)) {
                    assertEquals(List.of("fixture"), change.changedArtifactIds());
                    assertEquals("2.0.0",
                        change.targetCatalog().artifacts().getFirst().version());
                    assertEquals(String.class,
                        change.targetCatalog().configType("fixture").orElseThrow());
                    assertEquals("1.0.0", installedVersion(plugins.resolve("fixture")));
                    assertEquals("fixture:one", root.get(VALUE));
                    assertFalse(oldClassLoader.isClosed());

                    change.commit();

                    assertTrue(oldClassLoader.isClosed());
                    assertEquals("2.0.0", installedVersion(plugins.resolve("fixture")));
                    assertEquals("v2:fixture:one", root.get(VALUE));
                    assertTrue(Files.exists(workspace.resolve("previous/fixture")));

                    change.complete();
                    assertFalse(Files.exists(workspace));
                    assertThrows(IllegalStateException.class,
                        () -> change.targetCatalog().configType("fixture"));
                }
            });
        }
    }

    @Test
    void closeRollsBackPreparedAndCommittedChanges(@TempDir Path work) throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        PluginPackageFixtures.executableDirectory(plugins, "fixture", "1.0.0", "",
            ConfigurableEntrypoint.class);
        var candidate = candidate(work, "fixture", "2.0.0",
            ConfigurableReplacementEntrypoint.class);

        try (Context root = FibraRuntime.create();
             var loader = new FibraPluginLoader(root, plugins)) {
            loader.loadArtifacts();
            loader.mount(PluginInstanceSpec.builder("fixture", "fixture")
                .parentContext(root).config("one").build());

            var preparedWorkspace = Files.createDirectory(work.resolve("prepared"));
            loader.runExclusive(() -> {
                var change = loader.prepareArtifacts(List.of(candidate), preparedWorkspace);
                change.close();
            });
            assertEquals("1.0.0", installedVersion(plugins.resolve("fixture")));
            assertFalse(Files.exists(preparedWorkspace));

            var committedWorkspace = Files.createDirectory(work.resolve("committed"));
            loader.runExclusive(() -> {
                var change = loader.prepareArtifacts(List.of(candidate), committedWorkspace);
                change.commit();
                change.close();
            });
            assertEquals("1.0.0", installedVersion(plugins.resolve("fixture")));
            assertEquals("fixture:one", root.get(VALUE));
            assertFalse(Files.exists(committedWorkspace));
        }
    }

    private static Path candidate(Path work, String id, String version,
                                  Class<?> entrypoint) throws Exception {
        var source = Files.createTempDirectory(work, "candidate-");
        var packageRoot = PluginPackageFixtures.executableDirectory(source, id, version, "",
            entrypoint);
        return PluginPackageFixtures.zipDirectory(packageRoot,
            Files.createTempFile(work, id + '-', ".zip"));
    }

    private static String installedVersion(Path packageRoot) {
        return new PluginPackageInspector().inspectDirectory(packageRoot)
            .descriptor().getVersion();
    }
}
