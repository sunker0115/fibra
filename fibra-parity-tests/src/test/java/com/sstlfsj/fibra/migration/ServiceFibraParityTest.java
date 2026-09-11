package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstance;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.sstlfsj.fibra.migration.MigrationTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceFibraParityTest {
    private static final ServiceKey<Counter> COUNTER =
        ServiceKey.of("counter", Counter.class);

    @Test
    void isolateUsesIdentityLabelsAndSharedLabelsJoinTheSameScope() {
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            var rootCounter = new Counter();
            context.services().provide(COUNTER, rootCounter);
            var isolated = context.withRealm(COUNTER, new Object());
            var label = new Object();
            var first = context.withRealm(COUNTER, label);
            var second = context.withRealm(COUNTER, label);
            var sharedCounter = new Counter();
            first.services().provide(COUNTER, sharedCounter);

            assertSame(rootCounter, context.services().require(COUNTER));
            assertTrue(isolated.services().find(COUNTER).isEmpty());
            assertSame(sharedCounter, second.services().require(COUNTER));
        }
    }

    @Test
    void nameOnlyIsolationDoesNotDeclareAServiceType() {
        try (var runtime = FibraRuntime.create()) {
            var root = runtime.rootScope().context();
            var textRealm = root.withRealm("value", "text");
            var numberRealm = root.withRealm("value", "number");
            var text = ServiceKey.of("value", String.class);
            var number = ServiceKey.of("value", Integer.class);

            textRealm.services().provide(text, "value");
            numberRealm.services().provide(number, 42);

            assertEquals("value", root.withRealm("value", "text").services().require(text));
            assertEquals(42, root.withRealm("value", "number").services().require(number));
            assertTrue(root.services().find(text).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> textRealm.services().require(number));
        }
    }

    @Test
    void nameOnlyDependencyWaitsForTheTypedProviderWithoutDeclaringObjectType() {
        var loads = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            var definition = PluginDefinition.builder("consumer", Void.class,
                    () -> (pluginContext, ignored) -> {
                        assertSame(context.services().require(COUNTER),
                            pluginContext.services().require(COUNTER));
                        loads.incrementAndGet();
                        return Mono.empty();
                    })
                .require(COUNTER)
                .build();
            var consumer = context.plugins().mount("consumer", definition.prepare(null));

            assertEquals(PluginInstanceState.PENDING, consumer.state());
            context.services().provide(COUNTER, new Counter());
            consumer.settled().block();

            assertEquals(PluginInstanceState.ACTIVE, consumer.state());
            assertEquals(1, loads.get());
        }
    }

    @Test
    void pendingFibraCanAddANameOnlyDependencyDirectly() {
        try (var runtime = FibraRuntime.create()) {
            var definition = PluginDefinition.builder("consumer", Void.class,
                    () -> (context, ignored) -> Mono.empty())
                .require(COUNTER)
                .build();
            var consumer = runtime.rootScope().context().plugins()
                .mount("consumer", definition.prepare(null));

            assertEquals(PluginInstanceState.PENDING, consumer.state());
            assertFalse(java.util.Arrays.stream(PluginInstance.class.getMethods())
                .anyMatch(method -> method.getName().equals("require")));
            assertThrows(UnsupportedOperationException.class,
                () -> definition.requires().put(ServiceKey.of("other", Object.class), null));
        }
    }

    @Test
    void descriptorRejectsTheSameDependencyDeclaredByNameAndType() {
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            context.services().provide(COUNTER, new Counter());
            var conflicting = ServiceKey.of(COUNTER.name(), Object.class);

            assertThrows(IllegalArgumentException.class,
                () -> context.services().find(conflicting));
            assertFalse(java.util.Arrays.stream(PluginDefinition.Builder.class.getMethods())
                .anyMatch(method -> method.getName().equals("require")
                    && method.getParameterTypes()[0] == String.class));
        }
    }

    @Test
    void configurationRequirementsOverrideTypedInterceptWithoutLosingItsType() {
        var seen = new AtomicReference<Object>();
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            context.services().provide(COUNTER, new Counter());
            var definition = PluginDefinition.builder("consumer", Void.class,
                    () -> (pluginContext, ignored) -> {
                        seen.set(pluginContext.intercept(COUNTER));
                        return Mono.empty();
                    })
                .require(COUNTER, "original")
                .build();

            context.withIntercept(COUNTER, "configured").plugins()
                .mount("consumer", definition.prepare(null)).settled().block();

            assertEquals("configured", seen.get());
            assertEquals(Counter.class,
                definition.requires().keySet().iterator().next().type());
        }
    }

    @Test
    void dependencyActivatesAndRevokeWaitsForConsumerCleanup() {
        var sequence = new CopyOnWriteArrayList<String>();
        var cleanupGate = Sinks.<Void>one();
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            var definition = PluginDefinition.builder("consumer", Void.class,
                    () -> (pluginContext, ignored) -> {
                        sequence.add("load:" + pluginContext.services()
                            .require(COUNTER).value());
                        pluginContext.effects().add(cleanupGate::asMono);
                        return Mono.empty();
                    })
                .require(COUNTER)
                .build();
            var consumer = context.plugins().mount("consumer", definition.prepare(null));
            assertEquals(PluginInstanceState.PENDING, consumer.state());
            var registration = context.services().provide(COUNTER, new Counter());
            consumer.settled().block();

            var revoke = registration.dispose().toFuture();
            await(() -> consumer.state() == PluginInstanceState.STOPPING);
            assertEquals(List.of("load:0"), sequence);
            assertFalse(revoke.isDone());
            cleanupGate.tryEmitEmpty();
            revoke.join();

            assertEquals(PluginInstanceState.PENDING, consumer.state());
        }
    }

    @Test
    void readyCompletesWhenMissingDependencyIsStablyPending() {
        try (var runtime = FibraRuntime.create()) {
            var definition = PluginDefinition.builder("consumer", Void.class,
                    () -> (context, ignored) -> Mono.empty())
                .require(COUNTER)
                .build();
            var consumer = runtime.rootScope().context().plugins()
                .mount("consumer", definition.prepare(null));

            consumer.settled().block();

            assertEquals(PluginInstanceState.PENDING, consumer.state());
        }
    }

    @Test
    void boundServiceInvocationRunsOnTheCallingThread() {
        var callingThread = Thread.currentThread();
        var requestContext = new ThreadLocal<String>();
        requestContext.set("request");
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            context.services().provide(COUNTER, new Counter());

            int value = context.services().reference(COUNTER).invoke((invocation, service) -> {
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
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            var first = context.services().provide(COUNTER, new Counter());
            var definition = PluginDefinition.builder("consumer", Void.class,
                    () -> (pluginContext, ignored) -> {
                        pluginContext.services().require(COUNTER).increment();
                        loads.incrementAndGet();
                        return Mono.empty();
                    })
                .require(COUNTER)
                .build();
            var consumer = context.plugins().mount("consumer", definition.prepare(null));
            consumer.settled().block();
            first.dispose().block();
            var secondCounter = new Counter();
            context.services().provide(COUNTER, secondCounter);
            consumer.settled().block();

            assertEquals(2, loads.get());
            assertEquals(1, secondCounter.value());
        }
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
