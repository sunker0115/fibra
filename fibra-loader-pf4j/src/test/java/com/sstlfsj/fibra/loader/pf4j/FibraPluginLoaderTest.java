package com.sstlfsj.fibra.loader.pf4j;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.FibraState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import example.fibra.plugin.ConfigurableEntrypoint;
import example.fibra.plugin.ConfigurableReplacementEntrypoint;
import example.fibra.plugin.ConsumerEntrypoint;
import example.fibra.plugin.FixtureEntrypoint;
import example.fibra.plugin.ProviderEntrypoint;
import example.fibra.plugin.ReplacementProviderEntrypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraPluginLoaderTest {
    private static final ServiceKey<String> VALUE = ServiceKey.of("fixture.value", String.class);

    @Test
    void initializesFromTheCompleteDiskGraphAndReloadsAnExplicitlyUnloadedPackage(
        @TempDir Path work) throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        PluginPackageFixtures.executableDirectory(plugins, "fixture", "1.0.0", "",
            FixtureEntrypoint.class);

        try (Context root = FibraRuntime.create();
             var loader = new FibraPluginLoader(root, plugins)) {
            assertThrows(IllegalStateException.class,
                () -> loader.mount(instance(root, "fixture", "fixture")));
            assertThrows(IllegalStateException.class,
                () -> loader.applyArtifacts(List.of(work.resolve("missing.zip"))));

            assertEquals(List.of("fixture"), loader.loadArtifacts());
            assertEquals(List.of("fixture"), loader.loadArtifacts());
            loader.mount(instance(root, "fixture", "fixture"));
            assertTrue(loader.unloadArtifact("fixture"));
            assertEquals(List.of(), loader.artifactIds());
            assertTrue(Files.isDirectory(plugins.resolve("fixture")));

            assertEquals(List.of("fixture"), loader.loadArtifacts());
            assertEquals(FibraState.ACTIVE,
                loader.mount(instance(root, "fixture", "fixture")).state());
        }
    }

    @Test
    void mountsUpdatesAndUnmountsMultipleEntriesFromOneDirectoryPackage(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        PluginPackageFixtures.executableDirectory(plugins, "fixture", "1.0.0", "",
            ConfigurableEntrypoint.class);

        try (Context root = FibraRuntime.create();
             var loader = new FibraPluginLoader(root, plugins)) {
            loader.loadArtifacts();
            var firstContext = root.isolate(VALUE, "first");
            var secondContext = root.isolate(VALUE, "second");
            var first = loader.mount(PluginInstanceSpec.builder("first", "fixture")
                .parentContext(firstContext).config("one").build());
            var second = loader.mount(PluginInstanceSpec.builder("second", "fixture")
                .parentContext(secondContext).config("two").build());

            assertEquals(String.class, loader.configType("fixture"));
            assertEquals(List.of("first", "second"), loader.entryIds());
            assertEquals("first:one", firstContext.get(VALUE));
            assertEquals("second:two", secondContext.get(VALUE));
            assertNotEquals(first.uid(), second.uid());
            assertSame(first, loader.update("first", "updated"));

            loader.unmount("first");
            assertEquals(List.of("second"), loader.entryIds());
            assertSame(second, loader.fibra("second").orElseThrow());
        }
    }

    @Test
    void appliesUpgradeAndRebuildsEveryEntryWithTheNewClassLoader(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        PluginPackageFixtures.executableDirectory(plugins, "fixture", "1.0.0", "",
            ConfigurableEntrypoint.class);
        var candidate = candidate(work, "fixture", "2.0.0", "",
            ConfigurableReplacementEntrypoint.class);

        try (Context root = FibraRuntime.create();
             var loader = new FibraPluginLoader(root, plugins)) {
            loader.loadArtifacts();
            var firstContext = root.isolate(VALUE, "first");
            var secondContext = root.isolate(VALUE, "second");
            loader.mount(PluginInstanceSpec.builder("first", "fixture")
                .parentContext(firstContext).config("one").build());
            loader.mount(PluginInstanceSpec.builder("second", "fixture")
                .parentContext(secondContext).config("two").build());
            var oldClassLoader = loader.pluginClassLoader("fixture");

            assertEquals(List.of("fixture"), loader.applyArtifacts(List.of(candidate)));

            assertTrue(oldClassLoader.isClosed());
            assertEquals(List.of("first", "second"), loader.entryIds());
            assertEquals("v2:first:one", firstContext.get(VALUE));
            assertEquals("v2:second:two", secondContext.get(VALUE));
            assertEquals("2.0.0", installedVersion(plugins.resolve("fixture")));
            assertTrue(Files.isRegularFile(candidate));
        }
    }

    @Test
    void rollsBackDirectoriesRuntimeAndServicesWhenTheNewPackageCannotMount(
        @TempDir Path work) throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        PluginPackageFixtures.executableDirectory(plugins, "fixture", "1.0.0", "",
            FixtureEntrypoint.class);
        var candidate = candidate(work, "fixture", "2.0.0", "");

        try (Context root = FibraRuntime.create();
             var loader = new FibraPluginLoader(root, plugins)) {
            loader.loadArtifacts();
            loader.mount(instance(root, "fixture", "fixture"));
            var oldClassLoader = loader.pluginClassLoader("fixture");

            var error = assertThrows(FibraArtifactException.class,
                () -> loader.applyArtifacts(List.of(candidate)));

            assertEquals(FibraArtifactErrorStage.APPLY, error.stage());
            assertTrue(oldClassLoader.isClosed());
            assertEquals("1.0.0", installedVersion(plugins.resolve("fixture")));
            assertEquals("fixture", root.get(VALUE));
            assertEquals(List.of("fixture"), loader.entryIds());
            assertFalse(Files.exists(plugins.resolve(".fibra-transactions")));
        }
    }

    @Test
    void appliesAnExplicitMultiPackageUpgradeInDependencyOrder(@TempDir Path work)
        throws Exception {
        PluginLifecycleRecorder.EVENTS.clear();
        var plugins = Files.createDirectory(work.resolve("plugins"));
        PluginPackageFixtures.executableDirectory(plugins, "provider", "1.0.0", "",
            ProviderEntrypoint.class);
        PluginPackageFixtures.executableDirectory(plugins, "consumer", "1.0.0",
            "provider@>=1.0.0 & <2.0.0", ConsumerEntrypoint.class);
        var provider = candidate(work, "provider", "2.0.0", "",
            ReplacementProviderEntrypoint.class);
        var consumer = candidate(work, "consumer", "2.0.0",
            "provider@>=2.0.0 & <3.0.0", ConsumerEntrypoint.class);

        try (Context root = FibraRuntime.create();
             var loader = new FibraPluginLoader(root, plugins)) {
            loader.loadArtifacts();
            loader.mount(instance(root, "provider", "provider"));
            loader.mount(instance(root, "consumer", "consumer"));
            var oldProvider = loader.pluginClassLoader("provider");
            var oldConsumer = loader.pluginClassLoader("consumer");

            assertEquals(List.of("consumer", "provider"),
                loader.applyArtifacts(List.of(provider, consumer)));

            assertTrue(oldProvider.isClosed());
            assertTrue(oldConsumer.isClosed());
            assertEquals(List.of(
                "provider:start", "consumer:start",
                "consumer:stop", "provider:stop",
                "provider-v2:start", "consumer:start"),
                PluginLifecycleRecorder.EVENTS);
        }
    }

    @Test
    void supportsNewInstallNoOpDowngradeAndRejectsAmbiguousInputs(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        var v2 = candidate(work, "contract", "2.0.0", "");

        try (Context root = FibraRuntime.create();
             var loader = new FibraPluginLoader(root, plugins)) {
            loader.loadArtifacts();
            assertThrows(IllegalArgumentException.class,
                () -> loader.applyArtifacts(List.of()));
            assertThrows(IllegalArgumentException.class,
                () -> loader.applyArtifacts(List.of(v2, v2)));

            assertEquals(List.of("contract"), loader.applyArtifacts(List.of(v2)));
            var identical = PluginPackageFixtures.zipDirectory(plugins.resolve("contract"),
                work.resolve("identical.zip"));
            assertEquals(List.of("contract"), loader.applyArtifacts(List.of(identical)));
            assertFalse(Files.exists(plugins.resolve(".fibra-transactions")));

            var v1 = candidate(work, "contract", "1.0.0", "");
            loader.applyArtifacts(List.of(v1));
            assertEquals("1.0.0", installedVersion(plugins.resolve("contract")));
        }
    }

    @Test
    void rejectsSameVersionWithDifferentContentAndBrokenProspectiveGraph(
        @TempDir Path work) throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        PluginPackageFixtures.standardDirectory(plugins, "contract", "1.0.0");
        var changed = candidate(work, "contract", "1.0.0", "",
            FixtureEntrypoint.class);
        var broken = candidate(work, "consumer", "1.0.0", "missing@>=1.0.0");

        try (Context root = FibraRuntime.create();
             var loader = new FibraPluginLoader(root, plugins)) {
            loader.loadArtifacts();
            assertEquals(FibraArtifactErrorStage.VALIDATE,
                assertThrows(FibraArtifactException.class,
                    () -> loader.applyArtifacts(List.of(changed))).stage());
            assertEquals(FibraArtifactErrorStage.RESOLVE,
                assertThrows(FibraArtifactException.class,
                    () -> loader.applyArtifacts(List.of(broken))).stage());
            assertEquals("1.0.0", installedVersion(plugins.resolve("contract")));
            assertEquals(List.of("contract"), loader.artifactIds());
        }
    }

    private static Path candidate(Path work, String id, String version,
                                  String dependencies, Class<?>... classes) throws Exception {
        var source = Files.createTempDirectory(work, "candidate-");
        var packageRoot = PluginPackageFixtures.executableDirectory(source, id, version,
            dependencies, classes);
        return PluginPackageFixtures.zipDirectory(packageRoot,
            Files.createTempFile(work, id + '-', ".zip"));
    }

    private static PluginInstanceSpec instance(Context root, String entryId, String pluginId) {
        return PluginInstanceSpec.builder(entryId, pluginId)
            .parentContext(root)
            .build();
    }

    private static String installedVersion(Path packageRoot) throws Exception {
        return new PluginPackageInspector().inspectDirectory(packageRoot)
            .descriptor().getVersion();
    }
}
