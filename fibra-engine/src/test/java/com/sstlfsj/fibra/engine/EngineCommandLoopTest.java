package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineCommandLoopTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void acceptedCommandCompletesAndCachesItsResultWithoutAResultSubscriber() {
        var loop = new EngineCommandLoop();
        var executions = new AtomicInteger();
        try {
            var result = loop.submit(() -> Mono.fromSupplier(executions::incrementAndGet));
            loop.quiesce().block(TIMEOUT);
            assertEquals(1, executions.get());
            assertEquals(1, result.block(TIMEOUT));
            assertEquals(1, result.block(TIMEOUT));
            assertEquals(1, executions.get());
        } finally {
            close(loop);
        }
    }

    @Test
    void commandsExecuteSeriallyInSubmissionOrder() throws Exception {
        var loop = new EngineCommandLoop();
        var releaseFirst = Sinks.<Integer>one();
        var firstStarted = new CountDownLatch(1);
        var secondStarts = new AtomicInteger();
        try {
            var first = loop.submit(() -> Mono.defer(() -> {
                firstStarted.countDown();
                return releaseFirst.asMono();
            })).toFuture();
            var second = loop.submit(() -> Mono.fromSupplier(() -> {
                secondStarts.incrementAndGet();
                return 2;
            })).toFuture();

            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            assertEquals(0, secondStarts.get());
            releaseFirst.tryEmitValue(1);

            assertEquals(1, first.get(5, TimeUnit.SECONDS));
            assertEquals(2, second.get(5, TimeUnit.SECONDS));
            assertEquals(1, secondStarts.get());
        } finally {
            releaseFirst.tryEmitEmpty();
            close(loop);
        }
    }

    @Test
    void cancellingAResultSubscriptionDoesNotCancelItsAcceptedCommand() throws Exception {
        var loop = new EngineCommandLoop();
        var release = Sinks.<Void>one();
        var started = new CountDownLatch(1);
        try {
            var result = loop.submit(() -> Mono.defer(() -> {
                started.countDown();
                return release.asMono().thenReturn("finished");
            })).subscribe();

            result.dispose();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            release.tryEmitEmpty();
            loop.quiesce().block(TIMEOUT);
        } finally {
            release.tryEmitEmpty();
            close(loop);
        }
    }

    @Test
    void quiesceWaitsForAcceptedQueueAndRejectsLaterCommands() throws Exception {
        var loop = new EngineCommandLoop();
        var releaseFirst = Sinks.<Integer>one();
        var firstStarted = new CountDownLatch(1);
        var secondStarts = new AtomicInteger();
        try {
            var first = loop.submit(() -> Mono.defer(() -> {
                firstStarted.countDown();
                return releaseFirst.asMono();
            })).toFuture();
            var second = loop.submit(() -> Mono.fromSupplier(() -> {
                secondStarts.incrementAndGet();
                return 2;
            })).toFuture();
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

            var quiesced = loop.quiesce().toFuture();
            assertFalse(quiesced.isDone());
            assertThrows(IllegalStateException.class, () -> loop.submit(() -> Mono.just(3)).block(TIMEOUT));

            releaseFirst.tryEmitValue(1);
            assertEquals(1, first.get(5, TimeUnit.SECONDS));
            assertEquals(2, second.get(5, TimeUnit.SECONDS));
            quiesced.get(5, TimeUnit.SECONDS);
            assertEquals(1, secondStarts.get());
        } finally {
            releaseFirst.tryEmitEmpty();
            close(loop);
        }
    }

    @Test
    void resultCallbackCanWaitForCloseWithoutWaitingForItself() throws Exception {
        var loop = new EngineCommandLoop();
        try {
            var result = loop.submit(() -> Mono.just("done"))
                .doOnSuccess(ignored -> loop.closeAsync().block(TIMEOUT)).toFuture();

            assertEquals("done", result.get(5, TimeUnit.SECONDS));
            loop.closeAsync().block(TIMEOUT);
        } finally {
            close(loop);
        }
    }

    @Test
    void commandFailureDoesNotPreventTheNextCommand() throws Exception {
        var loop = new EngineCommandLoop();
        var failure = new IllegalStateException("failed command");
        try {
            var failed = loop.submit(() -> Mono.error(failure)).toFuture();
            var next = loop.submit(() -> Mono.just("next")).toFuture();

            var error = assertThrows(ExecutionException.class,
                () -> failed.get(5, TimeUnit.SECONDS));
            assertSame(failure, error.getCause());
            assertEquals("next", next.get(5, TimeUnit.SECONDS));
        } finally {
            close(loop);
        }
    }

    @Test
    void observationCanRunWhileAnAsyncCommandWaitsWithoutWritingInParallel() throws Exception {
        var loop = new EngineCommandLoop();
        var release = Sinks.<String>one();
        var commandStarted = new CountDownLatch(1);
        var observed = new CountDownLatch(1);
        var commandThread = new AtomicReference<Thread>();
        var observationThread = new AtomicReference<Thread>();
        var writes = new AtomicInteger();
        try {
            var command = loop.submit(() -> Mono.defer(() -> {
                commandThread.set(Thread.currentThread());
                writes.incrementAndGet();
                commandStarted.countDown();
                return release.asMono();
            })).toFuture();
            assertTrue(commandStarted.await(5, TimeUnit.SECONDS));

            loop.observe(() -> {
                observationThread.set(Thread.currentThread());
                writes.incrementAndGet();
                observed.countDown();
            });

            assertTrue(observed.await(5, TimeUnit.SECONDS));
            assertFalse(command.isDone());
            assertSame(commandThread.get(), observationThread.get());
            assertEquals(2, writes.get());

            release.tryEmitValue("done");
            assertEquals("done", command.get(5, TimeUnit.SECONDS));
        } finally {
            release.tryEmitEmpty();
            close(loop);
        }
    }

    private static void close(EngineCommandLoop loop) {
        loop.closeAsync().block(TIMEOUT);
    }
}
