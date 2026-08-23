package com.sstlfsj.fibra.example.host;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.FibraState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginWatcher;
import com.sstlfsj.fibra.loader.pf4j.PluginInstanceSpec;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraExampleHostIT {
    private static final ServiceKey<String> PROVIDER_VERSION =
        ServiceKey.of("example.provider.version", String.class);
    private static final ServiceKey<String> CONSUMER_RESULT =
        ServiceKey.of("example.consumer.result", String.class);
    private static final String PROVIDER_API =
        "example.fibra.provider.api.Greeting";
    private static final String PROVIDER_ENTRYPOINT =
        "example.fibra.provider.ProviderEntrypoint";
    private static final String CONSUMER_ENTRYPOINT =
        "example.fibra.consumer.ConsumerEntrypoint";

    @Test
    void runsFiniteHostAgainstRealPluginArtifacts(@TempDir Path work) throws Exception {
        var artifacts = Path.of(System.getProperty("fibra.example.artifacts"));
        var plugins = Files.createDirectory(work.resolve("plugins"));
        var installed = Files.copy(artifacts.resolve("fibra-example-provider-v1.jar"),
            plugins.resolve("fibra-example-provider.jar"));
        Files.copy(artifacts.resolve("fibra-example-consumer-v1.jar"),
            plugins.resolve("fibra-example-consumer.jar"));
        var update = Files.copy(artifacts.resolve("fibra-example-provider-v2.jar"),
            work.resolve("fibra-example-provider-v2.jar"));

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
        assertTrue(output.contains("consumer->provider-2.0.0"), output);

        assertEquals("2.0.0", pluginVersion(installed));
    }

    @Test
    void runsRealPluginUpdateAndRollbackWithoutHostClasspathLeak(@TempDir Path work)
        throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(PROVIDER_API));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(PROVIDER_ENTRYPOINT));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(CONSUMER_ENTRYPOINT));

        var artifacts = Path.of(System.getProperty("fibra.example.artifacts"));
        var plugins = Files.createDirectory(work.resolve("plugins"));
        var incoming = Files.createDirectory(work.resolve("incoming"));
        var staging = Files.createDirectory(work.resolve("staging"));
        var installed = plugins.resolve("fibra-example-provider.jar");
        var consumer = plugins.resolve("fibra-example-consumer.jar");
        Files.copy(artifacts.resolve("fibra-example-provider-v1.jar"), installed);
        Files.copy(artifacts.resolve("fibra-example-consumer-v1.jar"), consumer);
        var update = Files.copy(artifacts.resolve("fibra-example-provider-v2.jar"),
            staging.resolve("fibra-example-provider-v2.jar"));
        var broken = Files.copy(artifacts.resolve("fibra-example-provider-broken.jar"),
            staging.resolve("fibra-example-provider-broken.jar"));

        assertEquals("fibra-example-provider", pluginDependencies(consumer));
        assertFalse(jarContains(consumer, "example/fibra/provider/api/Greeting.class"));

        try (Context root = FibraRuntime.create();
             FibraPluginLoader loader = new FibraPluginLoader(root, plugins);
             FibraPluginWatcher watcher = new FibraPluginWatcher(
                 loader, incoming, Duration.ofMillis(100))) {
            loader.loadArtifacts();
            loader.mount(instance(root, "fibra-example-provider"));
            loader.mount(instance(root, "fibra-example-consumer"));
            assertEquals("1.0.0", root.get(PROVIDER_VERSION));
            assertEquals("consumer->provider-1.0.0", root.get(CONSUMER_RESULT));
            assertEquals(FibraState.ACTIVE,
                loader.fibra("fibra-example-provider").orElseThrow().state());
            assertEquals(FibraState.ACTIVE,
                loader.fibra("fibra-example-consumer").orElseThrow().state());
            watcher.start();

            var publishedUpdate = incoming.resolve(update.getFileName());
            publish(update, publishedUpdate);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertEquals("2.0.0", root.get(PROVIDER_VERSION));
                assertEquals("consumer->provider-2.0.0", root.get(CONSUMER_RESULT));
                assertEquals("2.0.0", pluginVersion(installed));
            });
            assertTrue(Files.isRegularFile(publishedUpdate));

            var publishedBroken = incoming.resolve(broken.getFileName());
            publish(broken, publishedBroken);
            await().atMost(Duration.ofSeconds(5))
                .until(() -> watcher.lastFailure().isPresent());
            assertEquals(publishedBroken.toAbsolutePath().normalize(),
                watcher.lastFailure().orElseThrow().candidate());
            assertEquals("2.0.0", root.get(PROVIDER_VERSION));
            assertEquals("consumer->provider-2.0.0", root.get(CONSUMER_RESULT));
            assertEquals("2.0.0", pluginVersion(installed));
            assertEquals("1.0.0", pluginVersion(consumer));
            assertEquals(FibraState.ACTIVE,
                loader.fibra("fibra-example-provider").orElseThrow().state());
            assertEquals(FibraState.ACTIVE,
                loader.fibra("fibra-example-consumer").orElseThrow().state());
            assertTrue(Files.isRegularFile(publishedBroken));
        }
    }

    private static void publish(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static PluginInstanceSpec instance(Context root, String pluginId) {
        return PluginInstanceSpec.builder(pluginId, pluginId)
            .parentContext(root)
            .build();
    }

    private static String pluginVersion(Path plugin) throws IOException {
        try (var jar = new JarFile(plugin.toFile())) {
            return jar.getManifest().getMainAttributes().getValue("Plugin-Version");
        }
    }

    private static String pluginDependencies(Path plugin) throws IOException {
        try (var jar = new JarFile(plugin.toFile())) {
            return jar.getManifest().getMainAttributes().getValue("Plugin-Dependencies");
        }
    }

    private static boolean jarContains(Path plugin, String entry) throws IOException {
        try (var jar = new JarFile(plugin.toFile())) {
            return jar.getEntry(entry) != null;
        }
    }
}
