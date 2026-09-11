package com.sstlfsj.fibra.plugins.shell.local;

import com.sstlfsj.fibra.CancellationSource;
import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.plugins.shell.ShellRequest;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessServices;
import com.sstlfsj.fibra.plugins.subprocess.local.LocalSubprocess;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalShellTest {
    @TempDir
    Path directory;

    private final LocalShell shell = new LocalShell(new ShellLocalConfig("bash", 1024, 2000));

    private ShellRequest request(String command, long timeout) {
        return ShellRequest.builder().command(command).workdir(directory.toString())
            .timeout(Duration.ofMillis(timeout)).build();
    }
    private InvocationContext invocation(FibraRuntime runtime) {
        var context = runtime.rootScope().context();
        context.services().provide(SubprocessServices.SUBPROCESS, new LocalSubprocess("node"));
        return InvocationContext.of(context, "shell");
    }

    @Test void freshShellSeparatesOutputAndPreservesNonzeroResult() throws Exception {
        try (var runtime = FibraRuntime.create()) {
            var invocation = invocation(runtime);
            var first = shell.run(invocation, request("export FIBRA_CALL_ONLY=first; printf out; printf err >&2; exit 9", 5000)).block();
            assertEquals(9, first.exitCode());
            assertEquals("out", first.stdout().text());
            assertEquals("err", first.stderr().text());
            var second = shell.run(invocation, request("printf \"%s\" \"$FIBRA_CALL_ONLY\"; pwd", 5000)).block();
            assertEquals(directory.toRealPath() + "\n", second.stdout().text());
        }
    }
    @Test void timeoutReturnsOnlyAfterDescendantsStop() throws Exception {
        try (var runtime = FibraRuntime.create()) {
            var result = shell.run(invocation(runtime), request("sleep 60 & echo $! > child; wait", 300)).block(Duration.ofSeconds(5));
            assertTrue(result.timedOut());
            assertFalse(result.aborted());
            assertChildStopped();
        }
    }

    @Test void callerCancellationWinsBeforeTimeoutAndWaitsForTree() throws Exception {
        try (var runtime = FibraRuntime.create()) {
            var source = new CancellationSource();
            var future = shell.run(invocation(runtime).withCancellation(source.token()),
                request("sleep 60 & echo $! > child; wait", 5000)).toFuture();
            long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
            while (!Files.exists(directory.resolve("child")) && System.nanoTime() < deadline) Thread.sleep(10);
            source.cancel();
            var result = future.get(5, TimeUnit.SECONDS);
            assertTrue(result.aborted());
            assertFalse(result.timedOut());
            assertChildStopped();
        }
    }

    private void assertChildStopped() throws Exception {
        long child = Long.parseLong(Files.readString(directory.resolve("child")).trim());
        assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
    }
}
