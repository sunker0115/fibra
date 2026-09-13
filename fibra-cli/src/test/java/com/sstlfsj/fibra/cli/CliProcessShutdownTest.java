package com.sstlfsj.fibra.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliProcessShutdownTest {
    @Test
    void signalClosesAdmissionBeforeDeferredShutdownWorkRuns() throws Exception {
        var invocations = new CliInvocationCoordinator();
        var queued = new AtomicReference<Runnable>();
        var exited = new CountDownLatch(1);
        try (var shutdown = new CliProcessShutdown(invocations, () -> { },
            code -> exited.countDown(), writer(new ByteArrayOutputStream()),
            Duration.ofSeconds(5), queued::set)) {
            shutdown.interrupt(CliProcessShutdown.Signal.INT);

            assertThrows(IllegalStateException.class, invocations::begin);
            assertFalse(exited.await(25, TimeUnit.MILLISECONDS));
            assertTrue(queued.get() != null);
            queued.get().run();
            assertTrue(exited.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void normalCompletionAtomicallyClosesLaterSignalAdmission() throws Exception {
        var invocations = new CliInvocationCoordinator();
        var exits = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        try (var shutdown = new CliProcessShutdown(invocations, () -> { },
            code -> exits.incrementAndGet(), writer(new ByteArrayOutputStream()),
            Duration.ofMillis(25), executor)) {
            assertTrue(shutdown.tryCompleteNormally());

            shutdown.interrupt(CliProcessShutdown.Signal.INT);

            assertFalse(shutdown.requested());
            try (var invocation = invocations.begin()) {
                assertFalse(invocation.token().isCancelled());
            }
            assertEquals(0, exits.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void termClosesTheOwnedHostAndProjectsSuccess() throws Exception {
        var invocations = new CliInvocationCoordinator();
        var closed = new AtomicInteger();
        var exitCode = new AtomicReference<Integer>();
        var exited = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var shutdown = new CliProcessShutdown(invocations, closed::incrementAndGet,
            code -> {
                exitCode.set(code);
                exited.countDown();
            }, writer(new ByteArrayOutputStream()), Duration.ofSeconds(5), executor)) {
            shutdown.interrupt(CliProcessShutdown.Signal.TERM);

            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertEquals(1, closed.get());
            assertEquals(0, exitCode.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void repeatedSignalsShareCancellationDrainCloseAndExit() throws Exception {
        var invocations = new CliInvocationCoordinator();
        var invocation = invocations.begin();
        var cancelled = new CountDownLatch(1);
        invocation.token().cancelled().doOnSuccess(ignored -> cancelled.countDown()).subscribe();
        var closed = new AtomicInteger();
        var exitCode = new AtomicReference<Integer>();
        var exited = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var shutdown = new CliProcessShutdown(invocations, closed::incrementAndGet,
            code -> {
                exitCode.set(code);
                exited.countDown();
            }, writer(new ByteArrayOutputStream()), Duration.ofSeconds(5), executor)) {
            shutdown.interrupt(CliProcessShutdown.Signal.INT);
            shutdown.interrupt(CliProcessShutdown.Signal.TERM);

            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            assertTrue(invocation.token().isCancelled());
            assertFalse(shutdown.tryCompleteNormally());
            assertFalse(exited.await(100, TimeUnit.MILLISECONDS));
            invocation.close();
            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertEquals(1, closed.get());
            assertEquals(130, exitCode.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void drainTimeoutHasItsOwnExitProjectionAndDoesNotClaimHostClosure() throws Exception {
        var invocations = new CliInvocationCoordinator();
        var invocation = invocations.begin();
        var closed = new AtomicInteger();
        var gracefulExit = new AtomicInteger();
        var exitCode = new AtomicReference<Integer>();
        var exited = new CountDownLatch(1);
        var diagnostics = new ByteArrayOutputStream();
        var executor = Executors.newSingleThreadExecutor();
        try (var shutdown = new CliProcessShutdown(invocations, closed::incrementAndGet,
            code -> gracefulExit.incrementAndGet(),
            code -> {
                exitCode.set(code);
                exited.countDown();
            }, writer(diagnostics), Duration.ofMillis(25), executor)) {
            shutdown.interrupt(CliProcessShutdown.Signal.TERM);

            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertEquals(8, exitCode.get());
            assertEquals(0, gracefulExit.get());
            assertEquals(0, closed.get());
            assertTrue(diagnostics.toString(StandardCharsets.UTF_8).contains("排空超时"));
        } finally {
            invocation.close();
            executor.shutdownNow();
        }
    }

    @Test
    void deadlineStartsBeforeSynchronousCancellationDelivery() throws Exception {
        var invocations = new CliInvocationCoordinator();
        var invocation = invocations.begin();
        var cancelEntered = new CountDownLatch(1);
        var releaseCancel = new CountDownLatch(1);
        invocation.token().cancelled().doOnSuccess(ignored -> {
            cancelEntered.countDown();
            await(releaseCancel);
        }).subscribe();
        var exitCode = new AtomicReference<Integer>();
        var exited = new CountDownLatch(1);
        var shutdownExecutor = Executors.newSingleThreadExecutor();
        var signalExecutor = Executors.newSingleThreadExecutor();
        try (var shutdown = new CliProcessShutdown(invocations, () -> { },
            code -> { }, code -> {
                exitCode.set(code);
                exited.countDown();
            }, writer(new ByteArrayOutputStream()), Duration.ofMillis(25), shutdownExecutor)) {
            signalExecutor.submit(() -> shutdown.interrupt(CliProcessShutdown.Signal.INT));

            assertTrue(cancelEntered.await(5, TimeUnit.SECONDS));
            assertTrue(exited.await(2, TimeUnit.SECONDS));
            assertEquals(8, exitCode.get());
        } finally {
            releaseCancel.countDown();
            invocation.close();
            signalExecutor.shutdownNow();
            shutdownExecutor.shutdownNow();
        }
    }

    @Test
    void deadlineAlsoCoversOwnedHostClose() throws Exception {
        var invocations = new CliInvocationCoordinator();
        var closeEntered = new CountDownLatch(1);
        var releaseClose = new CountDownLatch(1);
        var gracefulExit = new AtomicInteger();
        var exitCode = new AtomicReference<Integer>();
        var exited = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var shutdown = new CliProcessShutdown(invocations, () -> {
            closeEntered.countDown();
            await(releaseClose);
        }, code -> gracefulExit.incrementAndGet(), code -> {
            exitCode.set(code);
            exited.countDown();
        }, writer(new ByteArrayOutputStream()), Duration.ofMillis(25), executor)) {
            shutdown.interrupt(CliProcessShutdown.Signal.TERM);

            assertTrue(closeEntered.await(5, TimeUnit.SECONDS));
            assertTrue(exited.await(2, TimeUnit.SECONDS));
            assertEquals(8, exitCode.get());
            assertEquals(0, gracefulExit.get());
        } finally {
            releaseClose.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void hostCloseFailureIsDistinctFromCancellationAndDrainTimeout() throws Exception {
        var invocations = new CliInvocationCoordinator();
        var exitCode = new AtomicReference<Integer>();
        var exited = new CountDownLatch(1);
        var diagnostics = new ByteArrayOutputStream();
        var executor = Executors.newSingleThreadExecutor();
        try (var shutdown = new CliProcessShutdown(invocations,
            () -> { throw new IllegalStateException("close failed"); },
            code -> {
                exitCode.set(code);
                exited.countDown();
            }, writer(diagnostics), Duration.ofSeconds(5), executor)) {
            shutdown.interrupt(CliProcessShutdown.Signal.INT);

            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertEquals(7, exitCode.get());
            assertTrue(diagnostics.toString(StandardCharsets.UTF_8).contains("关闭宿主失败"));
        } finally {
            executor.shutdownNow();
        }
    }

    private static PrintWriter writer(ByteArrayOutputStream output) {
        return new PrintWriter(output, true, StandardCharsets.UTF_8);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }
}
