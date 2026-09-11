package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReadmeExampleTest {
    @Test
    void minimalUsageCompilesAndRunsWithoutModification() {
        ServiceKey<Greeting> greeting = ServiceKey.of("greeting", Greeting.class);
        try (var runtime = FibraRuntime.create()) {
            var root = runtime.rootScope().context();
            var registration = root.services().provide(greeting,
                name -> "你好，" + name);
            var observed = new String[1];
            var definition = PluginDefinition.builder("consumer", Void.class,
                    () -> (context, ignored) -> {
                        observed[0] = context.services().reference(greeting)
                            .invoke((invocation, service) -> service.greet("Fibra"));
                        return Mono.empty();
                    })
                .require(greeting)
                .build();
            var consumer = root.plugins().mount("consumer", definition.prepare(null));

            consumer.settled().block();
            assertEquals("你好，Fibra", observed[0]);
            registration.dispose().block();
            assertEquals(PluginInstanceState.PENDING, consumer.state());
        }
    }

    @FunctionalInterface
    private interface Greeting {
        String greet(String name);
    }
}
