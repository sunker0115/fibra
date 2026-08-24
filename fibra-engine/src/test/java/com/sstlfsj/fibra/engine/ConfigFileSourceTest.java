package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigFileSourceTest {
    @Test
    void followsUpdatedPathSetAndDetectsMissingFileCreation(@TempDir Path work)
        throws Exception {
        var root = work.resolve("fibra.yaml");
        var included = work.resolve("late.yaml");
        Files.writeString(root, "[]");
        var paths = new AtomicReference<>(Set.of(root, included));
        var calls = new AtomicInteger();
        try (var source = new ConfigFileSource(paths::get, Duration.ofMillis(20),
            calls::incrementAndGet)) {
            source.start();
            Files.writeString(included, "[]");
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertEquals(1, calls.get()));

            paths.set(Set.of(root));
            Files.writeString(root, "[ ]");
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertEquals(2, calls.get()));
        }
    }
}
