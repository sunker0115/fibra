package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.*;
import com.sstlfsj.fibra.event.EventKey;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Cordis packages/core/tests/isolate.spec.ts 的 3 项逐条映射。 */
class IsolateSpecParityTest extends CordisSpecSupport {
    private static final ServiceKey<Value> FOO = ServiceKey.of("foo", Value.class);
    private static final EventKey<Signal> EVENT = EventKey.of("test/isolated", Signal.class);

    @Test
    void isolatedContext() {
        var descriptor = PluginDescriptor.<Void>builder("consumer").require(FOO).build();
        var loads = new AtomicInteger();
        var rootConsumer = root.plugin(descriptor, (context, config) -> { loads.incrementAndGet(); return Mono.empty(); }, null);
        var first = root.isolate(FOO);
        var second = root.isolate(FOO);
        var firstConsumer = first.plugin(descriptor, (context, config) -> { loads.incrementAndGet(); return Mono.empty(); }, null);
        var secondConsumer = second.plugin(descriptor, (context, config) -> { loads.incrementAndGet(); return Mono.empty(); }, null);
        root.provide(FOO, new Value(100)); await(rootConsumer);
        assertNull(first.get(FOO)); assertNull(second.get(FOO));
        first.provide(FOO, new Value(200)); await(firstConsumer);
        second.provide(FOO, new Value(300)); await(secondConsumer);
        assertEquals(3, loads.get());
        assertEquals(100, root.get(FOO).number());
        assertEquals(200, first.get(FOO).number());
        assertEquals(300, second.get(FOO).number());
    }

    @Test
    void sharedLabel() {
        var label = new Object();
        var first = root.isolate(FOO, label);
        var second = root.isolate(FOO, label);
        first.provide(FOO, new Value(200));
        assertSame(first.get(FOO), second.get(FOO));
        assertNull(root.get(FOO));
    }

    @Test
    void isolatedEvent() {
        var isolated = root.isolate(FOO);
        var outer = new AtomicInteger();
        var inner = new AtomicInteger();
        root.on(EVENT, outer::incrementAndGet);
        isolated.on(EVENT, inner::incrementAndGet);
        root.emit(context -> context == isolated, EVENT, Signal::call);
        assertEquals(0, outer.get());
        assertEquals(1, inner.get());
    }

    record Value(int number) {}
    @FunctionalInterface interface Signal { void call(); }
}
