package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.annotation.InjectService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DecoratorSpecParityTest extends CordisSpecSupport {
    private static final ServiceKey<Object> FOO = ServiceKey.of("foo", Object.class);

    @Test
    void injectOnClassMethod() {
        var starts = new AtomicInteger();
        var stops = new AtomicInteger();
        var definition = PluginDefinition.builder("bar", Void.class,
                () -> new Bar(starts, stops))
            .inject(Bar.class).build();
        var owner = root.plugins().mount("bar", definition.prepare(null));
        await(owner);
        var child = root.plugins().find("bar:inject:method").orElseThrow();
        assertEquals(0, starts.get());
        var provider = root.services().provide(FOO, new Object());
        await(child);
        assertEquals(1, starts.get());
        provider.dispose().block(TIMEOUT);
        assertEquals(1, stops.get());
    }

    private static final class Bar implements Plugin<Void> {
        private final AtomicInteger starts;
        private final AtomicInteger stops;

        private Bar(AtomicInteger starts, AtomicInteger stops) {
            this.starts = starts;
            this.stops = stops;
        }

        @Override
        public Mono<Void> start(com.sstlfsj.fibra.Context context, Void config) {
            return Mono.empty();
        }

        @InjectService(value = "foo", type = Object.class)
        private Disposable method() {
            starts.incrementAndGet();
            return Disposables.from(stops::incrementAndGet);
        }
    }
}
