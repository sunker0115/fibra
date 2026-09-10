package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.event.AggregateEventException;
import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.EventOptions;
import com.sstlfsj.fibra.event.EventMode;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EventDispatchContractTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final EventKey<AsyncSignal> PARALLEL = EventKey.of(
        "parallel", AsyncSignal.class, EventMode.PARALLEL);
    private static final EventKey<AsyncSignal> SERIAL = EventKey.of(
        "serial", AsyncSignal.class, EventMode.SERIAL);
    private static final EventKey<Signal> BAIL = EventKey.of(
        "bail", Signal.class, EventMode.BAIL);
    private static final EventKey<Signal> EMIT = EventKey.of(
        "emit", Signal.class, EventMode.EMIT);

    @Test
    void parallelWaitsForPublisherTerminationAndIncludesErrorsAfterValues() {
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            var completion = Sinks.<Object>one();
            var lateError = new IllegalStateException("after value");
            events.on(PARALLEL, () -> Flux.concat(Flux.just("value"), completion.asMono()));

            StepVerifier.create(events.parallel(PARALLEL, AsyncSignal::call))
                .then(() -> assertEquals(1, completion.currentSubscriberCount(),
                    "收到元素后必须继续等待监听器终止，不能取消其后续流"))
                .then(() -> assertEquals(Sinks.EmitResult.OK, completion.tryEmitError(lateError)))
                .expectErrorSatisfies(error -> assertEquals(List.of(lateError),
                    assertInstanceOf(AggregateEventException.class, error).causes()))
                .verify(TIMEOUT);
        }
    }

    @Test
    void parallelSubscriptionsDoNotShareAccumulatedFailures() {
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            var calls = new AtomicInteger();
            var firstError = new IllegalStateException("first dispatch");
            events.on(PARALLEL, () -> calls.incrementAndGet() == 1
                ? Mono.error(firstError) : Mono.empty());
            var dispatch = events.parallel(PARALLEL, AsyncSignal::call);

            StepVerifier.create(dispatch)
                .expectErrorSatisfies(error -> assertEquals(List.of(firstError),
                    assertInstanceOf(AggregateEventException.class, error).causes()))
                .verify(TIMEOUT);
            StepVerifier.create(dispatch).expectComplete().verify(TIMEOUT);
            assertEquals(2, calls.get());
        }
    }

    @Test
    void serialSkipsEmptyAndFalseButBailsOnZero() {
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            var calls = new ArrayList<Integer>();
            events.on(SERIAL, () -> {
                calls.add(1);
                return Mono.empty();
            });
            events.on(SERIAL, () -> {
                calls.add(2);
                return Mono.just(false);
            });
            events.on(SERIAL, () -> {
                calls.add(3);
                return Mono.just(0);
            });
            events.on(SERIAL, () -> {
                fail("零值已经截断分派");
                return Mono.empty();
            });

            assertEquals(0, events.serial(SERIAL, AsyncSignal::call).block(TIMEOUT));
            assertEquals(List.of(1, 2, 3), calls);
        }
    }

    @Test
    void bailSkipsNullAndFalseButBailsOnEmptyString() {
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            var calls = new ArrayList<Integer>();
            events.on(BAIL, () -> {
                calls.add(1);
                return null;
            });
            events.on(BAIL, () -> {
                calls.add(2);
                return false;
            });
            events.on(BAIL, () -> {
                calls.add(3);
                return "";
            });
            events.on(BAIL, () -> fail("空字符串已经截断分派"));

            assertEquals("", events.bail(BAIL, Signal::call));
            assertEquals(List.of(1, 2, 3), calls);
        }
    }

    @Test
    void globalAndPrependKeepScopeOwnership() {
        try (var runtime = FibraRuntime.create()) {
            var child = runtime.rootScope().openChild("listeners");
            var events = child.context().events();
            var calls = new ArrayList<Integer>();
            events.on(EMIT, () -> calls.add(1));
            events.on(EMIT, () -> calls.add(2), EventOptions.global());
            events.on(EMIT, () -> calls.add(3), EventOptions.prepend());
            events.on(EMIT, () -> calls.add(4), EventOptions.prependGlobal());

            var rootEvents = runtime.rootScope().context().events();
            rootEvents.emit(EMIT, Signal::call);
            assertEquals(List.of(4, 3, 1, 2), calls);
            calls.clear();
            rootEvents.emit(context -> false, EMIT, Signal::call);
            assertEquals(List.of(4, 2), calls);
            child.closeAsync().block(TIMEOUT);
            calls.clear();
            rootEvents.emit(EMIT, Signal::call);
            assertTrue(calls.isEmpty());
        }
    }

    @FunctionalInterface interface Signal { Object call(); }
    @FunctionalInterface interface AsyncSignal { Publisher<Object> call(); }
}
