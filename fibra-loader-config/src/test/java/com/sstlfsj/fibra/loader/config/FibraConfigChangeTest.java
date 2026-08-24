package com.sstlfsj.fibra.loader.config;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import example.fibra.config.ConfigLoaderEntrypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraConfigChangeTest {
    private static final ServiceKey<String> VALUE =
        ServiceKey.of("fixture.value", String.class);

    @Test
    void currentPrepareHasNoSideEffectsAndCommittedCloseRestoresRuntime(
        @TempDir Path work) throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        FibraConfigLoaderTest.writePluginJar(plugins.resolve("fixture.jar"),
            ConfigLoaderEntrypoint.class);
        var config = work.resolve("fibra.yaml");
        writeConfig(config, "old");

        try (Context root = FibraRuntime.create();
             var artifacts = new FibraPluginLoader(root, plugins);
             var loader = FibraConfigLoader.builder(root, artifacts, config).build()) {
            artifacts.loadArtifacts();
            var original = loader.load();
            var originalFibra = loader.resolve("entry").orElseThrow().fibra();
            writeConfig(config, "new");
            var workspace = Files.createDirectory(work.resolve("config-change"));

            artifacts.runExclusive(() -> {
                var change = loader.prepareCurrent(artifacts.catalog(), workspace);
                assertEquals("new", change.targetSnapshot().resolve("entry")
                    .orElseThrow().config());
                assertSame(original, loader.snapshot());
                assertSame(originalFibra, loader.resolve("entry").orElseThrow().fibra());
                assertEquals("entry:old", loader.resolve("entry").orElseThrow()
                    .context().get(VALUE));

                change.commit();
                assertEquals("entry:new", loader.resolve("entry").orElseThrow()
                    .context().get(VALUE));
                change.close();
            });

            assertSame(original, loader.snapshot());
            assertEquals("entry:old", loader.resolve("entry").orElseThrow()
                .context().get(VALUE));
            assertFalse(Files.exists(workspace));
        }
    }

    @Test
    void replacementMapsFilesAndRollsBackWithArtifactParticipant(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        FibraConfigLoaderTest.writePluginJar(plugins.resolve("fixture.jar"),
            ConfigLoaderEntrypoint.class);
        var config = work.resolve("host-config").resolve("fibra.yaml");
        Files.createDirectories(config.getParent());
        writeConfig(config, "old");
        var stagedRoot = Files.createDirectories(work.resolve("deployment/config"));
        var stagedConfig = stagedRoot.resolve("fibra.yaml");
        writeConfig(stagedConfig, "deployed");

        try (Context root = FibraRuntime.create();
             var artifacts = new FibraPluginLoader(root, plugins);
             var loader = FibraConfigLoader.builder(root, artifacts, config).build()) {
            artifacts.loadArtifacts();
            var original = loader.load();
            var artifactWorkspace = Files.createDirectory(work.resolve("artifact-change"));
            var configWorkspace = Files.createDirectory(work.resolve("config-change"));

            artifacts.runExclusive(() -> {
                try (var artifactChange = artifacts.prepareArtifacts(List.of(),
                    artifactWorkspace);
                     var configChange = loader.prepareReplacement(stagedConfig,
                         artifactChange, configWorkspace)) {
                    assertEquals("deployed", configChange.targetSnapshot().resolve("entry")
                        .orElseThrow().config());
                    assertEquals("old", loader.snapshot().resolve("entry")
                        .orElseThrow().config());
                    assertTrue(read(config).contains("old"));

                    artifactChange.commit();
                    configChange.commit();
                    assertTrue(read(config).contains("deployed"));
                    assertEquals("entry:deployed", loader.resolve("entry").orElseThrow()
                        .context().get(VALUE));
                }
            });

            assertSame(original, loader.snapshot());
            assertTrue(read(config).contains("old"));
            assertEquals("entry:old", loader.resolve("entry").orElseThrow()
                .context().get(VALUE));
            assertFalse(Files.exists(artifactWorkspace));
            assertFalse(Files.exists(configWorkspace));
        }
    }

    @Test
    void sourcePathsIncludeLastFailedAttemptWithoutStartingAWatcher(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        var config = work.resolve("fibra.yaml");
        Files.writeString(config, "- id: late\n  include: ./missing.yaml\n");

        try (Context root = FibraRuntime.create();
             var artifacts = new FibraPluginLoader(root, plugins);
             var loader = FibraConfigLoader.builder(root, artifacts, config).build()) {
            artifacts.loadArtifacts();
            assertThrows(FibraConfigException.class, loader::resolve);
            assertTrue(loader.sourcePaths().contains(config.toAbsolutePath().normalize()));
            assertTrue(loader.sourcePaths().contains(
                config.toRealPath().getParent().resolve("missing.yaml").normalize()));
        }
    }

    private static void writeConfig(Path path, String value) throws Exception {
        Files.writeString(path, """
            - id: entry
              name: fixture
              isolate:
                fixture.value: entry
              config: %s
            """.formatted(value));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
