package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.EffectHandle;
import com.sstlfsj.fibra.EffectMetadata;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.EventMode;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 固定 Cordis dispose.spec.ts 的清理路径；诊断与同步 generator 边界见 references。 */
class DisposeSpecParityTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final EventKey<Runnable> EVENT = EventKey.of(
        "custom-event", Runnable.class, EventMode.EMIT);
    private final FibraRuntime runtime = FibraRuntime.create();
    private final List<Integer> sequence = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeRuntime() {
        runtime.closeAsync().block(TIMEOUT);
    }

    @Test
    void disposeByPlugin() {
        var calls = new AtomicInteger();
        var handle = new AtomicReference<EffectHandle>();
        var definition = PluginDefinition.builder("test", String.class, () -> (context, config) -> {
            handle.set(context.effects().effect(() -> Disposables.from(calls::incrementAndGet), "test"));
            return Mono.empty();
        }).build();
        var instance = runtime.rootScope().context().plugins().mount("test", definition, "");
        instance.settled().block(TIMEOUT);
        assertEquals(new EffectMetadata("test", List.of()), handle.get().metadata());
        assertEquals(0, calls.get());
        instance.dispose().block(TIMEOUT);
        assertEquals(1, calls.get());
        instance.dispose().block(TIMEOUT);
        assertEquals(1, calls.get());
    }

    @Test
    void disposeManually() {
        var calls = new AtomicInteger();
        var handle = runtime.rootScope().context().effects()
            .effect(() -> Disposables.from(calls::incrementAndGet));
        assertEquals(new EffectMetadata("anonymous", List.of()), handle.metadata());
        assertEquals(0, calls.get());
        handle.dispose().block(TIMEOUT);
        assertEquals(1, calls.get());
        handle.dispose().block(TIMEOUT);
        assertEquals(1, calls.get());
    }

    @Test
    void yieldDispose() {
        var context = runtime.rootScope().context();
        var nestedCalls = new AtomicInteger();
        var independentCalls = new AtomicInteger();
        var outer = context.effects().collect(Flux.defer(() -> Flux.just(
            disposer(1), context.events().on(EVENT, nestedCalls::incrementAndGet), disposer(2),
            context.effects().collect(Flux.defer(() -> Flux.just(
                context.events().on(EVENT, nestedCalls::incrementAndGet), disposer(3)))))));
        context.events().on(EVENT, independentCalls::incrementAndGet);
        outer.ready().block(TIMEOUT);
        var listenerMetadata = new EffectMetadata("events.on(\"custom-event\")", List.of());
        assertEquals(new EffectMetadata("anonymous", List.of(listenerMetadata,
            new EffectMetadata("anonymous", List.of(listenerMetadata)))), outer.metadata());
        assertTrue(sequence.isEmpty());
        context.events().emit(EVENT, Runnable::run);
        assertEquals(2, nestedCalls.get());
        outer.dispose().block(TIMEOUT);
        assertEquals(List.of(3, 2, 1), sequence);
        outer.dispose().block(TIMEOUT);
        assertEquals(List.of(3, 2, 1), sequence);
        context.events().emit(EVENT, Runnable::run);
        assertEquals(2, nestedCalls.get());
        assertEquals(2, independentCalls.get());
    }

    @Test
    void asyncReturn1() {
        var source = TestPublisher.<Disposable>create();
        var handle = runtime.rootScope().context().effects().collect(source.mono());
        assertTrue(sequence.isEmpty());
        produce(source, 1);
        handle.ready().block(TIMEOUT);
        assertEquals(List.of(1), sequence);
        handle.dispose().block(TIMEOUT);
        assertEquals(List.of(1, 2), sequence);
    }

    @Test
    void asyncReturn2() {
        var source = TestPublisher.<Disposable>create();
        var handle = runtime.rootScope().context().effects().collect(source.mono());
        StepVerifier.create(handle.dispose())
            .then(() -> assertTrue(sequence.isEmpty()))
            .then(() -> produce(source, 1))
            .expectComplete().verify(TIMEOUT);
        assertEquals(List.of(1, 2), sequence);
    }

    @Test
    void asyncYield1() {
        var source = TestPublisher.<Disposable>create();
        var handle = runtime.rootScope().context().effects().collect(source.flux());
        assertTrue(sequence.isEmpty());
        produce(source, 1);
        produce(source, 3);
        produce(source, 5);
        source.complete();
        handle.ready().block(TIMEOUT);
        assertEquals(List.of(1, 3, 5), sequence);
        handle.dispose().block(TIMEOUT);
        assertEquals(List.of(1, 3, 5, 6, 4, 2), sequence);
    }

    @Test
    void asyncYield2Aborted() {
        var source = TestPublisher.<Disposable>create();
        var handle = runtime.rootScope().context().effects().collect(source.flux());
        StepVerifier.create(handle.dispose())
            .then(() -> assertTrue(sequence.isEmpty()))
            .then(source::assertNotCancelled)
            .then(() -> produce(source, 1))
            .expectComplete().verify(TIMEOUT);
        source.assertCancelled();
        assertEquals(List.of(1, 2), sequence);
    }

    @Test
    void asyncYield3Aborted() {
        var source = TestPublisher.<Disposable>create();
        var handle = runtime.rootScope().context().effects().collect(source.flux());
        assertTrue(sequence.isEmpty());
        produce(source, 1);
        assertEquals(List.of(1), sequence);
        StepVerifier.create(handle.dispose())
            .then(() -> assertEquals(List.of(1), sequence))
            .then(source::assertNotCancelled)
            .then(() -> produce(source, 3))
            .expectComplete().verify(TIMEOUT);
        source.assertCancelled();
        assertEquals(List.of(1, 3, 4, 2), sequence);
    }

    @Test
    void asyncYield4AwaitDispose() {
        var source = TestPublisher.<Disposable>create();
        var handle = runtime.rootScope().context().effects().collect(source.flux());
        StepVerifier.create(handle.ready())
            .then(() -> produce(source, 1))
            .then(() -> produce(source, 3))
            .then(() -> produce(source, 5))
            .then(source::complete)
            .assertNext(ready -> {
                assertSame(handle, ready);
                assertEquals(List.of(1, 3, 5), sequence);
            })
            .expectComplete().verify(TIMEOUT);
        handle.dispose().block(TIMEOUT);
        assertEquals(List.of(1, 3, 5, 6, 4, 2), sequence);
    }

    @Test
    void returnWithError() {
        var expected = new IllegalStateException("test");
        assertSame(expected, assertThrows(IllegalStateException.class,
            () -> runtime.rootScope().context().effects().effect(() -> { throw expected; })));
        assertTrue(sequence.isEmpty());
    }

    @Test
    void yieldWithError() {
        var expected = new IllegalStateException("test");
        var handle = runtime.rootScope().context().effects().collect(
            Flux.concat(Flux.just(disposer(1)), Flux.error(expected), Flux.just(disposer(2))));
        StepVerifier.create(handle.ready()).expectErrorSatisfies(error -> assertSame(expected, error))
            .verify(TIMEOUT);
        assertEquals(List.of(1), sequence);
    }

    @Test
    void asyncReturnWithError() {
        var source = TestPublisher.<Disposable>create();
        var expected = new IllegalStateException("test");
        var handle = runtime.rootScope().context().effects().collect(source.mono());
        assertTrue(sequence.isEmpty());
        StepVerifier.create(handle.ready())
            .then(() -> source.error(expected))
            .expectErrorSatisfies(error -> assertSame(expected, error)).verify(TIMEOUT);
        assertTrue(sequence.isEmpty());
    }

    @Test
    void asyncYieldWithError() {
        var source = TestPublisher.<Disposable>create();
        var expected = new IllegalStateException("test");
        var handle = runtime.rootScope().context().effects().collect(source.flux());
        assertTrue(sequence.isEmpty());
        StepVerifier.create(handle.ready())
            .then(() -> source.next(disposer(1)))
            .then(() -> source.error(expected))
            .expectErrorSatisfies(error -> assertSame(expected, error)).verify(TIMEOUT);
        assertEquals(List.of(1), sequence);
    }

    private Disposable disposer(int value) {
        return Disposables.from(() -> sequence.add(value));
    }

    private void produce(TestPublisher<Disposable> source, int value) {
        sequence.add(value);
        source.next(disposer(value + 1));
    }
}
