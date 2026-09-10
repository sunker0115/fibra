package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.event.AggregateEventException;
import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.EventMode;
import com.sstlfsj.fibra.event.EventOptions;
import com.sstlfsj.fibra.event.Next;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventParityTest {
    private static final EventKey<MessageListener> MESSAGE = EventKey.of(
        "test/message", MessageListener.class, EventMode.EMIT);
    private static final EventKey<MessageListener> PARALLEL = EventKey.of(
        "test/parallel", MessageListener.class, EventMode.PARALLEL);
    private static final EventKey<MessageListener> SERIAL = EventKey.of(
        "test/serial", MessageListener.class, EventMode.SERIAL);
    private static final EventKey<MessageListener> BAIL = EventKey.of(
        "test/bail", MessageListener.class, EventMode.BAIL);
    private static final EventKey<WaterfallListener> WATERFALL = EventKey.of(
        "test/waterfall", WaterfallListener.class, EventMode.WATERFALL);

    @Test
    void onOnceAndPrependShareOneOrderedHookTable() {
        var sequence = new ArrayList<String>();
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            events.on(MESSAGE, value -> sequence.add("normal:" + value));
            events.once(MESSAGE, value -> {
                sequence.add("once:" + value);
                events.emit(MESSAGE, listener -> listener.onMessage("nested"));
            }, EventOptions.prepend());

            events.emit(MESSAGE, listener -> listener.onMessage("first"));
            events.emit(MESSAGE, listener -> listener.onMessage("second"));
        }

        assertEquals(List.of("once:first", "normal:nested", "normal:first",
            "normal:second"), sequence);
    }

    @Test
    void targetFilterCanRejectLocalHooksButNotGlobalHooks() {
        var local = new AtomicInteger();
        var global = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            events.on(MESSAGE, ignored -> local.incrementAndGet());
            events.on(MESSAGE, ignored -> global.incrementAndGet(), EventOptions.global());

            events.emit(ignored -> false, MESSAGE,
                listener -> listener.onMessage("value"));
        }

        assertEquals(0, local.get());
        assertEquals(1, global.get());
    }

    @Test
    void parallelWaitsForEveryListenerAndAggregatesEveryFailure() {
        var settled = new AtomicBoolean();
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            events.on(PARALLEL, ignored -> { });
            events.on(PARALLEL, ignored -> { });
            var invocation = new AtomicInteger();

            StepVerifier.create(events.parallel(PARALLEL, listener -> {
                listener.onMessage("value");
                if (invocation.getAndIncrement() == 0) {
                    return Mono.error(new IllegalStateException("sync"));
                }
                return Mono.fromRunnable(() -> settled.set(true));
            })).expectErrorSatisfies(error -> assertEquals(List.of("sync"),
                ((AggregateEventException) error).causes().stream()
                    .map(Throwable::getMessage).toList()))
                .verify();
        }

        assertEquals(true, settled.get());
    }

    @Test
    void serialAwaitsInOrderAndStopsOnTheFirstBailValue() {
        var sequence = new ArrayList<Integer>();
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            events.on(SERIAL, ignored -> sequence.add(1));
            events.on(SERIAL, ignored -> sequence.add(2));
            events.on(SERIAL, ignored -> sequence.add(3));

            var result = events.serial(SERIAL, listener -> {
                listener.onMessage("value");
                int current = sequence.getLast();
                return current == 2 ? Mono.just("stop") : Mono.empty();
            }).block();

            assertEquals("stop", result);
        }
        assertEquals(List.of(1, 2), sequence);
    }

    @Test
    void bailTreatsNullAndFalseAsNonBailingValues() {
        var calls = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            events.on(BAIL, ignored -> calls.incrementAndGet());
            events.on(BAIL, ignored -> calls.incrementAndGet());

            var result = events.bail(BAIL, listener -> {
                listener.onMessage("value");
                return calls.get() == 1 ? false : "done";
            });

            assertEquals("done", result);
        }
        assertEquals(2, calls.get());
    }

    @Test
    void waterfallUsesTheSameHooksAndSupportsVeto() {
        var calls = new ArrayList<String>();
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            events.on(WATERFALL, (value, next) -> {
                calls.add("outer");
                return value + next.call();
            });
            events.on(WATERFALL, (value, next) -> {
                calls.add("veto");
                return value;
            });
            events.on(WATERFALL, (value, next) -> {
                calls.add("unreachable");
                return next.call();
            });

            int result = events.waterfall(WATERFALL,
                (listener, next) -> listener.apply(1, next), () -> 2);

            assertEquals(2, result);
        }
        assertEquals(List.of("outer", "veto"), calls);
    }

    @FunctionalInterface
    private interface MessageListener {
        void onMessage(String value);
    }

    @FunctionalInterface
    private interface WaterfallListener {
        int apply(int value, Next<Integer> next);
    }
}
