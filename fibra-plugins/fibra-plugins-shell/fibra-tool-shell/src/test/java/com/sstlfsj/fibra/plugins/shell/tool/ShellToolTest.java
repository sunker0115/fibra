package com.sstlfsj.fibra.plugins.shell.tool;
import com.sstlfsj.fibra.*;
import com.sstlfsj.fibra.bridge.*;
import com.sstlfsj.fibra.plugins.shell.*;
import com.sstlfsj.fibra.plugins.tool.*;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ShellToolTest {
    @Test void publishesBashWithActualInstanceIdAndPropagatesCancellation() {
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var caller = runtime.rootScope().context();
            var cancellation = new CancellationSource();
            caller.services().provide(ContributionServices.REGISTRAR, directory);
            caller.services().provide(ShellServices.SHELL, (invocation, request) -> {
                assertSame(cancellation.token(), invocation.cancellation());
                assertEquals("exit 3", request.command());
                assertEquals("/tmp", request.workdir());
                return Mono.just(result(3, false, false));
            });
            var plugin = caller.plugins().mount("shell-tools-actual",
                new ShellToolEntrypoint().definition().prepare(null));
            plugin.settled().block();
            assertEquals(PluginInstanceState.ACTIVE, plugin.state());
            var value = directory.current().routes().invoke(caller, ToolContributions.KIND,
                ToolContributions.id("shell-tools-actual", "bash"),
                ToolRequest.of(Map.of("command", "exit 3", "workdir", "/tmp", "timeoutMs", 1000), cancellation.token())).block();
            assertTrue(value.data().canonicalJson().contains("\"exitCode\":3"));
            plugin.dispose().block();
            assertTrue(directory.current().snapshot().entries().isEmpty());
        }
    }

    @Test void mapsTimeoutCancellationAndInfrastructureFailures() {
        assertFailure((invocation, request) -> Mono.just(result(143, true, false)), ToolFailureCode.TIMEOUT);
        assertFailure((invocation, request) -> Mono.just(result(143, false, true)), ToolFailureCode.ABORTED);
        assertFailure((invocation, request) -> Mono.error(new ShellException(ShellErrorCode.START_FAILED, "start")), ToolFailureCode.START_FAILED);
        assertFailure((invocation, request) -> Mono.error(new ShellException(ShellErrorCode.TERMINATION_FAILED, "stop")), ToolFailureCode.TERMINATION_FAILED);
    }
    @Test void rendersStdoutAndStderrAsSeparateModelVisibleSections() {
        assertRendered(completed(0, null, "out", false, "err", false),
            "out\n[stderr]\nerr");
        assertRendered(completed(0, null, "", false, "err\n", false),
            "[stderr]\nerr\n");
    }
    @Test void rendersEmptyOutputExactly() {
        assertRendered(completed(0, null, "", false, "", false), "(no output)");
    }
    @Test void rendersNonZeroExitAsAStatusMarker() {
        assertRendered(completed(7, null, "failed", false, "", false),
            "failed\n[exit code: 7]");
    }
    @Test void rendersEachTruncatedStreamWithTheUnavailableSpillFallback() {
        assertRendered(completed(0, null, "out-tail", true, "err-tail", true),
            "out-tail\n[output truncated; full output: (unavailable)]\n"
                + "[stderr]\nerr-tail\n[output truncated; full output: (unavailable)]");
    }
    @Test void rendersSignalTerminationAsAStatusMarker() {
        assertRendered(completed(null, "SIGKILL", "", false, "", false),
            "(no output)\n[killed by signal: SIGKILL]");
    }
    @Test void invalidArgumentsFailBeforeCallingShell() {
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var caller = runtime.rootScope().context();
            caller.services().provide(ContributionServices.REGISTRAR, directory);
            caller.services().provide(ShellServices.SHELL, (invocation, request) -> {
                fail("invalid tool request must not invoke Shell"); return Mono.empty();
            });
            caller.plugins().mount("tools", new ShellToolEntrypoint().definition().prepare(null)).settled().block();
            var failure = assertThrows(ToolException.class, () -> directory.current().routes().invoke(caller,
                ToolContributions.KIND, ToolContributions.id("tools", "bash"),
                ToolRequest.of(Map.of("command", "pwd", "workdir", "/tmp", "timeoutMs", 0))).block());
            assertEquals(ToolFailureCode.INVALID_ARGUMENT, failure.code());
        }
    }
    @Test void rejectsTimeoutBeyondTheSupportedTimerRangeBeforeCallingShell() {
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var caller = runtime.rootScope().context();
            caller.services().provide(ContributionServices.REGISTRAR, directory);
            caller.services().provide(ShellServices.SHELL, (invocation, request) -> {
                fail("oversized timeout must not invoke Shell"); return Mono.empty();
            });
            caller.plugins().mount("tools", new ShellToolEntrypoint().definition().prepare(null)).settled().block();
            var failure = assertThrows(ToolException.class, () -> directory.current().routes().invoke(caller,
                ToolContributions.KIND, ToolContributions.id("tools", "bash"), ToolRequest.of(Map.of(
                    "command", "pwd", "workdir", "/tmp", "timeoutMs", (long) Integer.MAX_VALUE + 1))).block());
            assertEquals(ToolFailureCode.INVALID_ARGUMENT, failure.code());
            assertEquals("timeoutMs must be between 1 and 2147483647", failure.getMessage());
        }
    }
    private void assertFailure(Shell shell, ToolFailureCode code) {
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var caller = runtime.rootScope().context();
            caller.services().provide(ContributionServices.REGISTRAR, directory);
            caller.services().provide(ShellServices.SHELL, shell);
            caller.plugins().mount("tools", new ShellToolEntrypoint().definition().prepare(null)).settled().block();
            var failure = assertThrows(ToolException.class, () -> directory.current().routes().invoke(caller,
                ToolContributions.KIND, ToolContributions.id("tools", "bash"),
                ToolRequest.of(Map.of("command", "pwd", "workdir", "/tmp", "timeoutMs", 1000))).block());
            assertEquals(code, failure.code());
        }
    }
    private void assertRendered(ShellResult shellResult, String expected) {
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var caller = runtime.rootScope().context();
            caller.services().provide(ContributionServices.REGISTRAR, directory);
            caller.services().provide(ShellServices.SHELL,
                (invocation, request) -> Mono.just(shellResult));
            caller.plugins().mount("tools", new ShellToolEntrypoint().definition().prepare(null))
                .settled().block();

            var actual = directory.current().routes().invoke(caller, ToolContributions.KIND,
                ToolContributions.id("tools", "bash"), ToolRequest.of(Map.of(
                    "command", "test", "workdir", "/tmp", "timeoutMs", 1000))).block();

            assertEquals(expected, actual.text());
        }
    }
    private static ShellResult result(int code, boolean timeout, boolean aborted) {
        var output = ShellOutput.builder().text("").totalBytes(0).truncated(false).build();
        return ShellResult.builder().exitCode(code).timedOut(timeout).aborted(aborted)
            .timeout(Duration.ofSeconds(1)).stdout(output).stderr(output).build();
    }
    private static ShellResult completed(Integer exitCode, String signal, String stdout,
                                         boolean stdoutTruncated, String stderr,
                                         boolean stderrTruncated) {
        return ShellResult.builder().exitCode(exitCode).signal(signal).timedOut(false).aborted(false)
            .timeout(Duration.ofSeconds(1))
            .stdout(ShellOutput.builder().text(stdout).truncated(stdoutTruncated)
                .totalBytes(stdout.length()).build())
            .stderr(ShellOutput.builder().text(stderr).truncated(stderrTruncated)
                .totalBytes(stderr.length()).build())
            .build();
    }
}
