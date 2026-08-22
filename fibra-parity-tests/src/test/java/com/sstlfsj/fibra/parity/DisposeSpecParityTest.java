package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.publisher.TestPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Cordis packages/core/tests/dispose.spec.ts 的 13 项逐条映射。 */
class DisposeSpecParityTest extends CordisSpecSupport {
    @Test
    void disposeByPlugin() {
        var calls = new AtomicInteger();
        var fibra = root.plugin("test", (context, config) -> {
            context.effect(() -> Disposables.from(calls::incrementAndGet), "test");
            return Mono.empty();
        }, null);
        await(fibra);
        assertEquals(List.of(new EffectMetadata("test", List.of())), fibra.effects());
        fibra.dispose().block(); fibra.dispose().block();
        assertEquals(1, calls.get());
    }

    @Test
    void disposeManually() {
        var calls = new AtomicInteger();
        var handle = root.effect(() -> Disposables.from(calls::incrementAndGet));
        handle.dispose().block(); handle.dispose().block();
        assertEquals(1, calls.get());
    }

    @Test
    void yieldDispose() {
        var sequence = new ArrayList<Integer>();
        var outer = root.effectSync(sink -> {
            sink.add(Disposables.from(() -> sequence.add(1)));
            sink.add(Disposables.from(() -> sequence.add(2)));
            sink.add(root.effect(() -> Disposables.from(() -> sequence.add(3))));
        });
        assertEquals(1, outer.metadata().children().size());
        outer.dispose().block(); outer.dispose().block();
        assertEquals(List.of(3, 2, 1), sequence);
    }

    @Test
    void asyncReturn1() {
        var sequence = new CopyOnWriteArrayList<Integer>();
        var source = TestPublisher.<Disposable>create();
        var handle = root.effect(source.flux());
        source.next(Disposables.from(() -> sequence.add(2))).complete();
        handle.ready().block(); handle.dispose().block();
        assertEquals(List.of(2), sequence);
    }

    @Test
    void asyncReturn2() {
        var sequence = new CopyOnWriteArrayList<Integer>();
        var source = TestPublisher.<Disposable>create();
        var handle = root.effect(source.flux());
        var disposal = handle.dispose().toFuture();
        source.next(Disposables.from(() -> sequence.add(2)));
        disposal.join();
        assertEquals(List.of(2), sequence);
    }

    @Test
    void asyncYield1() {
        var sequence = new CopyOnWriteArrayList<Integer>();
        var source = TestPublisher.<Disposable>create();
        var handle = root.effect(source.flux());
        source.next(disposer(sequence, 2), disposer(sequence, 4), disposer(sequence, 6)).complete();
        handle.ready().block(); handle.dispose().block();
        assertEquals(List.of(6, 4, 2), sequence);
    }

    @Test
    void asyncYield2Aborted() {
        var sequence = new CopyOnWriteArrayList<Integer>();
        var source = TestPublisher.<Disposable>create();
        var handle = root.effect(source.flux());
        var disposal = handle.dispose().toFuture();
        source.next(disposer(sequence, 2));
        disposal.join();
        assertEquals(List.of(2), sequence);
        source.assertCancelled();
    }

    @Test
    void asyncYield3Aborted() {
        var sequence = new CopyOnWriteArrayList<Integer>();
        var source = TestPublisher.<Disposable>create();
        var handle = root.effect(source.flux());
        source.next(disposer(sequence, 2));
        var disposal = handle.dispose().toFuture();
        source.next(disposer(sequence, 4));
        disposal.join();
        assertEquals(List.of(4, 2), sequence);
    }

    @Test
    void asyncYield4AwaitDispose() {
        var source = TestPublisher.<Disposable>create();
        var handle = root.effect(source.flux());
        var ready = handle.ready().toFuture();
        assertFalse(ready.isDone());
        source.complete();
        assertSame(handle, ready.join());
        handle.dispose().block();
    }

    @Test
    void returnWithError() {
        var error = assertThrows(IllegalStateException.class,
            () -> root.effect(() -> { throw new IllegalStateException("test"); }));
        assertEquals("test", error.getMessage());
    }

    @Test
    void yieldWithError() {
        var sequence = new ArrayList<Integer>();
        assertThrows(IllegalStateException.class, () -> root.effectSync(sink -> {
            sink.add(Disposables.from(() -> sequence.add(1)));
            throw new IllegalStateException("test");
        }));
        assertEquals(List.of(1), sequence);
    }

    @Test
    void asyncReturnWithError() {
        var source = TestPublisher.<Disposable>create();
        var handle = root.effect(source.flux());
        source.error(new IllegalStateException("test"));
        var error = assertThrows(RuntimeException.class, () -> handle.ready().block());
        assertEquals("test", error.getMessage());
    }

    @Test
    void asyncYieldWithError() {
        var sequence = new ArrayList<Integer>();
        var source = TestPublisher.<Disposable>create();
        var handle = root.effect(source.flux());
        source.next(disposer(sequence, 1));
        source.error(new IllegalStateException("test"));
        assertThrows(RuntimeException.class, () -> handle.ready().block());
        assertEquals(List.of(1), sequence);
    }

    private static Disposable disposer(List<Integer> sequence, int value) {
        return Disposables.from(() -> sequence.add(value));
    }
}
