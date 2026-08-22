package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.event.*;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Cordis packages/core/tests/events.spec.ts 的 7 项逐条映射。 */
class EventsSpecParityTest extends CordisSpecSupport {
    private static final EventKey<Signal> EVENT = EventKey.of("test/event", Signal.class);
    private static final EventKey<AsyncSignal> ASYNC = EventKey.of("test/async", AsyncSignal.class);
    private static final EventKey<Waterfall> WATERFALL = EventKey.of("test/waterfall", Waterfall.class);

    @Test
    void ctxOn() {
        var calls = new AtomicInteger();
        Disposable registration = root.on(EVENT, calls::incrementAndGet);
        root.emit(EVENT, Signal::call);
        root.emit(EVENT, Signal::call);
        registration.dispose().block();
        root.emit(EVENT, Signal::call);
        assertEquals(2, calls.get());
    }

    @Test
    void ctxOnce() {
        var calls = new AtomicInteger();
        root.once(EVENT, calls::incrementAndGet);
        root.emit(EVENT, Signal::call);
        root.emit(EVENT, Signal::call);
        assertEquals(1, calls.get());
    }

    @Test
    void ctxParallel() {
        var settled = new AtomicInteger();
        root.on(ASYNC, () -> Mono.fromRunnable(settled::incrementAndGet)
            .then(Mono.error(new IllegalStateException("async"))));
        root.on(ASYNC, () -> Mono.fromRunnable(settled::incrementAndGet)
            .then(Mono.error(new IllegalArgumentException("sync"))));
        var error = assertThrows(AggregateEventException.class,
            () -> root.parallel(ASYNC, AsyncSignal::call).block());
        assertEquals(2, error.causes().size());
        assertEquals(2, settled.get());
    }

    @Test
    void ctxEmit() {
        var calls = new AtomicInteger();
        root.extend(java.util.Map.of("accept", true)).on(EVENT, calls::incrementAndGet);
        EventTarget reject = context -> Boolean.FALSE.equals(context.metadata("accept"));
        EventTarget accept = context -> Boolean.TRUE.equals(context.metadata("accept"));
        root.emit(reject, EVENT, Signal::call);
        root.emit(accept, EVENT, Signal::call);
        assertEquals(1, calls.get());
        root.on(EVENT, () -> { throw new IllegalStateException("test"); });
        assertThrows(IllegalStateException.class, () -> root.emit(EVENT, Signal::call));
    }

    @Test
    void ctxSerial() {
        root.on(ASYNC, () -> Mono.empty());
        root.on(ASYNC, () -> Mono.just("stop"));
        root.on(ASYNC, () -> Mono.error(new AssertionError("must not run")));
        assertEquals("stop", root.serial(ASYNC, AsyncSignal::call).block());
    }

    @Test
    void ctxBail() {
        var calls = new AtomicInteger();
        root.on(EVENT, () -> calls.incrementAndGet());
        root.on(EVENT, () -> calls.addAndGet(10));
        var result = root.bail(EVENT, listener -> {
            listener.call();
            return calls.get() == 1 ? "stop" : null;
        });
        assertEquals("stop", result);
        assertEquals(1, calls.get());
    }

    @Test
    void ctxWaterfall() {
        root.on(WATERFALL, (value, next) -> value + next.call());
        root.on(WATERFALL, (value, next) -> value + next.call());
        assertEquals(4, root.waterfall(WATERFALL,
            (listener, next) -> listener.call(1, next), () -> 2));
        root.on(WATERFALL, (value, next) -> value);
        root.on(WATERFALL, (value, next) -> { throw new AssertionError("vetoed"); });
        assertEquals(3, root.waterfall(WATERFALL,
            (listener, next) -> listener.call(1, next), () -> 2));
    }

    @FunctionalInterface interface Signal { void call(); }
    @FunctionalInterface interface AsyncSignal { Publisher<String> call(); }
    @FunctionalInterface interface Waterfall { int call(int value, Next<Integer> next); }
}
