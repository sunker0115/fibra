package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Cordis packages/core/tests/service.spec.ts 的 5 项逐条映射。 */
class ServiceSpecParityTest extends CordisSpecSupport {
    private static final ServiceKey<Counter> FOO = ServiceKey.of("foo", Counter.class);
    private static final ServiceKey<Counter> BAR = ServiceKey.of("bar", Counter.class);
    private static final ServiceKey<Counter> QUX = ServiceKey.of("qux", Counter.class);

    @Test
    void pendingInject() {
        var gate = Sinks.<Void>one();
        var calls = new AtomicInteger();
        var consumer = root.plugin(PluginDescriptor.<Void>builder("consumer").require(FOO).build(),
            (context, config) -> { calls.incrementAndGet(); return Mono.empty(); }, null);
        var provider = root.plugin(PluginDescriptor.<Void>builder("provider").provide(FOO).build(),
            (context, config) -> { context.provide(FOO, new Counter()); return gate.asMono().thenMany(Mono.empty()); }, null);
        assertEquals(FibraState.LOADING, provider.state());
        assertEquals(FibraState.PENDING, consumer.state());
        gate.tryEmitEmpty(); await(provider); await(consumer);
        assertEquals(1, calls.get());
    }

    @Test
    void traceableEffectWithInject() {
        root.provide(QUX, new Counter());
        root.provide(FOO, new Counter());
        var caller = root.plugin(PluginDescriptor.<Void>builder("caller").require(FOO).build(),
            (context, config) -> {
                context.service(FOO).invoke((invocation, counter) -> {
                    invocation.effect(() -> Disposables.from(counter::increment), "call");
                    return null;
                });
                return Mono.empty();
            }, null);
        await(caller);
        assertEquals(0, root.get(FOO).value());
        caller.dispose().block();
        assertEquals(1, root.get(FOO).value());
    }

    @Test
    void traceableEffectWithoutInject() {
        var counter = new Counter();
        root.provide(FOO, counter);
        root.service(FOO).invoke((invocation, service) -> {
            invocation.effect(() -> Disposables.from(service::increment), "root-call");
            return null;
        });
        root.fibra().restart().block();
        assertEquals(1, counter.value());
    }

    @Test
    void compareSnapshot() {
        var descriptor = PluginDescriptor.<Void>builder("snapshot").provide(FOO).build();
        var first = root.plugin(descriptor, (context, config) -> {
            context.provide(FOO, new Counter()); return Mono.empty();
        }, null);
        await(first);
        assertNotNull(root.get(FOO));
        first.dispose().block();
        assertNull(root.get(FOO));
        var second = root.plugin(descriptor, (context, config) -> {
            context.provide(FOO, new Counter()); return Mono.empty();
        }, null);
        await(second);
        assertNotNull(root.get(FOO));
    }

    @Test
    void multipleInjects() {
        var fooCalls = new AtomicInteger();
        var barCalls = new AtomicInteger();
        root.provide(QUX, new Counter());
        var foo = root.plugin(PluginDescriptor.<Void>builder("foo").require(QUX).provide(FOO).build(),
            (context, config) -> { fooCalls.incrementAndGet(); context.provide(FOO, new Counter()); return Mono.empty(); }, null);
        var bar = root.plugin(PluginDescriptor.<Void>builder("bar").require(FOO).require(QUX).provide(BAR).build(),
            (context, config) -> { barCalls.incrementAndGet(); context.provide(BAR, new Counter()); return Mono.empty(); }, null);
        await(foo); await(bar);
        assertEquals(1, fooCalls.get());
        assertEquals(1, barCalls.get());
    }

    static final class Counter {
        private int value;
        int value() { return value; }
        void increment() { value++; }
    }
}
