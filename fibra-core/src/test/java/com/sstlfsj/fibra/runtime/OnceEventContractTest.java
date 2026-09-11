package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.event.AggregateEventException;
import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.EventMode;
import com.sstlfsj.fibra.event.Next;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class OnceEventContractTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @ParameterizedTest
    @EnumSource(value = EventMode.class, names = {"PARALLEL", "SERIAL"})
    void overlappingDispatchSnapshotsInvokeOnceListenerOnlyOnce(EventMode mode) {
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            var key = EventKey.of("overlapping-once", AsyncSignal.class, mode);
            var calls = new AtomicInteger();
            var release = Sinks.<Object>one();
            events.once(key, () -> {
                calls.incrementAndGet();
                return release.asMono();
            });
            var first = mode == EventMode.PARALLEL
                ? events.parallel(key, AsyncSignal::call)
                : events.serial(key, AsyncSignal::call).then();
            var second = mode == EventMode.PARALLEL
                ? events.parallel(key, AsyncSignal::call)
                : events.serial(key, AsyncSignal::call).then();

            StepVerifier.create(Mono.when(first, second))
                .then(() -> assertEquals(1, calls.get()))
                .then(() -> assertEquals(1, release.currentSubscriberCount()))
                .then(() -> assertEquals(Sinks.EmitResult.OK, release.tryEmitEmpty()))
                .expectComplete().verify(TIMEOUT);
        }
    }

    @ParameterizedTest
    @EnumSource(value = EventMode.class, names = {"PARALLEL", "SERIAL"})
    void resubscriptionDoesNotInvokeOnceListenerAgain(EventMode mode) {
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            var key = EventKey.of("repeat-once", AsyncSignal.class, mode);
            var onceCalls = new AtomicInteger();
            var regularCalls = new AtomicInteger();
            events.once(key, () -> {
                onceCalls.incrementAndGet();
                return Mono.empty();
            });
            events.on(key, () -> {
                regularCalls.incrementAndGet();
                return Mono.empty();
            });
            var dispatch = mode == EventMode.PARALLEL
                ? events.parallel(key, AsyncSignal::call)
                : events.serial(key, AsyncSignal::call).then();

            dispatch.block(TIMEOUT);
            dispatch.block(TIMEOUT);

            assertEquals(1, onceCalls.get());
            assertEquals(2, regularCalls.get());
        }
    }

    @ParameterizedTest
    @EnumSource(value = EventMode.class, names = {"PARALLEL", "SERIAL"})
    void failedOnceInvocationRemainsConsumed(EventMode mode) {
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            var key = EventKey.of("failed-once", AsyncSignal.class, mode);
            var calls = new AtomicInteger();
            var failure = new IllegalStateException("listener failed");
            events.once(key, () -> {
                calls.incrementAndGet();
                throw failure;
            });
            var dispatch = mode == EventMode.PARALLEL
                ? events.parallel(key, AsyncSignal::call)
                : events.serial(key, AsyncSignal::call).then();

            StepVerifier.create(dispatch).expectErrorSatisfies(error -> {
                if (mode == EventMode.PARALLEL) {
                    assertSame(failure,
                        assertInstanceOf(AggregateEventException.class, error).causes().getFirst());
                } else {
                    assertSame(failure, error);
                }
            }).verify(TIMEOUT);
            StepVerifier.create(dispatch).expectComplete().verify(TIMEOUT);
            assertEquals(1, calls.get());
        }
    }

    @Test
    void serialBailDoesNotConsumeUnreachedOnceListener() {
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            var key = EventKey.of("unreached-once", AsyncSignal.class, EventMode.SERIAL);
            var earlyCalls = new AtomicInteger();
            var onceCalls = new AtomicInteger();
            events.on(key, () -> earlyCalls.incrementAndGet() == 1
                ? Mono.just("stop") : Mono.empty());
            events.once(key, () -> Mono.just(onceCalls.incrementAndGet()));
            var dispatch = events.serial(key, AsyncSignal::call);

            assertEquals("stop", dispatch.block(TIMEOUT));
            assertEquals(0, onceCalls.get());
            assertEquals(1, dispatch.block(TIMEOUT));
            assertEquals(1, onceCalls.get());
        }
    }

    @ParameterizedTest
    @EnumSource(value = EventMode.class, names = {"PARALLEL", "SERIAL"})
    void cancellingStartedOnceInvocationDoesNotMakeItInvocableAgain(EventMode mode) {
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            var key = EventKey.of("cancelled-once", AsyncSignal.class, mode);
            var calls = new AtomicInteger();
            var pending = Sinks.<Object>one();
            events.once(key, () -> {
                calls.incrementAndGet();
                return pending.asMono();
            });
            var dispatch = mode == EventMode.PARALLEL
                ? events.parallel(key, AsyncSignal::call)
                : events.serial(key, AsyncSignal::call).then();

            StepVerifier.create(dispatch)
                .then(() -> assertEquals(1, pending.currentSubscriberCount()))
                .thenCancel().verify(TIMEOUT);
            assertEquals(0, pending.currentSubscriberCount());
            StepVerifier.create(dispatch).expectComplete().verify(TIMEOUT);
            assertEquals(1, calls.get());
        }
    }

    @Test
    void cancellingBeforeSerialOnceInvocationLeavesItAvailable() {
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            var key = EventKey.of("not-started-once", AsyncSignal.class, EventMode.SERIAL);
            var earlyCalls = new AtomicInteger();
            var onceCalls = new AtomicInteger();
            var pending = Sinks.<Object>one();
            events.on(key, () -> earlyCalls.incrementAndGet() == 1
                ? pending.asMono() : Mono.empty());
            events.once(key, () -> Mono.just(onceCalls.incrementAndGet()));
            var dispatch = events.serial(key, AsyncSignal::call);

            StepVerifier.create(dispatch)
                .then(() -> assertEquals(1, pending.currentSubscriberCount()))
                .then(() -> assertEquals(0, onceCalls.get()))
                .thenCancel().verify(TIMEOUT);
            assertEquals(0, pending.currentSubscriberCount());
            assertEquals(1, dispatch.block(TIMEOUT));
            assertEquals(1, onceCalls.get());
        }
    }

    @ParameterizedTest
    @EnumSource(value = EventMode.class, names = {"EMIT", "BAIL"})
    void reentrantEarlierListenerCannotInvokeCapturedOnceListenerAgain(EventMode mode) {
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            var key = EventKey.of("reentrant-once", Signal.class, mode);
            var regularCalls = new AtomicInteger();
            var onceCalls = new AtomicInteger();
            events.on(key, () -> {
                if (regularCalls.incrementAndGet() == 1) {
                    if (mode == EventMode.EMIT) {
                        events.emit(key, Signal::call);
                    } else {
                        events.bail(key, Signal::call);
                    }
                }
                return null;
            });
            events.once(key, () -> {
                onceCalls.incrementAndGet();
                return null;
            });

            if (mode == EventMode.EMIT) {
                events.emit(key, Signal::call);
            } else {
                events.bail(key, Signal::call);
            }

            assertEquals(2, regularCalls.get());
            assertEquals(1, onceCalls.get());
        }
    }

    @Test
    void repeatedContinuationSkipsConsumedOnceListenerButStillRunsInner() {
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            var key = EventKey.of("waterfall-once", Wrapper.class, EventMode.WATERFALL);
            var onceCalls = new AtomicInteger();
            var innerCalls = new AtomicInteger();
            events.on(key, next -> next.call() + next.call());
            events.once(key, next -> {
                onceCalls.incrementAndGet();
                return next.call();
            });

            assertEquals(3, events.waterfall(key, Wrapper::call,
                innerCalls::incrementAndGet));
            assertEquals(1, onceCalls.get());
            assertEquals(2, innerCalls.get());
        }
    }

    @FunctionalInterface interface AsyncSignal { Publisher<Object> call(); }
    @FunctionalInterface interface Signal { Object call(); }
    @FunctionalInterface interface Wrapper { int call(Next<Integer> next); }
}
