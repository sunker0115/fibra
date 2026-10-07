package com.sstlfsj.fibra.internal;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeCloseCompletionTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void successfulCloseDeliversCompletionAfterDispatcherShutdownRequest() throws Exception {
        var runtime = new DefaultFibraRuntime();
        var disposing = Sinks.<Void>one();
        var release = Sinks.<Void>one();
        var dispatcherDisposedAtCompletion = new AtomicBoolean();
        var runtimeClosedAtCompletion = new AtomicBoolean();
        runtime.rootScope().context().effects().add(() -> release.asMono()
            .doOnSubscribe(ignored -> disposing.tryEmitEmpty()));
        var close = runtime.closeAsync();
        try {
            disposing.asMono().block(TIMEOUT);
            var completion = close.doOnSuccess(ignored -> {
                dispatcherDisposedAtCompletion.set(runtime.lifecycle().scheduler().isDisposed());
                runtimeClosedAtCompletion.set(runtime.isClosed());
            }).toFuture();
            assertEquals(Sinks.EmitResult.OK, release.tryEmitEmpty());
            completion.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(dispatcherDisposedAtCompletion.get(),
                "close completion must follow the owned dispatcher shutdown request");
            assertTrue(runtimeClosedAtCompletion.get());
            runtime.closeAsync().block(TIMEOUT);
        } finally {
            release.tryEmitEmpty();
            runtime.closeAsync().block(TIMEOUT);
        }
    }

    @Test
    void dispatcherShutdownFailureIsSharedByRepeatedCloseReceipts() throws Exception {
        var shutdownFailure = new IllegalStateException("lifecycle dispatcher shutdown failed");
        var constructorThread = Thread.currentThread();
        var decorated = new AtomicInteger();
        var shutdowns = new AtomicInteger();
        var shutdownRequested = new CountDownLatch(1);
        var decoratorKey = "runtime-close-release-" + UUID.randomUUID();
        Schedulers.addExecutorServiceDecorator(decoratorKey, (scheduler, executor) -> {
            var lifecycleConstruction = Thread.currentThread() == constructorThread
                && StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                    frame.getClassName().equals(LifecycleDispatcher.class.getName())
                        && frame.getMethodName().equals("<init>")));
            if (!lifecycleConstruction) return executor;
            decorated.incrementAndGet();
            return new CloseFailureExecutor(executor, () -> {
                shutdowns.incrementAndGet();
                shutdownRequested.countDown();
                throw shutdownFailure;
            });
        });
        try {
            var runtime = new DefaultFibraRuntime();
            var disposing = Sinks.<Void>one();
            var release = Sinks.<Void>one();
            runtime.rootScope().context().effects().add(() -> release.asMono()
                .doOnSubscribe(ignored -> disposing.tryEmitEmpty()));
            try {
                var close = runtime.closeAsync();
                disposing.asMono().block(TIMEOUT);
                var completion = close.materialize().toFuture();
                assertFalse(completion.isDone());
                assertEquals(Sinks.EmitResult.OK, release.tryEmitEmpty());
                var first = completion.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                var second = runtime.closeAsync().materialize().block(TIMEOUT);

                assertTrue(shutdownRequested.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                assertEquals(1, decorated.get());
                assertEquals(1, shutdowns.get());
                assertSame(shutdownFailure, first.getThrowable(),
                    "the owned dispatcher shutdown failure must be part of the close receipt");
                assertSame(first.getThrowable(), second.getThrowable(),
                    "repeated close must replay the same error without another shutdown request");
                assertTrue(runtime.isClosed());
            } finally {
                release.tryEmitEmpty();
                runtime.closeAsync().onErrorComplete().block(TIMEOUT);
            }
        } finally {
            Schedulers.removeExecutorServiceDecorator(decoratorKey);
        }
    }

    private static final class CloseFailureExecutor extends AbstractExecutorService
        implements ScheduledExecutorService {
        private final ScheduledExecutorService delegate;
        private final Runnable afterShutdown;
        CloseFailureExecutor(ScheduledExecutorService delegate, Runnable afterShutdown) {
            this.delegate = delegate;
            this.afterShutdown = afterShutdown;
        }
        public void shutdown() { delegate.shutdown(); }
        public List<Runnable> shutdownNow() {
            var pending = delegate.shutdownNow();
            afterShutdown.run();
            return pending;
        }
        public boolean isShutdown() { return delegate.isShutdown(); }
        public boolean isTerminated() { return delegate.isTerminated(); }
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
        public void execute(Runnable command) { delegate.execute(command); }
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return delegate.schedule(command, delay, unit);
        }
        public <V> ScheduledFuture<V> schedule(Callable<V> command, long delay, TimeUnit unit) {
            return delegate.schedule(command, delay, unit);
        }
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initial, long period, TimeUnit unit) {
            return delegate.scheduleAtFixedRate(command, initial, period, unit);
        }
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initial, long delay, TimeUnit unit) {
            return delegate.scheduleWithFixedDelay(command, initial, delay, unit);
        }
    }
}
