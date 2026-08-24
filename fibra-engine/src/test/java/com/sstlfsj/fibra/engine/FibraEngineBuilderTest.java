package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;

class FibraEngineBuilderTest {
    @Test
    void rejectsInvalidPathsAndDurationsBeforeBuild(@TempDir Path work) throws Exception {
        var installed = Files.createDirectory(work.resolve("plugins"));
        var config = work.resolve("fibra.yaml");
        Files.writeString(config, "[]");

        assertThrows(IllegalArgumentException.class,
            () -> FibraEngine.builder(work.resolve("missing"), config));
        assertThrows(IllegalArgumentException.class,
            () -> FibraEngine.builder(installed, work.resolve("missing.yaml")));
        assertThrows(IllegalArgumentException.class, () ->
            FibraEngine.builder(installed, config)
                .retryBackoff(Duration.ofSeconds(2), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () ->
            FibraEngine.builder(installed, config).resyncInterval(Duration.ZERO));
    }
}
