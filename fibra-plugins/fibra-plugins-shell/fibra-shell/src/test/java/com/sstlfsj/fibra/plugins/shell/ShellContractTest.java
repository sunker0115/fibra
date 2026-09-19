package com.sstlfsj.fibra.plugins.shell;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellContractTest {
    @Test
    void validatesRequest() {
        var request = ShellRequest.builder().command("printf ok").workdir("/workspace")
            .timeout(Duration.ofSeconds(1)).build();

        assertEquals("printf ok", request.command());
        assertThrows(IllegalArgumentException.class, () -> ShellRequest.builder()
            .command(" ").workdir("/workspace").timeout(Duration.ofSeconds(1)).build());
        assertThrows(IllegalArgumentException.class, () -> ShellRequest.builder()
            .command("printf ok").workdir(" ").timeout(Duration.ofSeconds(1)).build());
        assertThrows(IllegalArgumentException.class, () -> ShellRequest.builder()
            .command("printf ok").workdir("/workspace").timeout(Duration.ZERO).build());
    }

    @Test
    void modelsExitSignalAndTimeoutWithoutTreatingNonZeroAsAnError() {
        var output = ShellOutput.builder().text("failure\n").truncated(false)
            .totalBytes("failure\n".getBytes(StandardCharsets.UTF_8).length).build();
        var exited = ShellResult.builder().exitCode(23).timedOut(false)
            .timeout(Duration.ofSeconds(1)).stdout(output).stderr(output).build();
        var signalledTimeout = ShellResult.builder().signal("TERM").timedOut(true)
            .timeout(Duration.ofMillis(100)).stdout(output).stderr(output).build();
        var signalledAbort = ShellResult.builder().signal("TERM").aborted(true)
            .timeout(Duration.ofSeconds(1)).stdout(output).stderr(output).build();

        assertEquals(23, exited.exitCode());
        assertFalse(exited.timedOut());
        assertEquals("TERM", signalledTimeout.signal());
        assertTrue(signalledTimeout.timedOut());
        assertTrue(signalledAbort.aborted());
        assertThrows(IllegalArgumentException.class, () -> ShellOutput.builder()
            .text("x").truncated(false).totalBytes(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ShellResult.builder().timedOut(false)
            .timeout(Duration.ofSeconds(1)).stdout(output).stderr(output).build());
        assertThrows(IllegalArgumentException.class, () -> ShellResult.builder().signal("TERM")
            .timedOut(true).aborted(true).timeout(Duration.ofSeconds(1))
            .stdout(output).stderr(output).build());
        assertThrows(IllegalArgumentException.class, () -> ShellResult.builder().exitCode(0)
            .signal("TERM").timeout(Duration.ofSeconds(1)).stdout(output).stderr(output).build());
    }

    @Test
    void exposesStableServiceKeyAndStructuredException() {
        var failure = new IllegalStateException("shell start failed");
        var exception = new ShellException(ShellErrorCode.START_FAILED, "cannot start", failure);

        assertEquals("fibra.shell", ShellServices.SHELL.name());
        assertEquals(Shell.class, ShellServices.SHELL.type());
        assertEquals(ShellErrorCode.START_FAILED, exception.code());
        assertEquals(failure, exception.getCause());
    }

    @Test
    void runtimeDescriptorDoesNotDuplicateLogicalPackageMetadata() throws Exception {
        try (var input = ShellContractTest.class.getResourceAsStream("/META-INF/fibra/plugin.yaml")) {
            assertTrue(input != null);
            var manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("{}\n", manifest);
        }
    }
}
