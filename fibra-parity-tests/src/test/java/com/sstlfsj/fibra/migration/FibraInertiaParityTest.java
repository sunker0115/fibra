package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.*;
import com.sstlfsj.fibra.runtime.FibraRuntime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FibraInertiaParityTest {
    private static final ServiceKey<Value> VALUE = ServiceKey.of("value", Value.class);

    private final Context context = FibraRuntime.create();

    @AfterEach
    void closeContext() {
        context.closeAsync().block();
    }

    @Test
    void dependencyCanDisappearDuringLoadAndReturnDuringUnload() {
        var firstLoad = Sinks.<Void>one();
        var firstUnload = Sinks.<Void>one();
        var loads = new AtomicInteger();
        var firstProvider = context.provide(VALUE, new Value(1));
        var descriptor = PluginDescriptor.<Void>builder("consumer").require(VALUE).build();
        var consumer = context.plugin(descriptor, (pluginContext, ignored) -> {
            int attempt = loads.incrementAndGet();
            if (attempt == 1) {
                return firstLoad.asMono().thenMany(Flux.just(() -> firstUnload.asMono()));
            }
            return Flux.empty();
        }, null);

        await().until(() -> consumer.state() == FibraState.LOADING);
        var revoke = firstProvider.dispose().toFuture();
        assertEquals(FibraState.LOADING, consumer.state());

        firstLoad.tryEmitEmpty();
        await().until(() -> consumer.state() == FibraState.UNLOADING);
        context.provide(VALUE, new Value(2));
        assertFalse(revoke.isDone());

        firstUnload.tryEmitEmpty();
        revoke.join();
        consumer.await().block();

        assertEquals(FibraState.ACTIVE, consumer.state());
        assertEquals(2, loads.get());
    }

    @Test
    void removingAPluginProviderWaitsUntilItsConsumerIsPending() {
        var unload = Sinks.<Void>one();
        var provider = context.plugin("provider", (pluginContext, ignored) -> {
            pluginContext.provide(VALUE, new Value(1));
            return Flux.empty();
        }, null);
        provider.await().block();
        var consumer = context.plugin(
            PluginDescriptor.<Void>builder("consumer").require(VALUE).build(),
            (pluginContext, ignored) -> Flux.just(() -> unload.asMono()),
            null
        );
        consumer.await().block();

        var disposal = provider.dispose().toFuture();
        await().until(() -> consumer.state() == FibraState.UNLOADING);
        assertFalse(disposal.isDone());

        unload.tryEmitEmpty();
        disposal.join();

        assertEquals(FibraState.PENDING, consumer.state());
        assertEquals(FibraState.DISPOSED, provider.state());
    }

    record Value(int number) {
    }
}
