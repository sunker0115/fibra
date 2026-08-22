package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.*;
import com.sstlfsj.fibra.runtime.FibraRuntime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraDisposalParityTest {
    private final Context context = FibraRuntime.create();

    @AfterEach
    void closeContext() {
        context.closeAsync().block();
    }

    @Test
    void startsTopLevelEffectsConcurrentlyAndWaitsForAll() {
        var firstStarted = new AtomicBoolean();
        var secondStarted = new AtomicBoolean();
        var firstGate = Sinks.<Void>one();
        var secondGate = Sinks.<Void>one();

        context.effect(() -> () -> Mono.defer(() -> {
            firstStarted.set(true);
            return firstGate.asMono();
        }), "first");
        context.effect(() -> () -> Mono.defer(() -> {
            secondStarted.set(true);
            return secondGate.asMono();
        }), "second");

        var restart = context.fibra().restart().toFuture();
        await().untilTrue(firstStarted);
        await().untilTrue(secondStarted);
        assertTrue(!restart.isDone());

        firstGate.tryEmitEmpty();
        secondGate.tryEmitEmpty();
        restart.join();
    }

    @Test
    void unloadLogsAndIsolatesEachTopLevelFailure() {
        var siblingDisposed = new AtomicBoolean();
        context.effect(() -> () -> Mono.error(new IllegalStateException("broken")), "broken");
        context.effect(() -> Disposables.from(() -> siblingDisposed.set(true)), "sibling");

        context.fibra().restart().block();

        assertTrue(siblingDisposed.get());
    }
}
