package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.*;
import com.sstlfsj.fibra.runtime.FibraRuntime;

import com.sstlfsj.fibra.event.AggregateEventException;
import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.EventOptions;
import com.sstlfsj.fibra.event.EventTarget;
import com.sstlfsj.fibra.event.Next;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventParityTest {
    private static final EventKey<MessageListener> MESSAGE = EventKey.of("test/message", MessageListener.class);
    private static final EventKey<WaterfallListener> WATERFALL = EventKey.of("test/waterfall", WaterfallListener.class);

    private final Context context = FibraRuntime.create();

    @AfterEach
    void closeContext() {
        context.closeAsync().block();
    }

    @Test
    void onOnceAndPrependShareOneOrderedHookTable() {
        var sequence = new ArrayList<String>();
        context.on(MESSAGE, value -> sequence.add("normal:" + value));
        context.once(MESSAGE, value -> {
            sequence.add("once:" + value);
            context.emit(MESSAGE, listener -> listener.onMessage("nested"));
        }, EventOptions.prepend());

        context.emit(MESSAGE, listener -> listener.onMessage("first"));
        context.emit(MESSAGE, listener -> listener.onMessage("second"));

        assertEquals(List.of(
            "once:first",
            "normal:nested",
            "normal:first",
            "normal:second"
        ), sequence);
    }

    @Test
    void targetFilterCanRejectLocalHooksButNotGlobalHooks() {
        var local = new AtomicInteger();
        var global = new AtomicInteger();
        context.on(MESSAGE, ignored -> local.incrementAndGet());
        context.on(MESSAGE, ignored -> global.incrementAndGet(), EventOptions.global());
        EventTarget rejectAll = ignored -> false;

        context.emit(rejectAll, MESSAGE, listener -> listener.onMessage("value"));

        assertEquals(0, local.get());
        assertEquals(1, global.get());
    }

    @Test
    void parallelWaitsForEveryListenerAndAggregatesEveryFailure() {
        var settled = new AtomicBoolean();
        context.on(MESSAGE, ignored -> {
        });
        context.on(MESSAGE, ignored -> {
        });
        var invocation = new AtomicInteger();

        var result = context.parallel(MESSAGE, listener -> {
            listener.onMessage("value");
            if (invocation.getAndIncrement() == 0) {
                return Mono.error(new IllegalStateException("sync"));
            }
            return Mono.fromRunnable(() -> settled.set(true));
        });

        StepVerifier.create(result)
            .expectErrorSatisfies(error -> {
                var aggregate = (AggregateEventException) error;
                assertEquals(List.of("sync"), aggregate.causes().stream()
                    .map(Throwable::getMessage)
                    .toList());
            })
            .verify();
        assertEquals(true, settled.get());
    }

    @Test
    void serialAwaitsInOrderAndStopsOnTheFirstBailValue() {
        var sequence = new ArrayList<Integer>();
        context.on(MESSAGE, ignored -> sequence.add(1));
        context.on(MESSAGE, ignored -> sequence.add(2));
        context.on(MESSAGE, ignored -> sequence.add(3));

        var result = context.serial(MESSAGE, listener -> {
            listener.onMessage("value");
            int current = sequence.get(sequence.size() - 1);
            return current == 2 ? Mono.just("stop") : Mono.empty();
        }).block();

        assertEquals("stop", result);
        assertEquals(List.of(1, 2), sequence);
    }

    @Test
    void bailTreatsNullAndFalseAsNonBailingValues() {
        var calls = new AtomicInteger();
        context.on(MESSAGE, ignored -> calls.incrementAndGet());
        context.on(MESSAGE, ignored -> calls.incrementAndGet());

        var result = context.bail(MESSAGE, listener -> {
            listener.onMessage("value");
            return calls.get() == 1 ? false : "done";
        });

        assertEquals("done", result);
        assertEquals(2, calls.get());
    }

    @Test
    void waterfallUsesTheSameHooksAndSupportsVeto() {
        var calls = new ArrayList<String>();
        context.on(WATERFALL, (value, next) -> {
            calls.add("outer");
            return value + next.call();
        });
        context.on(WATERFALL, (value, next) -> {
            calls.add("veto");
            return value;
        });
        context.on(WATERFALL, (value, next) -> {
            calls.add("unreachable");
            return next.call();
        });

        int result = context.waterfall(WATERFALL,
            (listener, next) -> listener.apply(1, next),
            () -> 2);

        assertEquals(2, result);
        assertEquals(List.of("outer", "veto"), calls);
    }

    @FunctionalInterface
    interface MessageListener {
        void onMessage(String value);
    }

    @FunctionalInterface
    interface WaterfallListener {
        int apply(int value, Next<Integer> next);
    }
}
