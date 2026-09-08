package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VNextScenarioTest {
    @Test
    void scopesPluginsServicesAndEffectsComposeWithoutAnEngine() {
        var starts = new AtomicInteger();
        var stops = new AtomicInteger();
        var greeting = ServiceKey.of("greeting", Greeting.class);
        var definition = PluginDefinition.builder("provider", String.class,
                () -> (context, config) -> {
                    starts.incrementAndGet();
                    context.services().provide(greeting, name -> config + ", " + name);
                    context.effects().add(() -> Mono.fromRunnable(stops::incrementAndGet));
                    return Mono.empty();
                })
            .provide(greeting)
            .build();

        try (var runtime = FibraRuntime.create()) {
            var child = runtime.rootScope().openChild("scenario");
            var instance = child.context().plugins().mount("provider", definition, "hello");
            instance.settled().block();

            assertEquals(PluginInstanceState.ACTIVE, instance.state());
            assertEquals("hello, Fibra",
                child.context().services().require(greeting).greet("Fibra"));
            child.close();
            assertEquals(PluginInstanceState.DISPOSED, instance.state());
        }
        assertEquals(1, starts.get());
        assertEquals(1, stops.get());
    }

    interface Greeting {
        String greet(String name);
    }
}
