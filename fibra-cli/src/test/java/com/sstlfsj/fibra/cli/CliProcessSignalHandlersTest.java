package com.sstlfsj.fibra.cli;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliProcessSignalHandlersTest {
    @Test
    void intAndTermAreRegisteredSeparatelyAndPreviousHandlersAreRestored() {
        var registrar = new RecordingRegistrar();
        var received = new java.util.ArrayList<CliProcessShutdown.Signal>();

        try (var ignored = CliProcessSignalHandlers.install(received::add, registrar)) {
            registrar.handlers.get("INT").run();
            registrar.handlers.get("TERM").run();

            assertEquals(java.util.List.of(
                CliProcessShutdown.Signal.INT, CliProcessShutdown.Signal.TERM), received);
            assertEquals(Map.of(), registrar.restored);
        }

        assertEquals(Map.of("TERM", "previous-TERM", "INT", "previous-INT"),
            registrar.restored);
    }

    private static final class RecordingRegistrar implements CliProcessSignalHandlers.Registrar {
        private final Map<String, Runnable> handlers = new LinkedHashMap<>();
        private final Map<String, Object> restored = new LinkedHashMap<>();

        @Override
        public Object register(String name, Runnable handler) {
            handlers.put(name, handler);
            return "previous-" + name;
        }

        @Override
        public void unregister(String name, Object previous) {
            restored.put(name, previous);
        }
    }
}
