package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.event.AggregateEventException;
import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.EventMode;
import com.sstlfsj.fibra.event.EventTarget;
import com.sstlfsj.fibra.event.Next;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Cordis 8cc9e33 的 events.spec.ts 七项行为；Promise 等待改为订阅 Mono。 */
class EventsSpecParityTest {
    private static final EventKey<Signal> EMIT = EventKey.of(
        "test/emit", Signal.class, EventMode.EMIT);
    private static final EventKey<Signal> BAIL = EventKey.of(
        "test/bail", Signal.class, EventMode.BAIL);
    private static final EventKey<AsyncSignal> PARALLEL = EventKey.of(
        "test/parallel", AsyncSignal.class, EventMode.PARALLEL);
    private static final EventKey<AsyncSignal> SERIAL = EventKey.of(
        "test/serial", AsyncSignal.class, EventMode.SERIAL);
    private static final EventKey<Waterfall> WATERFALL = EventKey.of(
        "test/waterfall", Waterfall.class, EventMode.WATERFALL);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final FibraRuntime runtime = FibraRuntime.create();
    private final Context root = runtime.rootScope().context();
    private final Context filtered = root.withMetadata("accept", true);
    private final EventTarget accept = context -> context == filtered;
    private final EventTarget reject = context -> context != filtered;

    @AfterEach
    void closeRuntime() {
        runtime.close();
    }

    @Test
    void ctxOn() {
        var calls = new AtomicInteger();
        var registration = root.events().on(EMIT, calls::incrementAndGet);
        root.events().emit(EMIT, Signal::call);
        assertEquals(1, calls.get());
        root.events().emit(EMIT, Signal::call);
        assertEquals(2, calls.get());
        registration.dispose().block(TIMEOUT);
        root.events().emit(EMIT, Signal::call);
        assertEquals(2, calls.get());
    }

    @Test
    void ctxOnce() {
        var calls = new AtomicInteger();
        var registration = root.events().once(EMIT, calls::incrementAndGet);
        root.events().emit(EMIT, Signal::call);
        assertEquals(1, calls.get());
        root.events().emit(EMIT, Signal::call);
        assertEquals(1, calls.get());
        registration.dispose().block(TIMEOUT);
        root.events().emit(EMIT, Signal::call);
        assertEquals(1, calls.get());
    }

    @Test
    void ctxParallel() {
        root.events().parallel(PARALLEL, AsyncSignal::call).block(TIMEOUT);
        var calls = new AtomicInteger();
        var failure = new AtomicReference<RuntimeException>();
        filtered.events().on(PARALLEL, () -> {
            calls.incrementAndGet();
            if (failure.get() != null) {
                throw failure.get();
            }
            return Mono.empty();
        });
        root.events().parallel(PARALLEL, AsyncSignal::call).block(TIMEOUT);
        assertEquals(1, calls.get());
        root.events().parallel(reject, PARALLEL, AsyncSignal::call).block(TIMEOUT);
        assertEquals(1, calls.get());
        root.events().parallel(accept, PARALLEL, AsyncSignal::call).block(TIMEOUT);
        assertEquals(2, calls.get());

        var syncError = new IllegalStateException("test");
        var asyncError = new IllegalArgumentException("async");
        var completion = Sinks.<Void>one();
        var settled = new AtomicInteger();
        var registration = root.events().on(PARALLEL, () -> completion.asMono()
            .then(Mono.fromRunnable(settled::incrementAndGet))
            .then(Mono.error(asyncError)));
        failure.set(syncError);
        StepVerifier.create(root.events().parallel(PARALLEL, AsyncSignal::call))
            .then(() -> assertEquals(0, settled.get()))
            .then(() -> assertEquals(Sinks.EmitResult.OK, completion.tryEmitEmpty()))
            .expectErrorSatisfies(error -> {
                var aggregate = assertInstanceOf(AggregateEventException.class, error);
                assertEquals(2, aggregate.causes().size());
                assertTrue(aggregate.causes().containsAll(List.of(syncError, asyncError)));
                assertEquals(1, settled.get());
            })
            .verify(TIMEOUT);
        registration.dispose().block(TIMEOUT);
    }

