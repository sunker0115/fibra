package com.sstlfsj.fibra.loader.pf4j;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.FibraState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import example.fibra.plugin.ConsumerEntrypoint;
import example.fibra.plugin.FixtureEntrypoint;
import example.fibra.plugin.ProviderEntrypoint;
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
    void loadsStartsStopsAndUnloadsRealPluginJar(@TempDir Path pluginsRoot) throws Exception {
        writePluginJar(pluginsRoot.resolve("fixture.jar"), "fixture", "1.0.0", "",
            FixtureEntrypoint.class);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader loader = new FibraPluginLoader(root, pluginsRoot)) {
            assertEquals(List.of("fixture"), loader.loadPlugins());

            var fibra = loader.startPlugin("fixture");

            assertEquals(FibraState.ACTIVE, fibra.state());
            assertEquals("fixture", root.get(VALUE));
            assertSame(loader.pluginClassLoader("fixture"),
                loader.entrypointClassLoader("fixture"));
            assertNotEquals(Context.class.getClassLoader(),
                loader.entrypointClassLoader("fixture"));
            assertSame(Context.class, loader.pluginClassLoader("fixture")
                .loadClass(Context.class.getName()));

            var classLoader = loader.pluginClassLoader("fixture");
            loader.stopPlugin("fixture");
            assertFalse(loader.fibra("fixture").isPresent());
            assertNull(root.get(VALUE));

            assertTrue(loader.unloadPlugin("fixture"));
            assertTrue(classLoader.isClosed());
            assertEquals(List.of(), loader.pluginIds());
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
            loader.loadPlugins();
            loader.startPlugin("consumer");

            assertEquals(List.of("provider:start", "consumer:start"),
                PluginLifecycleRecorder.EVENTS);

            loader.stopPlugin("provider");

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
            loader.loadPlugins();

            var error = assertThrows(IllegalStateException.class,
                () -> loader.startPlugin("empty"));

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
            loader.loadPlugins();

            var error = assertThrows(IllegalStateException.class,
                () -> loader.startPlugin("multiple"));

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
            var error = assertThrows(IllegalArgumentException.class, loader::loadPlugins);

            assertTrue(error.getMessage().contains("must not bundle shared class"));
            assertEquals(List.of(), loader.pluginIds());
        }
    }

    @Test
    void failedBatchDependencyResolutionLeavesNoPartialPlugins(@TempDir Path pluginsRoot)
        throws Exception {
        writePluginJar(pluginsRoot.resolve("consumer.jar"), "consumer", "1.0.0", "missing",
            ConsumerEntrypoint.class);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader loader = new FibraPluginLoader(root, pluginsRoot)) {
            assertThrows(RuntimeException.class, loader::loadPlugins);

            assertEquals(List.of(), loader.pluginIds());
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
}
