package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.*;
import com.sstlfsj.fibra.runtime.FibraRuntime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectParityTest {
    private final Context context = FibraRuntime.create();

    @AfterEach
    void closeContext() {
        context.closeAsync().block();
    }

    @Test
    void disposesCollectedValuesInReverseOrder() {
        var sequence = new ArrayList<Integer>();
        var handle = context.effectMany(List.of(
            Disposables.from(() -> sequence.add(1)),
            Disposables.from(() -> sequence.add(2)),
            Disposables.from(() -> sequence.add(3))
        ), "lifo");

        handle.ready().block();
        handle.dispose().block();
        handle.dispose().block();

        assertEquals(List.of(3, 2, 1), sequence);
        assertEquals("lifo", handle.metadata().label());
        assertTrue(handle.isDisposed());
    }

    @Test
    void manualDisposePropagatesAndStopsTheLocalChain() {
        var sequence = new ArrayList<Integer>();
        var handle = context.effectMany(List.of(
            Disposables.from(() -> sequence.add(1)),
            () -> Mono.fromRunnable(() -> {
                sequence.add(2);
                throw new IllegalStateException("broken");
            }),
            Disposables.from(() -> sequence.add(3))
        ), "failure");

        StepVerifier.create(handle.dispose())
            .expectErrorMessage("broken")
            .verify();

        assertEquals(List.of(3, 2), sequence);
    }

    @Test
    void disposeWaitsForTheAlreadyRequestedFirstAsyncValue() {
        var sequence = new CopyOnWriteArrayList<Integer>();
        var source = TestPublisher.<Disposable>create();
        var handle = context.effect(source.flux(), "async-yield-2");

        var completion = handle.dispose().toFuture();
        assertFalse(completion.isDone());

        source.next(Disposables.from(() -> sequence.add(2)));
        completion.join();

        assertEquals(List.of(2), sequence);
        source.assertCancelled();
    }

    @Test
    void disposeWaitsForTheAlreadyRequestedNextAsyncValue() {
        var sequence = new CopyOnWriteArrayList<Integer>();
        var source = TestPublisher.<Disposable>create();
        var handle = context.effect(source.flux(), "async-yield-3");

        source.next(Disposables.from(() -> sequence.add(2)));
        var completion = handle.dispose().toFuture();
        assertFalse(completion.isDone());

        source.next(Disposables.from(() -> sequence.add(4)));
        completion.join();

        assertEquals(List.of(4, 2), sequence);
        source.assertCancelled();
    }

    @Test
    void sourceFailureDisposesCollectedValuesBeforeReadyFails() {
        var sequence = new ArrayList<Integer>();
        var source = TestPublisher.<Disposable>create();
        var handle = context.effect(source.flux(), "async-error");

        source.next(Disposables.from(() -> sequence.add(1)));
        source.error(new IllegalArgumentException("source"));

        StepVerifier.create(handle.ready())
            .expectErrorMessage("source")
            .verify();
        assertEquals(List.of(1), sequence);
    }
}
