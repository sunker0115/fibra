package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class DesiredSourceMonitorTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void updateAfterCloseIsANoOp(@TempDir Path work) {
        var monitor = new DesiredSourceMonitor(Duration.ofHours(1));
        monitor.start(() -> { });
        monitor.closeAsync().block(TIMEOUT);

        assertDoesNotThrow(() -> monitor.update(Set.of(work.resolve("fibra.yaml"))));
    }
}
