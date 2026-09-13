package com.sstlfsj.fibra.cli;

import org.junit.jupiter.api.Test;

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
        try (var shutdown = new CliProcessShutdown(invocations::stopAdmission,
            cancelAndDrain(invocations), () -> { },
            code -> exited.countDown(),
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
        try (var shutdown = new CliProcessShutdown(invocations::stopAdmission,
            cancelAndDrain(invocations), () -> { },
            code -> exits.incrementAndGet(),
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
        try (var shutdown = new CliProcessShutdown(invocations::stopAdmission,
            cancelAndDrain(invocations), closed::incrementAndGet,
            code -> {
                exitCode.set(code);
                exited.countDown();
            }, Duration.ofSeconds(5), executor)) {
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
        try (var shutdown = new CliProcessShutdown(invocations::stopAdmission,
            cancelAndDrain(invocations), closed::incrementAndGet,
            code -> {
                exitCode.set(code);
                exited.countDown();
            }, Duration.ofSeconds(5), executor)) {
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
        var executor = Executors.newSingleThreadExecutor();
        try (var shutdown = new CliProcessShutdown(invocations::stopAdmission,
            cancelAndDrain(invocations), closed::incrementAndGet,
            code -> gracefulExit.incrementAndGet(),
            code -> {
                exitCode.set(code);
                exited.countDown();
            }, Duration.ofMillis(25), executor)) {
            shutdown.interrupt(CliProcessShutdown.Signal.TERM);

            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertEquals(8, exitCode.get());
            assertEquals(0, gracefulExit.get());
            assertEquals(0, closed.get());
        } finally {
            invocation.close();
            executor.shutdownNow();
        }
    }

    @Test
    void forcedResultIsPublishedOnlyAfterTheForcedExitCallbackReturns() throws Exception {
        var closeEntered = new CountDownLatch(1);
        var releaseClose = new CountDownLatch(1);
        var forcedExitEntered = new CountDownLatch(1);
        var releaseForcedExit = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        var observer = Executors.newSingleThreadExecutor();
        try (var shutdown = new CliProcessShutdown(() -> { }, () -> {
            closeEntered.countDown();
            await(releaseClose);
        }, () -> { }, code -> { }, code -> {
            forcedExitEntered.countDown();
            await(releaseForcedExit);
        }, Duration.ofMillis(25), executor)) {
            var result = observer.submit(shutdown::awaitResult);
            shutdown.interrupt(CliProcessShutdown.Signal.TERM);

            assertTrue(closeEntered.await(5, TimeUnit.SECONDS));
            assertTrue(forcedExitEntered.await(2, TimeUnit.SECONDS));
            assertFalse(result.isDone(),
                "退出结果不得先于 Runtime.halt 调用返回而唤醒主退出路径");
            releaseForcedExit.countDown();
            assertEquals(8, result.get(5, TimeUnit.SECONDS));
        } finally {
            releaseForcedExit.countDown();
            releaseClose.countDown();
            observer.shutdownNow();
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
        try (var shutdown = new CliProcessShutdown(invocations::stopAdmission,
            cancelAndDrain(invocations), () -> { },
            code -> { }, code -> {
                exitCode.set(code);
                exited.countDown();
            }, Duration.ofMillis(25), shutdownExecutor)) {
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
        try (var shutdown = new CliProcessShutdown(invocations::stopAdmission,
            cancelAndDrain(invocations), () -> {
            closeEntered.countDown();
            await(releaseClose);
        }, code -> gracefulExit.incrementAndGet(), code -> {
            exitCode.set(code);
            exited.countDown();
        }, Duration.ofMillis(25), executor)) {
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
        var executor = Executors.newSingleThreadExecutor();
        try (var shutdown = new CliProcessShutdown(invocations::stopAdmission,
            cancelAndDrain(invocations),
            () -> { throw new IllegalStateException("close failed"); },
            code -> {
                exitCode.set(code);
                exited.countDown();
            }, Duration.ofSeconds(5), executor)) {
            shutdown.interrupt(CliProcessShutdown.Signal.INT);

            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertEquals(7, exitCode.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void hostCloseFailureStillReachesTheHardDeadlineWhenGracefulExitBlocks() throws Exception {
        var gracefulExitEntered = new CountDownLatch(1);
        var releaseGracefulExit = new CountDownLatch(1);
        var forcedExitCode = new AtomicReference<Integer>();
        var forcedExit = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var shutdown = new CliProcessShutdown(() -> { }, () -> { },
            () -> { throw new IllegalStateException("close failed"); },
            code -> {
                gracefulExitEntered.countDown();
                await(releaseGracefulExit);
            }, code -> {
                forcedExitCode.set(code);
                forcedExit.countDown();
            }, Duration.ofMillis(25), executor)) {
            shutdown.interrupt(CliProcessShutdown.Signal.TERM);

            assertTrue(gracefulExitEntered.await(5, TimeUnit.SECONDS));
            assertTrue(forcedExit.await(2, TimeUnit.SECONDS),
                "System.exit 被 shutdown hook 阻塞时，硬截止仍须调用 Runtime.halt");
            assertEquals(8, forcedExitCode.get());
        } finally {
            releaseGracefulExit.countDown();
            executor.shutdownNow();
        }
    }

    private static Runnable cancelAndDrain(CliInvocationCoordinator invocations) {
        return () -> invocations.cancelActive().join();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }
}
