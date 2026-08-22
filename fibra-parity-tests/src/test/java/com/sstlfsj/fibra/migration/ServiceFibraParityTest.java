package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.*;
import com.sstlfsj.fibra.runtime.FibraRuntime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ServiceFibraParityTest {
    private static final ServiceKey<Counter> COUNTER = ServiceKey.of("counter", Counter.class);

    private final Context context = FibraRuntime.create();

    @AfterEach
    void closeContext() {
        context.closeAsync().block();
    }

    @Test
    void isolateUsesIdentityLabelsAndSharedLabelsJoinTheSameScope() {
        var rootCounter = new Counter();
        context.provide(COUNTER, rootCounter);
        var isolated = context.isolate(COUNTER);
        var label = new Object();
        var first = context.isolate(COUNTER, label);
        var second = context.isolate(COUNTER, label);
        var sharedCounter = new Counter();
        first.provide(COUNTER, sharedCounter);

        assertSame(rootCounter, context.get(COUNTER));
        assertNull(isolated.get(COUNTER));
        assertSame(sharedCounter, second.get(COUNTER));
    }

    @Test
    void dependencyActivatesAndRevokeWaitsForConsumerCleanup() {
        var sequence = new CopyOnWriteArrayList<String>();
        var cleanupGate = Sinks.<Void>one();
        var descriptor = PluginDescriptor.<Void>builder("consumer")
            .require(COUNTER)
            .build();
        var consumer = context.plugin(descriptor, (pluginContext, ignored) -> {
            sequence.add("load:" + pluginContext.get(COUNTER).value());
            return Mono.just(() -> Mono.defer(() -> {
                sequence.add("dispose");
                return cleanupGate.asMono();
            }));
        }, null);

        assertEquals(FibraState.PENDING, consumer.state());
        var registration = context.provide(COUNTER, new Counter());
        consumer.await().block();
        assertEquals(FibraState.ACTIVE, consumer.state());

        var revoke = registration.dispose().toFuture();
        assertEquals(List.of("load:0", "dispose"), sequence);
        assertEquals(FibraState.UNLOADING, consumer.state());

        cleanupGate.tryEmitEmpty();
        revoke.join();
        assertEquals(FibraState.PENDING, consumer.state());
    }

    @Test
    void readyCompletesWhenMissingDependencyIsStablyPending() {
        var consumer = context.plugin(
            PluginDescriptor.<Void>builder("consumer").require(COUNTER).build(),
            (pluginContext, ignored) -> Mono.empty(),
            null
        );

        consumer.ready().block();

        assertEquals(FibraState.PENDING, consumer.state());
    }

    @Test
    void boundServiceInvocationRunsOnTheCallingThread() {
        var callingThread = Thread.currentThread();
        var requestContext = new ThreadLocal<String>();
        requestContext.set("request");
        context.provide(COUNTER, new Counter());

        try {
            int value = context.service(COUNTER).invoke((invocation, service) -> {
                assertSame(callingThread, Thread.currentThread());
                assertEquals("request", requestContext.get());
                return service.value();
            });

            assertEquals(0, value);
        } finally {
            requestContext.remove();
        }
    }

    @Test
    void replacingAProviderReloadsTheConsumerWithANewEpoch() {
        var loads = new AtomicInteger();
        var descriptor = PluginDescriptor.<Void>builder("consumer")
            .require(COUNTER)
            .build();
        var first = context.provide(COUNTER, new Counter());
        var consumer = context.plugin(descriptor, (pluginContext, ignored) -> {
            pluginContext.get(COUNTER).increment();
            loads.incrementAndGet();
            return Mono.empty();
        }, null);

        consumer.await().block();
        first.dispose().block();
        var secondCounter = new Counter();
        context.provide(COUNTER, secondCounter);
        consumer.await().block();

        assertEquals(2, loads.get());
        assertEquals(1, secondCounter.value());
    }

    static final class Counter {
        private int value;

        int value() {
            return value;
        }

        void increment() {
            value++;
        }
    }
}
