package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginInstanceLifecycleContractTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ServiceKey<Counter> COUNTER = ServiceKey.of("counter", Counter.class);

    @ParameterizedTest
    @ValueSource(strings = {"same", "suppressed", "cause", "independent"})
    void startupAndCleanupErrorsHaveOneAggregateOwner(String relation) throws Exception {
        var startFailure = new IllegalStateException("startup failed");
        var cleanupFailure = relation.equals("same") ? startFailure
            : new IllegalStateException("cleanup failed");
        if (relation.equals("suppressed")) cleanupFailure.addSuppressed(startFailure);
        if (relation.equals("cause")) cleanupFailure.initCause(startFailure);
        var definition = PluginDefinition.builder("shared-failure", Void.class, () -> (context, config) -> {
            context.effects().add(() -> reactor.core.publisher.Mono.error(cleanupFailure));
            return reactor.core.publisher.Mono.error(startFailure);
        }).build();
        var runtime = FibraRuntime.create();
        try {
            var instance = runtime.rootScope().context().plugins().mount("shared-failure", definition.prepare(null));
            var reported = assertThrows(ExecutionException.class,
                () -> instance.settled().toFuture().get(5, TimeUnit.SECONDS)).getCause();
            if (relation.equals("independent")) {
                assertSame(startFailure, reported, "独立失败仍以原启动异常为根");
            } else {
                assertFalse(startFailure == reported, "清理聚合已拥有启动异常时必须保留聚合根");
                assertSame(cleanupFailure, reported.getSuppressed()[0]);
            }
            var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
            var pending = new java.util.ArrayDeque<Throwable>();
            pending.add(reported);
            while (!pending.isEmpty()) {
                var current = pending.removeFirst();
                assertTrue(seen.add(current), "异常图不能有对象环或重复引用");
                if (current.getCause() != null) pending.add(current.getCause());
                pending.addAll(java.util.Arrays.asList(current.getSuppressed()));
            }
            assertTrue(seen.contains(startFailure));
            assertTrue(seen.contains(cleanupFailure));
        } finally {
            runtime.closeAsync().toFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void anExistingCleanupCauseCycleDoesNotBlockStartupFailureReporting() throws Exception {
        var startup = new IllegalStateException("startup failed");
        var cleanup = new IllegalStateException("cleanup failed");
        cleanup.initCause(new IllegalStateException("preexisting cycle", cleanup));
        var definition = PluginDefinition.builder("cyclic-cleanup", Void.class, () -> (context, config) -> {
            context.effects().add(() -> reactor.core.publisher.Mono.error(cleanup));
            return reactor.core.publisher.Mono.error(startup);
        }).build();
        var runtime = FibraRuntime.create();
        try {
            var instance = runtime.rootScope().context().plugins().mount("cyclic-cleanup", definition.prepare(null));
            var reported = assertThrows(ExecutionException.class,
                () -> instance.settled().toFuture().get(5, TimeUnit.SECONDS)).getCause();
            assertSame(startup, reported);
            assertSame(cleanup, reported.getSuppressed()[0].getSuppressed()[0]);
        } finally {
            runtime.closeAsync().toFuture().get(5, TimeUnit.SECONDS);
        }
    }

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
    void preparedUpdateDoesNotValidateAgainAndRejectsAnotherDefinitionIdentity() {
        var validations = new AtomicInteger();
        var definition = PluginDefinition.builder("prepared-update", String.class,
            () -> (context, config) -> reactor.core.publisher.Mono.empty())
            .validator(config -> {
                validations.incrementAndGet();
                return config + "-normalized";
            }).build();
        var sameNameDifferentDefinition = PluginDefinition.builder("prepared-update", String.class,
            () -> (context, config) -> reactor.core.publisher.Mono.empty()).build();

        try (var runtime = FibraRuntime.create()) {
            var instance = runtime.rootScope().context().plugins()
                .mount("prepared-update", definition.prepare("initial"));
            var prepared = definition.prepare("next");

            instance.updatePrepared(prepared).block(TIMEOUT);

            assertEquals(2, validations.get());
            assertEquals("next-normalized", instance.config());
            assertThrows(IllegalArgumentException.class,
                () -> instance.updatePrepared(sameNameDifferentDefinition.prepare("foreign"))
                    .block(TIMEOUT));
            assertEquals("next-normalized", instance.config());
        }
    }

    @Test
    void preparedUpdateDuringStartupConvergesToTheLatestConfigBeforeCompleting() throws Exception {
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
                var first = instance.updatePrepared(definition.prepare("intermediate")).toFuture();
                var latest = instance.updatePrepared(definition.prepare("latest")).toFuture();
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
    void nullConfigUpdateKeepsStringVoidAndObjectConfigSemantics() {
        try (var runtime = FibraRuntime.create()) {
            var stringSeen = new java.util.concurrent.CopyOnWriteArrayList<String>();
            var stringDefinition = PluginDefinition.builder("string-null", String.class,
                () -> (context, config) -> {
                    stringSeen.add(config == null ? "<null>" : config);
                    return reactor.core.publisher.Mono.empty();
                }).build();
            var string = runtime.rootScope().context().plugins()
                .mount("string-null", stringDefinition.prepare("initial"));
            string.settled().block(TIMEOUT);
            string.update(null).block(TIMEOUT);
            assertEquals(java.util.List.of("initial", "<null>"), stringSeen);
            assertEquals(null, string.config());

            var voidStarts = new AtomicInteger();
            var voidDefinition = PluginDefinition.builder("void-null", Void.class,
                () -> (context, config) -> {
                    voidStarts.incrementAndGet();
                    return reactor.core.publisher.Mono.empty();
                }).build();
            var voidInstance = runtime.rootScope().context().plugins()
                .mount("void-null", voidDefinition.prepare(null));
            voidInstance.settled().block(TIMEOUT);
            voidInstance.update(null).block(TIMEOUT);
            assertEquals(2, voidStarts.get());

            var objectConfig = new AtomicReference<Object>();
            var objectDefinition = PluginDefinition.builder("object-null", Object.class,
                () -> (context, config) -> {
                    objectConfig.set(config);
                    return reactor.core.publisher.Mono.empty();
                }).build();
            var object = runtime.rootScope().context().plugins()
                .mount("object-null", objectDefinition.prepare("initial"));
            object.settled().block(TIMEOUT);
            object.update(null).block(TIMEOUT);
            assertEquals(null, objectConfig.get());
            assertEquals(null, object.config());
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
    void failedInstanceRecoversAfterPreparedUpdate() {
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

            instance.updatePrepared(definition.prepare("good")).block(TIMEOUT);
            assertEquals(PluginInstanceState.ACTIVE, instance.state());
            assertEquals(2, starts.get());
        }
    }

    @Test
    void activeStateObserverCanUpdateWithoutCorruptingEitherTransitionSignal() throws Exception {
        var firstStart = reactor.core.publisher.Sinks.<Void>one();
        var nextStart = reactor.core.publisher.Sinks.<Void>one();
        var updateStarted = new CountDownLatch(1);
        var updateResult = new AtomicReference<CompletableFuture<com.sstlfsj.fibra.PluginInstance<String>>>();
        var definition = PluginDefinition.builder("reentrant-active", String.class,
            () -> (context, config) -> "old".equals(config)
                ? firstStart.asMono() : nextStart.asMono()).build();

        try (var runtime = FibraRuntime.create()) {
            var instance = runtime.rootScope().context().plugins()
                .mount("reentrant-active", definition.prepare("old"));
            var firstResult = instance.settled().toFuture();
            var observation = instance.states()
                .filter(state -> state == PluginInstanceState.ACTIVE).take(1)
                .subscribe(ignored -> {
                    updateResult.set(instance.update("new").toFuture());
                    updateStarted.countDown();
                });
            try {
                firstStart.tryEmitEmpty();
                assertTrue(updateStarted.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                assertSame(instance, firstResult.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                assertFalse(updateResult.get().isDone());

                nextStart.tryEmitEmpty();
                assertSame(instance,
                    updateResult.get().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                assertEquals("new", instance.config());
                assertEquals(PluginInstanceState.ACTIVE, instance.state());
            } finally {
                firstStart.tryEmitEmpty();
                nextStart.tryEmitEmpty();
                observation.dispose();
            }
        }
    }

    @Test
    void failedStateObserverCanRecoverWithoutSendingOldFailureToTheNewTransition() throws Exception {
        var firstStart = reactor.core.publisher.Sinks.<Void>one();
        var nextStart = reactor.core.publisher.Sinks.<Void>one();
        var failure = new IllegalStateException("first start failed");
        var updateStarted = new CountDownLatch(1);
        var updateResult = new AtomicReference<CompletableFuture<com.sstlfsj.fibra.PluginInstance<String>>>();
        var definition = PluginDefinition.builder("reentrant-failed", String.class,
            () -> (context, config) -> "bad".equals(config)
                ? firstStart.asMono() : nextStart.asMono()).build();

        try (var runtime = FibraRuntime.create()) {
            var instance = runtime.rootScope().context().plugins()
                .mount("reentrant-failed", definition.prepare("bad"));
            var firstResult = instance.settled().toFuture();
            var observation = instance.states()
                .filter(state -> state == PluginInstanceState.FAILED).take(1)
                .subscribe(ignored -> {
                    updateResult.set(instance.update("good").toFuture());
                    updateStarted.countDown();
                });
            try {
                firstStart.tryEmitError(failure);
                assertTrue(updateStarted.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                var reported = assertThrows(ExecutionException.class,
                    () -> firstResult.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                assertSame(failure, reported.getCause());
                assertFalse(updateResult.get().isDone());

                nextStart.tryEmitEmpty();
                assertSame(instance,
                    updateResult.get().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                assertEquals("good", instance.config());
                assertEquals(PluginInstanceState.ACTIVE, instance.state());
            } finally {
                firstStart.tryEmitError(failure);
                nextStart.tryEmitEmpty();
                observation.dispose();
            }
        }
    }

    @Test
    void disposedInstanceRejectsPreparedUpdateWithoutApplyingIt() {
        var definition = PluginDefinition.builder("disposed-prepared", String.class,
            () -> (context, config) -> reactor.core.publisher.Mono.empty()).build();
        try (var runtime = FibraRuntime.create()) {
            var instance = runtime.rootScope().context().plugins()
                .mount("disposed-prepared", definition.prepare("initial"));
            instance.settled().block(TIMEOUT);
            instance.dispose().block(TIMEOUT);

            var failure = assertThrows(com.sstlfsj.fibra.FibraException.class,
                () -> instance.updatePrepared(definition.prepare("next")).block(TIMEOUT));

            assertEquals(com.sstlfsj.fibra.FibraException.PLUGIN_DISPOSED, failure.code());
            assertEquals("initial", instance.config());
        }
    }

    @Test
    void disposedInstanceRejectsRawUpdateBeforeRunningItsValidator() {
        var validations = new AtomicInteger();
        var definition = PluginDefinition.builder("disposed-raw", String.class,
            () -> (context, config) -> reactor.core.publisher.Mono.empty())
            .validator(config -> {
                validations.incrementAndGet();
                if ("next".equals(config)) {
                    throw new IllegalArgumentException("validator must not run after disposal");
                }
                return config;
            }).build();

        try (var runtime = FibraRuntime.create()) {
            var instance = runtime.rootScope().context().plugins()
                .mount("disposed-raw", definition.prepare("initial"));
            instance.settled().block(TIMEOUT);
            instance.dispose().block(TIMEOUT);

            var failure = assertThrows(com.sstlfsj.fibra.FibraException.class,
                () -> instance.update("next").block(TIMEOUT));

            assertEquals(com.sstlfsj.fibra.FibraException.PLUGIN_DISPOSED, failure.code());
            assertEquals(1, validations.get());
            assertEquals("initial", instance.config());
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
