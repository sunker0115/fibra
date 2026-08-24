package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class FibraEngineStateTest {
    @Test
    void definesTheTerminalEngineLifecycle() {
        assertArrayEquals(new String[] {
            "NEW", "STARTING", "RUNNING", "DEGRADED", "STOPPING", "TERMINATED"
        }, java.util.Arrays.stream(FibraEngineState.values()).map(Enum::name).toArray());
    }
}
