package com.sstlfsj.fibra.plugins.shell.local;

import com.sstlfsj.fibra.CancellationSource;
import com.sstlfsj.fibra.CancellationToken;
import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.plugins.shell.ShellErrorCode;
import com.sstlfsj.fibra.plugins.shell.ShellException;
import com.sstlfsj.fibra.plugins.shell.ShellRequest;
import com.sstlfsj.fibra.plugins.shell.ShellResult;
import com.sstlfsj.fibra.plugins.subprocess.ProcessUnit;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessOutcome;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessOutput;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessServices;
import com.sstlfsj.fibra.plugins.subprocess.local.LocalSubprocess;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ShellLifecycleTest {
    @TempDir
    Path directory;

    private final LocalShell shell = new LocalShell(new ShellLocalConfig("bash", 100, 1000));

    private ShellRequest request(long milliseconds) {
        return ShellRequest.builder().command("pwd").workdir("/tmp")
            .timeout(Duration.ofMillis(milliseconds)).build();
    }

    private ShellRequest processRequest(long milliseconds) {
        return ShellRequest.builder()
            .command("printf '%s' $$ > process.pid; sleep 60")
            .workdir(directory.toString())
            .timeout(Duration.ofMillis(milliseconds))
            .build();
    }

    @Test void alreadyCancelledRequestDoesNotStartAProcess() {
        try (var runtime = FibraRuntime.create()) {
            var caller = runtime.rootScope().context();
            caller.services().provide(SubprocessServices.SUBPROCESS, (invocation, spec) -> {
                fail("pre-cancelled call must not spawn"); return Mono.empty();
            });
            var source = new CancellationSource();
            source.cancel();
            var result = shell.run(InvocationContext.of(caller, "shell").withCancellation(source.token()), request(1000)).block();
            assertTrue(result.aborted());
            assertFalse(result.timedOut());
        }
    }
    @Test void oversizedTimeoutFailsBeforeStartingAProcess() {
        try (var runtime = FibraRuntime.create()) {
            var caller = runtime.rootScope().context();
            caller.services().provide(SubprocessServices.SUBPROCESS, (invocation, spec) -> {
                fail("oversized timeout must not spawn"); return Mono.empty();
            });
            var failure = assertThrows(ShellException.class, () -> shell.run(
                InvocationContext.of(caller, "shell"), request((long) Integer.MAX_VALUE + 1)).block());
            assertEquals(ShellErrorCode.START_FAILED, failure.code());
            assertEquals("timeout must not exceed 2147483647 ms", failure.getMessage());
        }
    }

    @Test void timeoutCancelsARealProviderDuringAcquisitionAndWaitsForQuiescence() throws Exception {
        try (var runtime = FibraRuntime.create()) {
            var acquired = new CountDownLatch(1);
            var observed = new AtomicReference<CancellationToken>();
            var result = shell.run(realAcquisitionInvocation(runtime, acquired, observed),
                processRequest(500)).toFuture();

            assertTrue(acquired.await(2, TimeUnit.SECONDS));
            var settled = result.get(5, TimeUnit.SECONDS);
            assertTrue(settled.timedOut());
            assertFalse(settled.aborted());
            assertTrue(observed.get().isCancelled());
            assertProcessStopped(directory.resolve("process.pid"));
        }
    }

    @Test void callerCancellationReachesARealProviderDuringAcquisitionAndWaitsForQuiescence()
            throws Exception {
        try (var runtime = FibraRuntime.create()) {
            var source = new CancellationSource();
            var acquired = new CountDownLatch(1);
            var observed = new AtomicReference<CancellationToken>();
            var invocation = realAcquisitionInvocation(runtime, acquired, observed)
                .withCancellation(source.token());
            var result = shell.run(invocation, processRequest(5000)).toFuture();

            assertTrue(acquired.await(2, TimeUnit.SECONDS));
            assertNotSame(source.token(), observed.get());
            var pidFile = directory.resolve("process.pid");
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(pidFile));
            source.cancel();
            var settled = result.get(5, TimeUnit.SECONDS);
            assertTrue(settled.aborted());
            assertFalse(settled.timedOut());
            assertProcessStopped(pidFile);
        }
    }

    @Test void timeoutRemainsFirstCauseAndResultWaitsForTreeQuiescence() throws Exception {
        try (var runtime = FibraRuntime.create()) {
            var caller = runtime.rootScope().context();
            var unit = new ControlledUnit();
            caller.services().provide(SubprocessServices.SUBPROCESS, (invocation, spec) -> {
                unit.cancelOn(invocation.cancellation());
                return Mono.just(unit);
            });
            var source = new CancellationSource();
            var result = shell.run(InvocationContext.of(caller, "shell").withCancellation(source.token()), request(20)).toFuture();
            assertTrue(unit.terminated.await(2, TimeUnit.SECONDS));
            source.cancel();
            unit.done.tryEmitValue(SubprocessOutcome.builder().signal("SIGTERM")
                .stdout(output()).stderr(output()).build());
            assertFalse(result.isDone(), "direct process exit must not bypass tree drain");
            unit.quiet.tryEmitEmpty();
            assertTrue(result.get(2, TimeUnit.SECONDS).timedOut());
            assertFalse(result.get().aborted());
        }
    }
    private static SubprocessOutput output() {
        return SubprocessOutput.builder().text("").truncated(false).totalBytes(0).build();
    }

    private InvocationContext realAcquisitionInvocation(FibraRuntime runtime, CountDownLatch acquired,
                                                         AtomicReference<CancellationToken> observed) {
        var caller = runtime.rootScope().context();
        var subprocess = new LocalSubprocess("node");
        caller.services().provide(SubprocessServices.SUBPROCESS, (serviceInvocation, spec) -> {
            observed.set(serviceInvocation.cancellation());
            return subprocess.spawn(serviceInvocation, spec).flatMap(processUnit -> {
                acquired.countDown();
                return serviceInvocation.cancellation().cancelled()
                    .then(processUnit.waitForExit())
                    .thenReturn(processUnit);
            });
        });
        return InvocationContext.of(caller, "shell");
    }

    private static void assertProcessStopped(Path pidFile) throws IOException {
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    private static final class ControlledUnit implements ProcessUnit {
        final CountDownLatch terminated = new CountDownLatch(1);
        final Sinks.One<SubprocessOutcome> done = Sinks.one();
        final Sinks.Empty<Void> quiet = Sinks.empty();
        private Disposable cancellationListener;

        void cancelOn(CancellationToken cancellation) {
            cancellationListener = cancellation.cancelled()
                .subscribe(ignored -> { }, ignored -> terminate(), this::terminate);
        }
        @Override
        public Mono<SubprocessOutcome> done() {
            return done.asMono();
        }

        @Override
        public void terminate() {
            terminated.countDown();
        }

        @Override
        public Mono<Void> waitForExit() {
            return quiet.asMono().doFinally(ignored -> cancellationListener.dispose());
        }

        @Override
        public Mono<Void> drain() {
            terminate();
            return waitForExit();
        }

        @Override
        public Mono<Void> dispose() {
            return drain();
        }
    }
}
