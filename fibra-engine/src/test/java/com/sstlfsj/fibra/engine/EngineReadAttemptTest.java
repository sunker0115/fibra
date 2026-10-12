package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.engine.runtime.RuntimeDefinitionQuery;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EngineReadAttemptTest {
    @Test
    void manySynchronousCloseFailuresStillVisitEverySiblingAndCacheTheTerminalFailure() throws Exception {
        var loop = new EngineCommandLoop();
        try {
            int count = 10_000;
            var next = new AtomicInteger(count - 1);
            var callbacks = new AtomicInteger();
            var failure = new IllegalStateException("safe cleanup failure");
            var owner = new EngineReadAttempt(loop, () -> {
                callbacks.incrementAndGet();
                return failure;
            });
            for (int i = 0; i < count; i++) {
                int index = i;
                owner.register(new RuntimeDefinitionQuery() {
                    public Mono<List<String>> readAsync() { return Mono.just(List.of()); }
                    public Mono<Void> closeAsync() {
                        assertEquals(index, next.getAndDecrement());
                        throw new IllegalStateException("foreign close failure");
                    }
                });
            }
            var terminal = new CompletableFuture<Throwable>();
            var worker = new Thread(null, () -> {
                try { owner.closed.toFuture().join(); terminal.complete(null); }
                catch (Throwable error) { terminal.complete(error); }
            }, "read-owner-stack-probe", 256 * 1024L);
            worker.start();
            assertSame(failure, assertInstanceOf(CompletionException.class,
                terminal.get(10, TimeUnit.SECONDS)).getCause());
            worker.join();
            assertEquals(-1, next.get());
            assertEquals(1, callbacks.get());
            assertSame(failure, assertThrows(CompletionException.class, () -> owner.closed.toFuture().join()).getCause());
            assertEquals(-1, next.get());
            assertEquals(1, callbacks.get());
        } finally { loop.closeAsync().block(Duration.ofSeconds(5)); }
    }
}
