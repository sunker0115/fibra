package com.sstlfsj.fibra.loader.pf4j;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import example.fibra.plugin.FixtureEntrypoint;
import example.fibra.plugin.ReplacementEntrypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraPluginWatcherTest {
    private static final ServiceKey<String> VALUE = ServiceKey.of("fixture.value", String.class);

    @Test
    void reloadsLatestAtomicallyPublishedCandidateAfterPerPluginDebounce(@TempDir Path work)
        throws Exception {
        PluginLifecycleRecorder.EVENTS.clear();
        var pluginsRoot = Files.createDirectory(work.resolve("plugins"));
        var incoming = Files.createDirectory(work.resolve("incoming"));
        var staging = Files.createDirectory(work.resolve("staging"));
        writePluginJar(pluginsRoot.resolve("fixture.jar"), "fixture", "1.0.0",
            FixtureEntrypoint.class);
        var second = staging.resolve("fixture-2.0.0.jar");
        var third = staging.resolve("fixture-3.0.0.jar");
        writePluginJar(second, "fixture", "2.0.0", ReplacementEntrypoint.class);
        writePluginJar(third, "fixture", "3.0.0", ReplacementEntrypoint.class);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader loader = new FibraPluginLoader(root, pluginsRoot);
             FibraPluginWatcher watcher = new FibraPluginWatcher(
                 loader, incoming, Duration.ofMillis(150))) {
            loader.loadPlugins();
            loader.startPlugin("fixture");
            watcher.start();

            publish(second, incoming.resolve(second.getFileName()));
            publish(third, incoming.resolve(third.getFileName()));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertEquals("3.0.0", pluginVersion(pluginsRoot.resolve("fixture.jar")));
                assertEquals("replacement", root.get(VALUE));
            });
            assertEquals(List.of("replacement:start"), PluginLifecycleRecorder.EVENTS);
            assertTrue(watcher.lastFailure().isEmpty());
        }
    }

    @Test
    void exposesInvalidPublishedCandidateFailure(@TempDir Path work) throws Exception {
        var pluginsRoot = Files.createDirectory(work.resolve("plugins"));
        var incoming = Files.createDirectory(work.resolve("incoming"));
        var staging = Files.createDirectory(work.resolve("staging"));
        writePluginJar(pluginsRoot.resolve("fixture.jar"), "fixture", "1.0.0",
            FixtureEntrypoint.class);
        var invalid = staging.resolve("invalid.jar");
        writePluginJar(invalid, "fixture", "2.0.0", Context.class);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader loader = new FibraPluginLoader(root, pluginsRoot);
             FibraPluginWatcher watcher = new FibraPluginWatcher(
                 loader, incoming, Duration.ofMillis(50))) {
            loader.loadPlugins();
            loader.startPlugin("fixture");
            watcher.start();

            var published = incoming.resolve(invalid.getFileName());
            publish(invalid, published);

            await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertTrue(watcher.lastFailure().isPresent()));
            assertEquals(published.toAbsolutePath().normalize(),
                watcher.lastFailure().orElseThrow().candidate());
            assertEquals("fixture", root.get(VALUE));
        }
    }

    private static void publish(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void writePluginJar(Path path, String id, String version,
                                       Class<?>... classes) throws IOException {
        var manifest = new Manifest();
        var attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Plugin-Id", id);
        attributes.putValue("Plugin-Version", version);

        try (var output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            output.putNextEntry(new JarEntry("META-INF/extensions.idx"));
            for (var type : classes) {
                output.write((type.getName() + '\n').getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            output.closeEntry();
            for (var type : classes) {
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
    }

    private static String pluginVersion(Path path) throws IOException {
        try (var jar = new java.util.jar.JarFile(path.toFile())) {
            return jar.getManifest().getMainAttributes().getValue("Plugin-Version");
        }
    }
}
