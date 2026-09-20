package com.sstlfsj.fibra.plugins.subprocess;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubprocessContractTest {
    @Test
    void validatesSpawnSpecAndDefensivelyCopiesArgv() {
        var argv = new java.util.ArrayList<>(List.of("rg", "needle"));
        var spec = SubprocessSpec.builder().argv(argv).cwd("/workspace")
            .stdoutMaxBytes(16).stderrMaxBytes(32).grace(Duration.ofSeconds(1)).build();
        var emptyArguments = SubprocessSpec.builder().argv(List.of("rg", "", " "))
            .cwd("/workspace").stdoutMaxBytes(1).stderrMaxBytes(1)
            .grace(Duration.ofMillis(1)).build();
        argv.clear();

        assertEquals(List.of("rg", "needle"), spec.argv());
        assertEquals(List.of("rg", "", " "), emptyArguments.argv());
        assertThrows(IllegalArgumentException.class, () -> SubprocessSpec.builder()
            .argv(List.of()).cwd("/workspace").stdoutMaxBytes(1).stderrMaxBytes(1)
            .grace(Duration.ofMillis(1)).build());
        assertThrows(IllegalArgumentException.class, () -> SubprocessSpec.builder()
            .argv(List.of("", "argument")).cwd("/workspace").stdoutMaxBytes(1).stderrMaxBytes(1)
            .grace(Duration.ofMillis(1)).build());
        assertThrows(IllegalArgumentException.class, () -> SubprocessSpec.builder()
            .argv(List.of(" ", "argument")).cwd("/workspace").stdoutMaxBytes(1).stderrMaxBytes(1)
            .grace(Duration.ofMillis(1)).build());
        assertThrows(IllegalArgumentException.class, () -> SubprocessSpec.builder()
            .argv(List.of("rg", "bad\0argument")).cwd("/workspace")
            .stdoutMaxBytes(1).stderrMaxBytes(1).grace(Duration.ofMillis(1)).build());
        assertThrows(IllegalArgumentException.class, () -> SubprocessSpec.builder()
            .argv(List.of("rg")).cwd(" ").stdoutMaxBytes(1).stderrMaxBytes(1)
            .grace(Duration.ofMillis(1)).build());
        assertThrows(IllegalArgumentException.class, () -> SubprocessSpec.builder()
            .argv(List.of("rg")).cwd("/bad\0directory").stdoutMaxBytes(1).stderrMaxBytes(1)
            .grace(Duration.ofMillis(1)).build());
        assertThrows(IllegalArgumentException.class, () -> SubprocessSpec.builder()
            .argv(List.of("rg")).cwd("/workspace").stdoutMaxBytes(0).stderrMaxBytes(1)
            .grace(Duration.ofMillis(1)).build());
        assertThrows(IllegalArgumentException.class, () -> SubprocessSpec.builder()
            .argv(List.of("rg")).cwd("/workspace").stdoutMaxBytes(1).stderrMaxBytes(1)
            .grace(Duration.ZERO).build());
    }

    @Test
    void validatesCapturedOutputAndTerminalOutcome() {
        var output = SubprocessOutput.builder().text("ok").truncated(false)
            .totalBytes("ok".getBytes(StandardCharsets.UTF_8).length).build();
        var byExit = SubprocessOutcome.builder().exitCode(7).stdout(output).stderr(output).build();
        var bySignal = SubprocessOutcome.builder().signal("TERM").stdout(output).stderr(output).build();

        assertEquals(7, byExit.exitCode());
        assertEquals("TERM", bySignal.signal());
        assertThrows(IllegalArgumentException.class, () -> SubprocessOutput.builder()
            .text("x").truncated(false).totalBytes(-1).build());
        assertThrows(IllegalArgumentException.class, () -> SubprocessOutcome.builder()
            .stdout(output).stderr(output).build());
        assertThrows(IllegalArgumentException.class, () -> SubprocessOutcome.builder()
            .exitCode(0).signal("TERM").stdout(output).stderr(output).build());
    }

    @Test
    void exposesStableServiceKeyAndStructuredException() {
        var failure = new IllegalStateException("spawn failed");
        var exception = new SubprocessException(SubprocessErrorCode.SPAWN_FAILED,
            "cannot spawn", failure);

        assertEquals("fibra.subprocess", SubprocessServices.SUBPROCESS.name());
        assertEquals(Subprocess.class, SubprocessServices.SUBPROCESS.type());
        assertEquals(SubprocessErrorCode.SPAWN_FAILED, exception.code());
        assertEquals(failure, exception.getCause());
    }

    @Test
    void runtimeDescriptorDoesNotDuplicateLogicalPackageMetadata() throws Exception {
        try (var input = SubprocessContractTest.class.getResourceAsStream(
            "/META-INF/fibra/plugin.yaml")) {
            assertTrue(input != null);
            var manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("{}\n", manifest);
        }
    }
}
