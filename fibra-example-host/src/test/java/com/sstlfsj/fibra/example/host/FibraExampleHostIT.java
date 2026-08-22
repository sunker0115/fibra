package com.sstlfsj.fibra.example.host;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginWatcher;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraExampleHostIT {
    private static final ServiceKey<String> VERSION =
        ServiceKey.of("example.plugin.version", String.class);
    private static final String ENTRYPOINT =
        "example.fibra.plugin.ExamplePluginEntrypoint";

    @Test
    void runsFiniteHostAgainstRealPluginArtifacts(@TempDir Path work) throws Exception {
        var artifacts = Path.of(System.getProperty("fibra.example.artifacts"));
        var plugins = Files.createDirectory(work.resolve("plugins"));
        var installed = Files.copy(artifacts.resolve("fibra-example-plugin-v1.jar"),
            plugins.resolve("fibra-example-greeting.jar"));
        var update = Files.copy(artifacts.resolve("fibra-example-plugin-v2.jar"),
            work.resolve("fibra-example-plugin-v2.jar"));

        var javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
        var host = Path.of(System.getProperty("fibra.example.host.jar"));
        var process = new ProcessBuilder(javaExecutable.toString(), "-jar", host.toString(),
            plugins.toString(), update.toString())
            .redirectErrorStream(true)
            .start();
        var finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertTrue(finished, "executable example host did not finish");
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);

        assertEquals("2.0.0", pluginVersion(installed));
    }

    @Test
    void runsRealPluginUpdateAndRollbackWithoutHostClasspathLeak(@TempDir Path work)
        throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(ENTRYPOINT));

        var artifacts = Path.of(System.getProperty("fibra.example.artifacts"));
        var plugins = Files.createDirectory(work.resolve("plugins"));
        var incoming = Files.createDirectory(work.resolve("incoming"));
        var staging = Files.createDirectory(work.resolve("staging"));
        var installed = plugins.resolve("fibra-example-greeting.jar");
        Files.copy(artifacts.resolve("fibra-example-plugin-v1.jar"), installed);
        var update = Files.copy(artifacts.resolve("fibra-example-plugin-v2.jar"),
            staging.resolve("fibra-example-plugin-v2.jar"));
        var broken = Files.copy(artifacts.resolve("fibra-example-plugin-broken.jar"),
            staging.resolve("fibra-example-plugin-broken.jar"));

        try (Context root = FibraRuntime.create();
             FibraPluginLoader loader = new FibraPluginLoader(root, plugins);
             FibraPluginWatcher watcher = new FibraPluginWatcher(
                 loader, incoming, Duration.ofMillis(100))) {
            loader.loadPlugins();
            loader.startPlugins();
            assertEquals("1.0.0", root.get(VERSION));
            watcher.start();

            var publishedUpdate = incoming.resolve(update.getFileName());
            publish(update, publishedUpdate);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertEquals("2.0.0", root.get(VERSION));
                assertEquals("2.0.0", pluginVersion(installed));
            });
            assertTrue(Files.isRegularFile(publishedUpdate));

            var publishedBroken = incoming.resolve(broken.getFileName());
            publish(broken, publishedBroken);
            await().atMost(Duration.ofSeconds(5))
                .until(() -> watcher.lastFailure().isPresent());
            assertEquals(publishedBroken.toAbsolutePath().normalize(),
                watcher.lastFailure().orElseThrow().candidate());
            assertEquals("2.0.0", root.get(VERSION));
            assertEquals("2.0.0", pluginVersion(installed));
            assertTrue(Files.isRegularFile(publishedBroken));
        }
    }

    private static void publish(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String pluginVersion(Path plugin) throws IOException {
        try (var jar = new JarFile(plugin.toFile())) {
            return jar.getManifest().getMainAttributes().getValue("Plugin-Version");
        }
    }
}
