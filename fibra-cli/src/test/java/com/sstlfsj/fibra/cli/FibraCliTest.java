package com.sstlfsj.fibra.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraCliTest {
    @Test
    void helpReturnsSuccessWithoutStartingAHost() {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {"--help"}, new ByteArrayInputStream(new byte[0]),
            writer(output), writer(error));

        assertEquals(0, exitCode);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Usage: fibra"));
        assertEquals("", error.toString(StandardCharsets.UTF_8));
    }

    @Test
    void versionReturnsSuccessWithoutStartingAHost() {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {"--version"}, new ByteArrayInputStream(new byte[0]),
            writer(output), writer(error));

        assertEquals(0, exitCode);
        assertTrue(output.toString(StandardCharsets.UTF_8).startsWith("fibra "));
        assertEquals("", error.toString(StandardCharsets.UTF_8));
    }

    @Test
    void unknownCommandReturnsUsageExitCode() {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {"unknown"}, new ByteArrayInputStream(new byte[0]),
            writer(output), writer(error));

        assertEquals(2, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("unknown"));
    }

    private static PrintWriter writer(ByteArrayOutputStream output) {
        return new PrintWriter(output, true, StandardCharsets.UTF_8);
    }
}
