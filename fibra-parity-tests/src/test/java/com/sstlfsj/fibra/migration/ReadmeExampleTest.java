package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.*;
import com.sstlfsj.fibra.runtime.FibraRuntime;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReadmeExampleTest {
    @Test
    void minimalUsageCompilesAndRunsWithoutModification() {
        ServiceKey<Greeting> greeting = ServiceKey.of("greeting", Greeting.class);
        var root = FibraRuntime.create();
        try {
            var registration = root.provide(greeting, name -> "你好，" + name);
            var descriptor = PluginDescriptor.<Void>builder("consumer")
                .require(greeting)
                .build();
            var observed = new String[1];
            var consumer = root.plugin(descriptor, (ctx, ignored) -> {
                observed[0] = ctx.service(greeting)
                    .invoke((invocation, service) -> service.greet("Fibra"));
                return Mono.just(Disposables.noop());
            });

            consumer.ready().block();
            assertEquals("你好，Fibra", observed[0]);
            registration.dispose().block();
            assertEquals(FibraState.PENDING, consumer.state());
        } finally {
            root.closeAsync().block();
        }
    }

    @FunctionalInterface
    interface Greeting {
        String greet(String name);
    }
}
