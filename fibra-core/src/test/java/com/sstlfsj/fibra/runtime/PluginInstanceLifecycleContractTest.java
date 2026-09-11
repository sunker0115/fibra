package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginInstanceLifecycleContractTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ServiceKey<Counter> COUNTER = ServiceKey.of("counter", Counter.class);

    @Test
    void preparedMountValidatesOnceWithoutCreatingThePluginUntilMount() {
        var validations = new AtomicInteger();
        var creations = new AtomicInteger();
        var definition = PluginDefinition.builder("prepared", String.class, () -> {
            creations.incrementAndGet();
            return (context, config) -> reactor.core.publisher.Mono.empty();
        }).validator(config -> {
            validations.incrementAndGet();
            return config + "-normalized";
        }).build();

        var prepared = definition.prepare("initial");
        assertEquals(1, validations.get());
        assertEquals(0, creations.get());
        assertEquals("initial-normalized", prepared.config());

        try (var runtime = FibraRuntime.create()) {
            var instance = runtime.rootScope().context().plugins()
                .mount("prepared", prepared);
            instance.settled().block(TIMEOUT);
            assertEquals(1, creations.get());
            assertEquals(1, validations.get());
            assertEquals("initial-normalized", instance.config());

            instance.update("updated").block(TIMEOUT);
            assertEquals(2, validations.get());
            assertEquals("updated-normalized", instance.config());
        }
    }

    @Test
    void updateDuringStartupConvergesToTheLatestConfigBeforeCompleting() throws Exception {
        var loading = reactor.core.publisher.Sinks.<Void>one();
        try (var runtime = FibraRuntime.create()) {
            var starts = new java.util.concurrent.CopyOnWriteArrayList<String>();
            var stops = new java.util.concurrent.CopyOnWriteArrayList<String>();
            var definition = PluginDefinition.builder("updating", String.class,
                () -> (context, config) -> {
                    starts.add(config);
                    context.effects().add(Disposables.from(() -> stops.add(config)));
                    return "old".equals(config) ? loading.asMono()
                        : reactor.core.publisher.Mono.empty();
                }).build();
            var instance = runtime.rootScope().context().plugins()
                .mount("updating", definition.prepare("old"));
            try {
                var first = instance.update("intermediate").toFuture();
                var latest = instance.update("latest").toFuture();
                loading.tryEmitEmpty();
                first.get(5, java.util.concurrent.TimeUnit.SECONDS);
                latest.get(5, java.util.concurrent.TimeUnit.SECONDS);

                assertEquals(java.util.List.of("old", "latest"), starts);
                assertEquals(java.util.List.of("old"), stops);
                assertEquals("latest", instance.config());
                assertEquals(PluginInstanceState.ACTIVE, instance.state());
            } finally {
                loading.tryEmitEmpty();
            }
        }
    }

    @Test
    void replacementProviderDuringStartupCleansTheOldSnapshotBeforeReloading() throws Exception {
        var loading = reactor.core.publisher.Sinks.<Void>one();
        try (var runtime = FibraRuntime.create()) {
            var key = ServiceKey.of("value", String.class);
            var firstProvider = runtime.rootScope().openChild("first-provider");
            var nextProvider = runtime.rootScope().openChild("next-provider");
            var registration = firstProvider.context().services().provide(key, "old");
            var seen = new java.util.concurrent.CopyOnWriteArrayList<String>();
            var definition = PluginDefinition.builder("consumer", Void.class,
                () -> (context, config) -> {
                    var value = context.services().require(key);
                    seen.add("start:" + value);
                    context.effects().add(Disposables.from(() ->
                        seen.add("stop:" + context.services().require(key))));
                    return "old".equals(value) ? loading.asMono()
                        : reactor.core.publisher.Mono.empty();
                }).require(key).build();
            var instance = runtime.rootScope().context().plugins()
                .mount("consumer", definition.prepare(null));
            try {
                var removal = registration.dispose().toFuture();
                nextProvider.context().services().provide(key, "new");
                loading.tryEmitEmpty();
                instance.settled().block(TIMEOUT);
                removal.get(5, java.util.concurrent.TimeUnit.SECONDS);

                assertEquals(java.util.List.of("start:old", "stop:old", "start:new"), seen);
            } finally {
                loading.tryEmitEmpty();
            }
        }
    }

    @Test
    void missingDependencyIsSettledPendingAndProviderActivatesConsumer() {
        try (var runtime = FibraRuntime.create()) {
            var consumerStarts = new AtomicInteger();
            var consumerStops = new AtomicInteger();
            var consumerDefinition = PluginDefinition.builder(
                    "consumer", Void.class,
                    () -> (context, config) -> {
                        consumerStarts.incrementAndGet();
                        context.effects().add(Disposables.from(consumerStops::incrementAndGet));
                        return reactor.core.publisher.Mono.empty();
                    })
                .require(COUNTER)
                .build();
            var providerDefinition = PluginDefinition.builder(
                    "provider", Void.class,
                    () -> (context, config) -> {
                        context.services().provide(COUNTER, new Counter());
                        return reactor.core.publisher.Mono.empty();
                    })
                .provide(COUNTER)
                .build();

            var consumer = runtime.rootScope().context().plugins()
                .mount("consumer-1", consumerDefinition.prepare(null));
            assertSame(consumer, consumer.settled().block(TIMEOUT));
            assertEquals(PluginInstanceState.PENDING, consumer.state());

            var provider = runtime.rootScope().context().plugins()
                .mount("provider-1", providerDefinition.prepare(null));
            provider.settled().block(TIMEOUT);
            consumer.settled().block(TIMEOUT);
            assertEquals(PluginInstanceState.ACTIVE, provider.state());
            assertEquals(PluginInstanceState.ACTIVE, consumer.state());
            assertEquals(1, consumerStarts.get());

            provider.dispose().block(TIMEOUT);
            consumer.settled().block(TIMEOUT);
            assertEquals(PluginInstanceState.PENDING, consumer.state());
            assertEquals(1, consumerStops.get());
        }
    }

    @Test
    void failedInstanceRecoversOnlyAfterExplicitUpdate() {
        try (var runtime = FibraRuntime.create()) {
            var starts = new AtomicInteger();
            var definition = PluginDefinition.builder(
                    "recoverable", String.class,
                    () -> (context, config) -> {
                        starts.incrementAndGet();
                        if ("bad".equals(config)) {
                            return reactor.core.publisher.Mono.error(new IllegalStateException("bad config"));
                        }
                        return reactor.core.publisher.Mono.empty();
                    })
                .build();
            var instance = runtime.rootScope().context().plugins()
                .mount("recoverable-1", definition.prepare("bad"));

            instance.settled().onErrorResume(error -> reactor.core.publisher.Mono.just(instance))
                .block(TIMEOUT);
            assertEquals(PluginInstanceState.FAILED, instance.state());

            instance.update("good").block(TIMEOUT);
            assertEquals(PluginInstanceState.ACTIVE, instance.state());
            assertEquals(2, starts.get());
        }
    }

    @Test
    void pluginContextExposesItsCurrentInstanceIdentity() {
        try (var runtime = FibraRuntime.create()) {
            var current = new AtomicReference<String>();
            var definition = PluginDefinition.builder("identity", Void.class,
                () -> (context, config) -> {
                    current.set(context.plugins().current().orElseThrow().id());
                    return reactor.core.publisher.Mono.empty();
                }).build();

            runtime.rootScope().context().plugins().mount("identity-1", definition.prepare(null))
                .settled().block(TIMEOUT);

            assertEquals("identity-1", current.get());
            assertTrue(runtime.rootScope().context().plugins().current().isEmpty());
        }
    }

    @Test
    void supervisedResourceFailureRevokesSiblingResourcesAndFailsTheInstance() {
        try (var runtime = FibraRuntime.create()) {
            var stopped = new AtomicInteger();
            var failure = reactor.core.publisher.Sinks.<Void>one();
            var definition = PluginDefinition.builder("supervised", Void.class,
                () -> (context, config) -> {
                    context.effects().add(Disposables.from(stopped::incrementAndGet));
                    context.effects().supervise(failure.asMono(), "sidecar");
                    return reactor.core.publisher.Mono.empty();
                }).build();
            var instance = runtime.rootScope().context().plugins()
                .mount("supervised-1", definition.prepare(null));
            instance.settled().block(TIMEOUT);

            failure.tryEmitError(new IllegalStateException("sidecar exited"));

            instance.settled().onErrorResume(error ->
                reactor.core.publisher.Mono.just(instance)).block(TIMEOUT);
            assertEquals(PluginInstanceState.FAILED, instance.state());
            assertEquals("sidecar exited", instance.failure().orElseThrow().getMessage());
            assertEquals(1, stopped.get());
        }
    }

    @Test
    void callerInterceptOverridesTheDefinitionDefault() {
        try (var runtime = FibraRuntime.create()) {
            runtime.rootScope().context().services().provide(COUNTER, new Counter());
            var seen = new AtomicReference<Object>();
            var definition = PluginDefinition.builder("intercepted", Void.class,
                    () -> (context, config) -> {
                        seen.set(context.intercept(COUNTER));
                        return reactor.core.publisher.Mono.empty();
                    })
                .require(COUNTER, "definition-default")
                .build();

            runtime.rootScope().context().withIntercept(COUNTER, "desired-value")
                .plugins().mount("intercepted-1", definition.prepare(null))
                .settled().block(TIMEOUT);

            assertEquals("desired-value", seen.get());
        }
    }

    @Test
    void pluginCannotPublishAnUndeclaredService() {
        try (var runtime = FibraRuntime.create()) {
            var definition = PluginDefinition.builder("invalid-provider", Void.class,
                () -> (context, config) -> {
                    context.services().provide(COUNTER, new Counter());
                    return reactor.core.publisher.Mono.empty();
                }).build();
            var instance = runtime.rootScope().context().plugins()
                .mount("invalid-provider-1", definition.prepare(null));

            instance.settled().onErrorResume(error ->
                reactor.core.publisher.Mono.just(instance)).block(TIMEOUT);

            assertEquals(PluginInstanceState.FAILED, instance.state());
            assertEquals(com.sstlfsj.fibra.FibraException.PLUGIN_UNDECLARED_SERVICE,
                ((com.sstlfsj.fibra.FibraException)
                    instance.failure().orElseThrow()).code());
        }
    }

    private static final class Counter {
    }
}
