package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.engine.FibraEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraSpringLifecycleTest {
    @Test
    void delegatesStartAndStopAndCompletesTheCallbackOnce(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        var config = Files.writeString(work.resolve("fibra.yaml"), "[]\n");
        var engine = FibraEngine.builder(plugins, config).build();
        var lifecycle = new FibraSpringLifecycle(engine);

        assertFalse(lifecycle.isRunning());
        lifecycle.start();
        assertTrue(lifecycle.isRunning());

        var callbacks = new AtomicInteger();
        lifecycle.stop(callbacks::incrementAndGet);
        assertFalse(lifecycle.isRunning());
        org.junit.jupiter.api.Assertions.assertEquals(1, callbacks.get());
    }
}
