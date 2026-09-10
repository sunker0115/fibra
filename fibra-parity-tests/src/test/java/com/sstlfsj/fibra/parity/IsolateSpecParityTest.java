package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.EventMode;
import com.sstlfsj.fibra.event.EventTarget;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsolateSpecParityTest extends CordisSpecSupport {
    private static final ServiceKey<Value> FOO = ServiceKey.of("foo", Value.class);
    private static final EventKey<Signal> EVENT = EventKey.of(
        "test/isolated", Signal.class, EventMode.EMIT);

    @Test
    void isolatedContext() {
        var definition = PluginDefinition.builder("consumer", Void.class,
                () -> (context, config) -> Mono.empty())
            .require(FOO).build();
        var rootConsumer = root.plugins().mount("root-consumer", definition, null);
        var first = root.withRealm(FOO, "first");
        var second = root.withRealm(FOO, "second");
        var firstConsumer = first.plugins().mount("first-consumer", definition, null);
        var secondConsumer = second.plugins().mount("second-consumer", definition, null);
        root.services().provide(FOO, new Value(100));
        await(rootConsumer);
        assertTrue(first.services().find(FOO).isEmpty());
        assertTrue(second.services().find(FOO).isEmpty());
        first.services().provide(FOO, new Value(200));
        second.services().provide(FOO, new Value(300));
        await(firstConsumer);
        await(secondConsumer);
        assertEquals(100, root.services().require(FOO).number);
        assertEquals(200, first.services().require(FOO).number);
        assertEquals(300, second.services().require(FOO).number);
    }

    @Test
    void sharedLabel() {
        var first = root.withRealm(FOO, "shared");
        var second = root.withRealm(FOO, new String("shared"));
        var value = new Value(200);
        first.services().provide(FOO, value);
        assertSame(value, second.services().require(FOO));
        assertTrue(root.services().find(FOO).isEmpty());
    }

    @Test
    void isolatedEvent() {
        var isolated = root.withMetadata("scope", "isolated");
        var outer = new AtomicInteger();
        var inner = new AtomicInteger();
        root.events().on(EVENT, outer::incrementAndGet);
        isolated.events().on(EVENT, inner::incrementAndGet);
        root.events().emit(EventTarget.context(isolated), EVENT, Signal::call);
        assertEquals(0, outer.get());
        assertEquals(1, inner.get());
    }

    private record Value(int number) {
    }

    @FunctionalInterface
    private interface Signal {
        void call();
    }
}
