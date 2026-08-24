package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraEngineLifecycleTest {
    @Test
    void ownsStartupRootStatusAndIdempotentTerminalClose(@TempDir Path work)
        throws Exception {
        var installed = Files.createDirectory(work.resolve("plugins"));
        var config = work.resolve("fibra.yaml");
        Files.writeString(config, "[]");
        var engine = FibraEngine.builder(installed, config).build();
        var root = engine.root();

        assertEquals(FibraEngineState.NEW, engine.status().state());
        engine.start();
        assertTrue(engine.isRunning());
        assertEquals(FibraEngineState.RUNNING, engine.status().state());
        assertSame(root, engine.root());

        engine.close();
        engine.close();
        assertFalse(engine.isRunning());
        assertEquals(FibraEngineState.TERMINATED, engine.status().state());
        assertThrows(IllegalStateException.class, engine::start);
    }

    @Test
    void readinessFailureTerminatesAndPreservesStructuredFailure(@TempDir Path work)
        throws Exception {
        var installed = Files.createDirectory(work.resolve("plugins"));
        var config = work.resolve("fibra.yaml");
        Files.writeString(config, "[]");
        var engine = FibraEngine.builder(installed, config)
            .requiredEntries(List.of("missing"))
            .readinessTimeout(Duration.ofMillis(50))
            .build();

        assertThrows(IllegalStateException.class, engine::start);
        assertEquals(FibraEngineState.TERMINATED, engine.status().state());
        assertEquals(FibraEngineFailureStage.READINESS,
            engine.status().failures().getFirst().stage());
    }
}
