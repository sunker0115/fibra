package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.*;
import com.sstlfsj.fibra.annotation.InjectService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Cordis packages/core/tests/decorator.spec.ts 的 1 项逐条映射。 */
class DecoratorSpecParityTest extends CordisSpecSupport {
    private static final ServiceKey<Object> FOO = ServiceKey.of("foo", Object.class);

    @Test
    void injectOnClassMethod() {
        var starts = new AtomicInteger();
        var stops = new AtomicInteger();
        var owner = root.plugin(PluginDescriptor.<Void>builder("bar").build(),
            (context, config) -> new Bar(starts, stops), ignored -> Mono.empty(), null);
        await(owner);
        assertEquals(0, starts.get());
        var provider = root.provide(FOO, new Object());
        org.awaitility.Awaitility.await().until(() -> starts.get() == 1);
        provider.dispose().block();
        org.awaitility.Awaitility.await().until(() -> stops.get() == 1);
    }

    static final class Bar {
        private final AtomicInteger starts;
        private final AtomicInteger stops;
        Bar(AtomicInteger starts, AtomicInteger stops) { this.starts = starts; this.stops = stops; }
        @InjectService(value = "foo", type = Object.class)
        private Disposable method() {
            starts.incrementAndGet();
            return Disposables.from(stops::incrementAndGet);
        }
    }
}
