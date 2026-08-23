package com.sstlfsj.fibra.loader.pf4j;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.FibraState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import example.fibra.plugin.ConsumerEntrypoint;
import example.fibra.plugin.ConfigurableEntrypoint;
import example.fibra.plugin.ConfigurableReplacementEntrypoint;
import example.fibra.plugin.FixtureEntrypoint;
import example.fibra.plugin.ProviderEntrypoint;
import example.fibra.plugin.ReplacementEntrypoint;
import example.fibra.plugin.ReplacementProviderEntrypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraPluginLoaderTest {
    private static final ServiceKey<String> VALUE = ServiceKey.of("fixture.value", String.class);

    @Test
    void mountsUpdatesAndUnmountsMultipleEntriesFromOneArtifact(@TempDir Path pluginsRoot)
        throws Exception {
        writePluginJar(pluginsRoot.resolve("fixture.jar"), "fixture", "1.0.0", "",
            ConfigurableEntrypoint.class);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader loader = new FibraPluginLoader(root, pluginsRoot)) {
            assertEquals(List.of("fixture"), loader.loadArtifacts());
            var firstContext = root.isolate(VALUE, "first");
            var secondContext = root.isolate(VALUE, "second");

            var first = loader.mount(PluginInstanceSpec.builder("first", "fixture")
                .parentContext(firstContext)
                .config("one")
                .build());
            var second = loader.mount(PluginInstanceSpec.builder("second", "fixture")
                .parentContext(secondContext)
                .config("two")
                .build());

            assertEquals(String.class, loader.configType("fixture"));
            assertEquals(List.of("first", "second"), loader.entryIds());
            assertEquals("first:one", firstContext.get(VALUE));
            assertEquals("second:two", secondContext.get(VALUE));
            assertNotEquals(first.uid(), second.uid());

            assertSame(first, loader.update("first", "updated"));
            assertEquals("first:updated", firstContext.get(VALUE));
            assertEquals("second:two", secondContext.get(VALUE));

            loader.unmount("first");
            assertNull(firstContext.get(VALUE));
            assertEquals(List.of("second"), loader.entryIds());
            assertTrue(loader.fibra("second").isPresent());
        }
    }

    @Test
    void reloadAndFailedReloadRestoreEveryEntryOfTheAffectedArtifact(@TempDir Path work)
        throws Exception {
        var pluginsRoot = Files.createDirectory(work.resolve("plugins"));
        var incoming = Files.createDirectory(work.resolve("incoming"));
        writePluginJar(pluginsRoot.resolve("fixture.jar"), "fixture", "1.0.0", "",
            ConfigurableEntrypoint.class);
        var replacement = incoming.resolve("fixture-2.0.0.jar");
        writePluginJar(replacement, "fixture", "2.0.0", "",
            ConfigurableReplacementEntrypoint.class);
        var invalid = incoming.resolve("fixture-3.0.0.jar");
        writePluginJar(invalid, "fixture", "3.0.0", "");

        try (Context root = FibraRuntime.create();
             FibraPluginLoader loader = new FibraPluginLoader(root, pluginsRoot)) {
            loader.loadArtifacts();
            var firstContext = root.isolate(VALUE, "first");
            var secondContext = root.isolate(VALUE, "second");
            loader.mount(PluginInstanceSpec.builder("first", "fixture")
                .parentContext(firstContext).config("one").build());
            loader.mount(PluginInstanceSpec.builder("second", "fixture")
                .parentContext(secondContext).config("two").build());
            var firstClassLoader = loader.pluginClassLoader("fixture");

            loader.reloadArtifact(replacement);

            assertTrue(firstClassLoader.isClosed());
            assertEquals(List.of("first", "second"), loader.entryIds());
            assertEquals("v2:first:one", firstContext.get(VALUE));
            assertEquals("v2:second:two", secondContext.get(VALUE));

            var secondClassLoader = loader.pluginClassLoader("fixture");
            assertThrows(IllegalStateException.class, () -> loader.reloadArtifact(invalid));

            assertTrue(secondClassLoader.isClosed());
            assertEquals("2.0.0", pluginVersion(pluginsRoot.resolve("fixture.jar")));
            assertEquals(List.of("first", "second"), loader.entryIds());
            assertEquals("v2:first:one", firstContext.get(VALUE));
            assertEquals("v2:second:two", secondContext.get(VALUE));
        }
    }

    @Test
    void loadsStartsStopsAndUnloadsRealPluginJar(@TempDir Path pluginsRoot) throws Exception {
        writePluginJar(pluginsRoot.resolve("fixture.jar"), "fixture", "1.0.0", "",
            FixtureEntrypoint.class);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader loader = new FibraPluginLoader(root, pluginsRoot)) {
            assertEquals(List.of("fixture"), loader.loadArtifacts());

            var fibra = loader.mount(instance(root, "fixture", "fixture"));

            assertEquals(FibraState.ACTIVE, fibra.state());
            assertEquals("fixture", root.get(VALUE));
            assertSame(loader.pluginClassLoader("fixture"),
                loader.entrypointClassLoader("fixture"));
            assertNotEquals(Context.class.getClassLoader(),
                loader.entrypointClassLoader("fixture"));
            assertSame(Context.class, loader.pluginClassLoader("fixture")
                .loadClass(Context.class.getName()));

            var classLoader = loader.pluginClassLoader("fixture");
            loader.stopArtifact("fixture");
            assertFalse(loader.fibra("fixture").isPresent());
            assertNull(root.get(VALUE));

            assertTrue(loader.unloadArtifact("fixture"));
            assertTrue(classLoader.isClosed());
            assertEquals(List.of(), loader.artifactIds());
        }
    }

    @Test
    void startsDependenciesAndDisposesDependentsBeforeProvider(@TempDir Path pluginsRoot)
        throws Exception {
        PluginLifecycleRecorder.EVENTS.clear();
        writePluginJar(pluginsRoot.resolve("provider.jar"), "provider", "1.0.0", "",
            ProviderEntrypoint.class);
        writePluginJar(pluginsRoot.resolve("consumer.jar"), "consumer", "1.0.0", "provider",
            ConsumerEntrypoint.class);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader loader = new FibraPluginLoader(root, pluginsRoot)) {
            loader.loadArtifacts();
            loader.mount(instance(root, "provider", "provider"));
            loader.mount(instance(root, "consumer", "consumer"));

            assertEquals(List.of("provider:start", "consumer:start"),
                PluginLifecycleRecorder.EVENTS);

            loader.stopArtifact("provider");

            assertEquals(List.of("provider:start", "consumer:start", "consumer:stop", "provider:stop"),
                PluginLifecycleRecorder.EVENTS);
            assertFalse(loader.fibra("provider").isPresent());
            assertFalse(loader.fibra("consumer").isPresent());
        }
    }

    @Test
    void rejectsPluginWithoutExactlyOneFibraEntrypoint(@TempDir Path pluginsRoot)
        throws Exception {
        writePluginJar(pluginsRoot.resolve("empty.jar"), "empty", "1.0.0", "");

        try (Context root = FibraRuntime.create();
             FibraPluginLoader loader = new FibraPluginLoader(root, pluginsRoot)) {
            loader.loadArtifacts();

            var error = assertThrows(IllegalStateException.class,
                () -> loader.mount(instance(root, "empty", "empty")));

            assertTrue(error.getMessage().contains("exactly one FibraPluginEntrypoint"));
            assertFalse(loader.fibra("empty").isPresent());
        }
    }

    @Test
    void rejectsPluginWithMultipleFibraEntrypoints(@TempDir Path pluginsRoot)
        throws Exception {
        writePluginJar(pluginsRoot.resolve("multiple.jar"), "multiple", "1.0.0", "",
            FixtureEntrypoint.class, ProviderEntrypoint.class);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader loader = new FibraPluginLoader(root, pluginsRoot)) {
            loader.loadArtifacts();

            var error = assertThrows(IllegalStateException.class,
                () -> loader.mount(instance(root, "multiple", "multiple")));

            assertTrue(error.getMessage().contains("found 2"));
            assertFalse(loader.fibra("multiple").isPresent());
        }
    }

    @Test
    void rejectsBundledSharedApiClasses(@TempDir Path pluginsRoot) throws Exception {
        writePluginJar(pluginsRoot.resolve("invalid.jar"), "invalid", "1.0.0", "",
            Context.class);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader loader = new FibraPluginLoader(root, pluginsRoot)) {
            var error = assertThrows(IllegalArgumentException.class, loader::loadArtifacts);

            assertTrue(error.getMessage().contains("must not bundle shared class"));
            assertEquals(List.of(), loader.artifactIds());
        }
    }

    @Test
    void failedBatchDependencyResolutionLeavesNoPartialPlugins(@TempDir Path pluginsRoot)
        throws Exception {
        writePluginJar(pluginsRoot.resolve("consumer.jar"), "consumer", "1.0.0", "missing",
            ConsumerEntrypoint.class);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader loader = new FibraPluginLoader(root, pluginsRoot)) {
            assertThrows(RuntimeException.class, loader::loadArtifacts);

            assertEquals(List.of(), loader.artifactIds());
        }
    }

    @Test
    void reloadsStartedPluginFromExternalCandidateAndClosesOldClassLoader(@TempDir Path work)
        throws Exception {
        var pluginsRoot = Files.createDirectory(work.resolve("plugins"));
        var incoming = Files.createDirectory(work.resolve("incoming"));
        writePluginJar(pluginsRoot.resolve("fixture.jar"), "fixture", "1.0.0", "",
            FixtureEntrypoint.class);
        var candidate = incoming.resolve("fixture-2.0.0.jar");
        writePluginJar(candidate, "fixture", "2.0.0", "", ReplacementEntrypoint.class);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader loader = new FibraPluginLoader(root, pluginsRoot)) {
            loader.loadArtifacts();
            loader.mount(instance(root, "fixture", "fixture"));
            var oldClassLoader = loader.pluginClassLoader("fixture");

            assertEquals("fixture", loader.reloadArtifact(candidate));

            assertEquals("replacement", root.get(VALUE));
            assertTrue(oldClassLoader.isClosed());
            assertNotEquals(oldClassLoader, loader.pluginClassLoader("fixture"));
            assertEquals("2.0.0", pluginVersion(pluginsRoot.resolve("fixture.jar")));
            assertTrue(Files.isRegularFile(candidate));
        }
    }

    @Test
    void reloadsDependentsAndRestartsThemInDependencyOrder(@TempDir Path work)
        throws Exception {
        PluginLifecycleRecorder.EVENTS.clear();
        var pluginsRoot = Files.createDirectory(work.resolve("plugins"));
        var incoming = Files.createDirectory(work.resolve("incoming"));
        writePluginJar(pluginsRoot.resolve("provider.jar"), "provider", "1.0.0", "",
            ProviderEntrypoint.class);
        writePluginJar(pluginsRoot.resolve("consumer.jar"), "consumer", "1.0.0", "provider",
            ConsumerEntrypoint.class);
        var candidate = incoming.resolve("provider-2.0.0.jar");
        writePluginJar(candidate, "provider", "2.0.0", "",
            ReplacementProviderEntrypoint.class);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader loader = new FibraPluginLoader(root, pluginsRoot)) {
            loader.loadArtifacts();
            loader.mount(instance(root, "provider", "provider"));
            loader.mount(instance(root, "consumer", "consumer"));
            var oldProviderClassLoader = loader.pluginClassLoader("provider");
            var oldConsumerClassLoader = loader.pluginClassLoader("consumer");

            loader.reloadArtifact(candidate);

            assertEquals(List.of(
                "provider:start", "consumer:start",
                "consumer:stop", "provider:stop",
                "provider-v2:start", "consumer:start"),
                PluginLifecycleRecorder.EVENTS);
            assertTrue(oldProviderClassLoader.isClosed());
            assertTrue(oldConsumerClassLoader.isClosed());
            assertEquals(FibraState.ACTIVE, loader.fibra("provider").orElseThrow().state());
            assertEquals(FibraState.ACTIVE, loader.fibra("consumer").orElseThrow().state());
        }
    }

    @Test
    void restoresOldArtifactAndRuntimeWhenReplacementCannotStart(@TempDir Path work)
        throws Exception {
        var pluginsRoot = Files.createDirectory(work.resolve("plugins"));
        var incoming = Files.createDirectory(work.resolve("incoming"));
        writePluginJar(pluginsRoot.resolve("fixture.jar"), "fixture", "1.0.0", "",
            FixtureEntrypoint.class);
        var candidate = incoming.resolve("invalid-2.0.0.jar");
        writePluginJar(candidate, "fixture", "2.0.0", "");

        try (Context root = FibraRuntime.create();
             FibraPluginLoader loader = new FibraPluginLoader(root, pluginsRoot)) {
            loader.loadArtifacts();
            loader.mount(instance(root, "fixture", "fixture"));
            var oldClassLoader = loader.pluginClassLoader("fixture");

            assertThrows(IllegalStateException.class, () -> loader.reloadArtifact(candidate));

            assertTrue(oldClassLoader.isClosed());
            assertEquals("1.0.0", pluginVersion(pluginsRoot.resolve("fixture.jar")));
            assertEquals("fixture", root.get(VALUE));
            assertEquals(FibraState.ACTIVE, loader.fibra("fixture").orElseThrow().state());
        }
    }

    private static void writePluginJar(Path path, String id, String version,
                                       String dependencies, Class<?>... classes) throws IOException {
        var manifest = new Manifest();
        var attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Plugin-Id", id);
        attributes.putValue("Plugin-Version", version);
        attributes.putValue("Plugin-Dependencies", dependencies);

        try (var output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            if (classes.length > 0) {
                output.putNextEntry(new JarEntry("META-INF/extensions.idx"));
                for (var type : classes) {
                    output.write((type.getName() + '\n').getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                output.closeEntry();
            }
            for (var type : classes) {
                addClass(output, type);
            }
        }
    }

    private static PluginInstanceSpec instance(Context root, String entryId, String pluginId) {
        return PluginInstanceSpec.builder(entryId, pluginId)
            .parentContext(root)
            .build();
    }

    private static void addClass(JarOutputStream output, Class<?> type) throws IOException {
        var resource = type.getName().replace('.', '/') + ".class";
        output.putNextEntry(new JarEntry(resource));
        try (InputStream input = type.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing class resource " + resource);
            }
            input.transferTo(output);
        }
        output.closeEntry();
    }

    private static String pluginVersion(Path path) throws IOException {
        try (var jar = new java.util.jar.JarFile(path.toFile())) {
            return jar.getManifest().getMainAttributes().getValue("Plugin-Version");
        }
    }
}
