package com.sstlfsj.fibra.loader.pf4j;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import example.fibra.plugin.FixtureEntrypoint;
import example.fibra.plugin.ReplacementEntrypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraPluginWatcherTest {
    private static final ServiceKey<String> VALUE = ServiceKey.of("fixture.value", String.class);

    @Test
    void appliesOnlyTheHighestAtomicallyPublishedZipAfterPerPluginDebounce(@TempDir Path work)
        throws Exception {
        PluginLifecycleRecorder.EVENTS.clear();
        var plugins = Files.createDirectory(work.resolve("plugins"));
        var incoming = Files.createDirectory(work.resolve("incoming"));
        PluginPackageFixtures.executableDirectory(plugins, "fixture", "1.0.0", "",
            FixtureEntrypoint.class);
        var second = candidate(work, "fixture", "2.0.0", ReplacementEntrypoint.class);
        var third = candidate(work, "fixture", "3.0.0", ReplacementEntrypoint.class);

        try (Context root = FibraRuntime.create();
             var loader = new FibraPluginLoader(root, plugins);
             var watcher = new FibraPluginWatcher(loader, incoming, Duration.ofMillis(150))) {
            loader.loadArtifacts();
            loader.mount(PluginInstanceSpec.builder("fixture", "fixture")
                .parentContext(root).build());
            watcher.start();

            publish(second, incoming.resolve("fixture-2.0.0.zip"));
            publish(third, incoming.resolve("fixture-3.0.0.zip"));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertEquals("3.0.0", loader.currentPluginVersion("fixture"));
                assertEquals("replacement", root.get(VALUE));
            });
            assertEquals("3.0.0", version(plugins.resolve("fixture")));
            assertEquals(java.util.List.of("replacement:start"),
                PluginLifecycleRecorder.EVENTS);
            assertTrue(watcher.lastFailure().isEmpty());
        }
    }

    @Test
    void retainsDirtyCandidateAndRetriesAfterTheLoaderTransactionBecomesIdle(
        @TempDir Path work) throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        var incoming = Files.createDirectory(work.resolve("incoming"));
        PluginPackageFixtures.executableDirectory(plugins, "fixture", "1.0.0", "",
            FixtureEntrypoint.class);
        var candidate = candidate(work, "fixture", "2.0.0", ReplacementEntrypoint.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (Context root = FibraRuntime.create();
             var loader = new FibraPluginLoader(root, plugins);
             var watcher = new FibraPluginWatcher(loader, incoming, Duration.ofMillis(40))) {
            loader.loadArtifacts();
            loader.mount(PluginInstanceSpec.builder("fixture", "fixture")
                .parentContext(root).build());
            var holder = Thread.ofPlatform().start(() -> loader.runExclusive(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            watcher.start();
            publish(candidate, incoming.resolve("fixture-2.0.0.zip"));

            await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    assertEquals("1.0.0", version(plugins.resolve("fixture")));
                    assertTrue(watcher.lastFailure().isEmpty());
                });
            release.countDown();
            holder.join();

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals("2.0.0", loader.currentPluginVersion("fixture")));
            assertEquals("2.0.0", version(plugins.resolve("fixture")));
        } finally {
            release.countDown();
        }
    }

    @Test
    void ignoresJarAndNonHigherVersionsButExposesInvalidZipFailure(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        var incoming = Files.createDirectory(work.resolve("incoming"));
        PluginPackageFixtures.executableDirectory(plugins, "fixture", "2.0.0", "",
            FixtureEntrypoint.class);
        var lower = candidate(work, "fixture", "1.0.0", ReplacementEntrypoint.class);
        var same = candidate(work, "fixture", "2.0.0", ReplacementEntrypoint.class);
        var invalid = Files.createTempFile(work, "invalid-", ".zip");
        Files.writeString(invalid, "not-a-zip");

        try (Context root = FibraRuntime.create();
             var loader = new FibraPluginLoader(root, plugins);
             var watcher = new FibraPluginWatcher(loader, incoming, Duration.ofMillis(40))) {
            loader.loadArtifacts();
            watcher.start();
            publish(lower, incoming.resolve("lower.zip"));
            publish(same, incoming.resolve("same.jar"));

            await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    assertEquals("2.0.0", version(plugins.resolve("fixture")));
                    assertTrue(watcher.lastFailure().isEmpty());
                });

            var publishedInvalid = incoming.resolve("invalid.zip");
            publish(invalid, publishedInvalid);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertTrue(watcher.lastFailure().isPresent());
                assertEquals(publishedInvalid.toAbsolutePath().normalize(),
                    watcher.lastFailure().orElseThrow().candidate());
            });
        }
    }

    private static Path candidate(Path work, String id, String version, Class<?> entrypoint)
        throws Exception {
        var source = Files.createTempDirectory(work, "watch-candidate-");
        var packageRoot = PluginPackageFixtures.executableDirectory(source, id, version, "",
            entrypoint);
        return PluginPackageFixtures.zipDirectory(packageRoot,
            Files.createTempFile(work, id + '-', ".zip"));
    }

    private static void publish(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String version(Path packageRoot) {
        return new PluginPackageInspector().inspectDirectory(packageRoot)
            .descriptor().getVersion();
    }
}
