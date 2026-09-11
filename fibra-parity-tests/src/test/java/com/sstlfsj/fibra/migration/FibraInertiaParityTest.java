package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicInteger;

import static com.sstlfsj.fibra.migration.MigrationTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FibraInertiaParityTest {
    private static final ServiceKey<Value> VALUE = ServiceKey.of("value", Value.class);

    @Test
    void dependencyCanDisappearDuringLoadAndReturnDuringUnload() {
        var firstLoad = Sinks.<Void>one();
        var firstUnload = Sinks.<Void>one();
        var loads = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            var firstProvider = context.services().provide(VALUE, new Value(1));
            var definition = PluginDefinition.builder("consumer", Void.class,
                    () -> (pluginContext, ignored) -> {
                        int attempt = loads.incrementAndGet();
                        if (attempt == 1) {
                            pluginContext.effects().add(firstUnload::asMono);
                            return firstLoad.asMono();
                        }
                        return Mono.empty();
                    })
                .require(VALUE)
                .build();
            var consumer = context.plugins().mount("consumer", definition.prepare(null));

            await(() -> consumer.state() == PluginInstanceState.STARTING);
            var revoke = firstProvider.dispose().toFuture();
            await(() -> context.services().find(VALUE).isEmpty());
            assertEquals(PluginInstanceState.STARTING, consumer.state());
            firstLoad.tryEmitEmpty();
            await(() -> consumer.state() == PluginInstanceState.STOPPING);
            context.services().provide(VALUE, new Value(2));
            assertFalse(revoke.isDone());
            firstUnload.tryEmitEmpty();
            revoke.join();
            consumer.settled().block();

            assertEquals(PluginInstanceState.ACTIVE, consumer.state());
            assertEquals(2, loads.get());
        }
    }

    @Test
    void removingAPluginProviderWaitsUntilItsConsumerIsPending() {
        var unload = Sinks.<Void>one();
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            var providerDefinition = PluginDefinition.builder("provider", Void.class,
                    () -> (pluginContext, ignored) -> {
                        pluginContext.services().provide(VALUE, new Value(1));
                        return Mono.empty();
                    })
                .provide(VALUE)
                .build();
            var provider = context.plugins().mount("provider", providerDefinition.prepare(null));
            provider.settled().block();
            var consumerDefinition = PluginDefinition.builder("consumer", Void.class,
                    () -> (pluginContext, ignored) -> {
                        pluginContext.effects().add(unload::asMono);
                        return Mono.empty();
                    })
                .require(VALUE)
                .build();
            var consumer = context.plugins().mount("consumer", consumerDefinition.prepare(null));
            consumer.settled().block();

            var disposal = provider.dispose().toFuture();
            await(() -> consumer.state() == PluginInstanceState.STOPPING);
            assertFalse(disposal.isDone());
            unload.tryEmitEmpty();
            disposal.join();

            assertEquals(PluginInstanceState.PENDING, consumer.state());
            assertEquals(PluginInstanceState.DISPOSED, provider.state());
        }
    }

    private record Value(int number) {
    }
}
