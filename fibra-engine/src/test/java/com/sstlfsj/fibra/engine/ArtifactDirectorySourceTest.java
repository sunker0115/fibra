package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ArtifactDirectorySourceTest {
    @Test
    void emitsOnlyForDirectZipPublicationAndStopsAfterClose(@TempDir Path work)
        throws Exception {
        var calls = new AtomicInteger();
        var nested = Files.createDirectory(work.resolve("nested"));
        try (var source = new ArtifactDirectorySource(work, Duration.ofMillis(20),
            calls::incrementAndGet)) {
            source.start();
            Files.writeString(work.resolve("partial.tmp"), "partial");
            Files.writeString(nested.resolve("nested.zip"), "nested");
            Thread.sleep(100);
            assertEquals(0, calls.get());

            Files.writeString(work.resolve("plugin.zip"), "zip");
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertEquals(1, calls.get()));
        }
        Files.writeString(work.resolve("after-close.zip"), "zip");
        Thread.sleep(100);
        assertEquals(1, calls.get());
    }
}
