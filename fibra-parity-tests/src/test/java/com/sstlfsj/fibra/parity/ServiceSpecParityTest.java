package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceSpecParityTest extends CordisSpecSupport {
    private static final ServiceKey<Counter> FOO = ServiceKey.of("foo", Counter.class);
    private static final ServiceKey<Counter> BAR = ServiceKey.of("bar", Counter.class);
    private static final ServiceKey<Counter> QUX = ServiceKey.of("qux", Counter.class);

    @Test
    void pendingInject() {
        var gate = Sinks.<Void>one();
        var calls = new AtomicInteger();
        var consumerDefinition = PluginDefinition.builder("consumer", Void.class,
                () -> (context, config) -> {
                    calls.incrementAndGet();
                    return Mono.empty();
                })
            .require(FOO).build();
        var consumer = root.plugins().mount("consumer", consumerDefinition.prepare(null));
        var providerDefinition = PluginDefinition.builder("provider", Void.class,
                () -> (context, config) -> {
                    context.services().provide(FOO, new Counter());
                    return gate.asMono();
                })
            .provide(FOO).build();
        var provider = root.plugins().mount("provider", providerDefinition.prepare(null));
        assertEquals(PluginInstanceState.STARTING, provider.state());
        assertEquals(PluginInstanceState.PENDING, consumer.state());
        gate.tryEmitEmpty();
        await(provider);
        await(consumer);
        assertEquals(1, calls.get());
    }

    @Test
    void traceableEffectWithInject() {
        root.services().provide(FOO, new Counter());
        var definition = PluginDefinition.builder("caller", Void.class,
                () -> (context, config) -> {
                    context.services().reference(FOO).invoke((invocation, counter) -> {
                        invocation.effects().add(
                            Disposables.from(counter::increment));
                        return null;
                    });
                    return Mono.empty();
                })
            .require(FOO).build();
        var caller = root.plugins().mount("caller", definition.prepare(null));
        await(caller);
        assertEquals(0, root.services().require(FOO).value());
        caller.dispose().block(TIMEOUT);
        assertEquals(1, root.services().require(FOO).value());
    }

    @Test
    void traceableEffectWithoutInject() {
        var counter = new Counter();
        root.services().provide(FOO, counter);
        var request = runtime.rootScope().openChild("request");
        request.context().services().reference(FOO).invoke((invocation, service) -> {
            invocation.effects().add(Disposables.from(service::increment));
            return null;
        });
        request.close();
        assertEquals(1, counter.value());
    }

    @Test
    void compareSnapshot() {
        var definition = PluginDefinition.builder("snapshot", Void.class,
                () -> (context, config) -> {
                    context.services().provide(FOO, new Counter());
                    return Mono.empty();
                })
            .provide(FOO).build();
        var first = root.plugins().mount("first", definition.prepare(null));
        await(first);
        assertNotNull(root.services().require(FOO));
        first.dispose().block(TIMEOUT);
        assertTrue(root.services().find(FOO).isEmpty());
        var second = root.plugins().mount("second", definition.prepare(null));
        await(second);
        assertNotNull(root.services().require(FOO));
    }

    @Test
    void multipleInjects() {
        var fooCalls = new AtomicInteger();
        var barCalls = new AtomicInteger();
        root.services().provide(QUX, new Counter());
        var fooDefinition = PluginDefinition.builder("foo", Void.class,
                () -> (context, config) -> {
                    fooCalls.incrementAndGet();
                    context.services().provide(FOO, new Counter());
                    return Mono.empty();
                })
            .require(QUX).provide(FOO).build();
        var barDefinition = PluginDefinition.builder("bar", Void.class,
                () -> (context, config) -> {
                    barCalls.incrementAndGet();
                    context.services().provide(BAR, new Counter());
                    return Mono.empty();
                })
            .require(FOO).require(QUX).provide(BAR).build();
        await(root.plugins().mount("foo", fooDefinition.prepare(null)));
        await(root.plugins().mount("bar", barDefinition.prepare(null)));
        assertEquals(1, fooCalls.get());
        assertEquals(1, barCalls.get());
    }

    private static final class Counter {
        private int value;

        private int value() {
            return value;
        }

        private void increment() {
            value++;
        }
    }
}