    @Test
    void ctxEmit() {
        root.events().emit(EMIT, Signal::call);
        var calls = new AtomicInteger();
        var failure = new AtomicReference<RuntimeException>();
        filtered.events().on(EMIT, () -> {
            calls.incrementAndGet();
            if (failure.get() != null) {
                throw failure.get();
            }
            return null;
        });
        root.events().emit(EMIT, Signal::call);
        assertEquals(1, calls.get());
        root.events().emit(reject, EMIT, Signal::call);
        assertEquals(1, calls.get());
        root.events().emit(accept, EMIT, Signal::call);
        assertEquals(2, calls.get());
        var expected = new IllegalStateException("test");
        failure.set(expected);
        assertSame(expected, assertThrows(IllegalStateException.class,
            () -> root.events().emit(EMIT, Signal::call)));
    }

    @Test
    void ctxSerial() {
        assertNull(root.events().serial(SERIAL, AsyncSignal::call).block(TIMEOUT));
        var calls = new AtomicInteger();
        var failure = new AtomicReference<RuntimeException>();
        filtered.events().on(SERIAL, () -> {
            calls.incrementAndGet();
            if (failure.get() != null) {
                throw failure.get();
            }
            return Mono.empty();
        });
        root.events().serial(SERIAL, AsyncSignal::call).block(TIMEOUT);
        assertEquals(1, calls.get());
        root.events().serial(reject, SERIAL, AsyncSignal::call).block(TIMEOUT);
        assertEquals(1, calls.get());
        root.events().serial(accept, SERIAL, AsyncSignal::call).block(TIMEOUT);
        assertEquals(2, calls.get());
        var expected = new IllegalStateException("message");
        failure.set(expected);
        StepVerifier.create(root.events().serial(SERIAL, AsyncSignal::call))
            .expectErrorSatisfies(error -> assertSame(expected, error))
            .verify(TIMEOUT);
    }

    @Test
    void ctxBail() {
        assertNull(root.events().bail(BAIL, Signal::call));
        var calls = new AtomicInteger();
        var failure = new AtomicReference<RuntimeException>();
        filtered.events().on(BAIL, () -> {
            calls.incrementAndGet();
            if (failure.get() != null) {
                throw failure.get();
            }
            return null;
        });
        root.events().bail(BAIL, Signal::call);
        assertEquals(1, calls.get());
        root.events().bail(reject, BAIL, Signal::call);
        assertEquals(1, calls.get());
        root.events().bail(accept, BAIL, Signal::call);
        assertEquals(2, calls.get());
        var expected = new IllegalStateException("message");
        failure.set(expected);
        assertSame(expected, assertThrows(IllegalStateException.class,
            () -> root.events().bail(BAIL, Signal::call)));
    }

    @Test
    void ctxWaterfall() {
        var first = new AtomicInteger();
        var second = new AtomicInteger();
        var third = new AtomicInteger();
        var inner = new AtomicInteger();
        var order = new ArrayList<String>();
        root.events().on(WATERFALL, (value, next) -> {
            order.add("first-enter");
            first.incrementAndGet();
            var result = value + next.call();
            order.add("first-exit");
            return result;
        });
        root.events().on(WATERFALL, (value, next) -> {
            order.add("second-enter");
            second.incrementAndGet();
            var result = value + next.call();
            order.add("second-exit");
            return result;
        });
        assertEquals(4, root.events().waterfall(WATERFALL,
            (listener, next) -> listener.call(1, next), () -> {
                order.add("inner");
                inner.incrementAndGet();
                return 2;
            }));
        assertEquals(1, first.get());
        assertEquals(1, second.get());
        assertEquals(List.of("first-enter", "second-enter", "inner", "second-exit", "first-exit"), order);
        order.clear();
        root.events().on(WATERFALL, (value, next) -> {
            order.add("third-veto");
            third.incrementAndGet();
            return value;
        });
        root.events().on(WATERFALL, (value, next) -> {
            fail("被前一个监听器截断后不应执行");
            return value;
        });
        assertEquals(3, root.events().waterfall(WATERFALL,
            (listener, next) -> listener.call(1, next), () -> {
                inner.incrementAndGet();
                return 2;
            }));
        assertEquals(2, first.get());
        assertEquals(2, second.get());
        assertEquals(1, third.get());
        assertEquals(1, inner.get());
        assertEquals(List.of("first-enter", "second-enter", "third-veto", "second-exit", "first-exit"), order);
    }

    @FunctionalInterface interface Signal { Object call(); }
    @FunctionalInterface interface AsyncSignal { Publisher<Object> call(); }
    @FunctionalInterface interface Waterfall { int call(int value, Next<Integer> next); }
}
