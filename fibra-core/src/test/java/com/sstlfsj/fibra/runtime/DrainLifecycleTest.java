package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.DrainingDisposable;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DrainLifecycleTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void scopeWaitsForEveryDrainBeforeStartingOrdinaryEffects() throws Exception {
        var gate = Sinks.<Void>one();
        var drained = new AtomicInteger();
        var released = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var scope = runtime.rootScope().openChild("owner");
            scope.context().effects().add(resource(gate.asMono().doOnSubscribe(ignored -> drained.incrementAndGet()), released));
            scope.context().effects().add(() -> Mono.fromRunnable(released::incrementAndGet));
            var closing = scope.closeAsync().toFuture();
            try {
                runtime.rootScope().context().plugins().instances();
                assertEquals(0, released.get());
                assertFalse(closing.isDone());
            } finally {
                gate.tryEmitEmpty();
            }
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(1, drained.get());
            assertEquals(2, released.get());
        }
    }

    @Test
    void lateAsyncDisposerJoinsTheSameDrainBarrier() throws Exception {
        var source = Sinks.<Disposable>one();
        var gate = Sinks.<Void>one();
        var released = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var scope = runtime.rootScope().openChild("owner");
            scope.context().effects().collect(source.asMono());
            scope.context().effects().add(() -> Mono.fromRunnable(released::incrementAndGet));
            var closing = scope.closeAsync().toFuture();
            try {
                runtime.rootScope().context().plugins().instances();
                assertEquals(0, released.get());
                source.tryEmitValue(resource(gate.asMono(), released));
                runtime.rootScope().context().plugins().instances();
                assertEquals(0, released.get());
            } finally {
                source.tryEmitValue(resource(gate.asMono(), released));
                gate.tryEmitEmpty();
            }
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(2, released.get());
        }
    }

    @Test
    void explicitGroupedEffectDisposalDrainsBeforeItsOrdinaryDisposers() throws Exception {
        var gate = Sinks.<Void>one();
        var released = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var effects = runtime.rootScope().context().effects();
            var grouped = effects.collect(reactor.core.publisher.Flux.just(
                resource(gate.asMono(), released), (Disposable) () -> Mono.fromRunnable(released::incrementAndGet)));
            grouped.ready().block(TIMEOUT);
            var closing = grouped.dispose().toFuture();
            try {
                runtime.rootScope().context().plugins().instances();
                assertEquals(0, released.get());
            } finally {
                gate.tryEmitEmpty();
            }
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(2, released.get());
        }
    }

    @Test
    void explicitDisposalDrainsCollectedValuesBeforeTheSourceCompletes() throws Exception {
        var tail = Sinks.<Disposable>one();
        var gate = Sinks.<Void>one();
        var draining = new AtomicInteger();
        var released = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var effect = runtime.rootScope().context().effects().collect(
                reactor.core.publisher.Flux.concat(Mono.just(resource(
                    gate.asMono().doOnSubscribe(ignored -> draining.incrementAndGet()), released)),
                    tail.asMono()));
            var closing = effect.dispose().toFuture();
            try {
                runtime.rootScope().context().plugins().instances();
                assertEquals(1, draining.get());
                assertEquals(0, released.get());
                assertFalse(closing.isDone());
            } finally {
                tail.tryEmitEmpty();
                gate.tryEmitEmpty();
            }
            closing.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void parentDisposalCompletesWhenItsStartingChildFails() throws Exception {
        var startup = Sinks.<Void>one();
        var childDefinition = PluginDefinition.builder("starting-child", Void.class,
            () -> (context, config) -> startup.asMono()).build();
        var parentDefinition = PluginDefinition.builder("parent", Void.class,
            () -> (context, config) -> {
                context.plugins().mount("starting-child", childDefinition.prepare(null));
                return Mono.empty();
            }).build();
        var runtime = FibraRuntime.create();
        try {
            var plugins = runtime.rootScope().context().plugins();
            var parent = plugins.mount("parent", parentDefinition.prepare(null));
            parent.settled().block(TIMEOUT);
            var child = plugins.find("starting-child").orElseThrow();
            assertEquals(PluginInstanceState.STARTING, child.state());
            var closing = parent.dispose().toFuture();
            plugins.instances();
            startup.tryEmitError(new IllegalStateException("startup failed after parent stopped"));
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(PluginInstanceState.DISPOSED, child.state());
            assertEquals(PluginInstanceState.DISPOSED, parent.state());
        } finally {
            startup.tryEmitError(new IllegalStateException("test cleanup"));
            runtime.closeAsync().subscribe(ignored -> { }, ignored -> { });
        }
    }

    @Test
    void providerWaitsForOldConsumerCleanupButNotItsNextActivation() throws Exception {
        var service = ServiceKey.of("service", String.class);
        var oldConsumerCleanup = Sinks.<Void>one();
        var nextConsumerStart = Sinks.<Void>one();
        var consumerStarts = new AtomicInteger();
        var providerReleases = new AtomicInteger();
        var providerDefinition = PluginDefinition.builder("provider", Integer.class,
            () -> (context, config) -> {
                context.services().provide(service, "value");
                context.effects().add(() -> Mono.fromRunnable(providerReleases::incrementAndGet));
                return Mono.empty();
            }).provide(service).build();
        var consumerDefinition = PluginDefinition.builder("consumer", Void.class,
            () -> (context, config) -> {
                var activation = consumerStarts.incrementAndGet();
                context.effects().add(() -> activation == 1 ? oldConsumerCleanup.asMono() : Mono.empty());
                return activation == 1 ? Mono.empty() : nextConsumerStart.asMono();
            }).require(service).build();
        try (var runtime = FibraRuntime.create()) {
            var plugins = runtime.rootScope().context().plugins();
            var provider = plugins.mount("provider", providerDefinition.prepare(1));
            provider.settled().block(TIMEOUT);
            var consumer = plugins.mount("consumer", consumerDefinition.prepare(null));
            consumer.settled().block(TIMEOUT);
            var updating = provider.update(2).toFuture();
            try {
                plugins.instances();
                assertEquals(0, providerReleases.get());
                oldConsumerCleanup.tryEmitEmpty();
                updating.get(5, TimeUnit.SECONDS);
                assertEquals(1, providerReleases.get());
                assertEquals(PluginInstanceState.ACTIVE, provider.state());
            } finally {
                oldConsumerCleanup.tryEmitEmpty();
                nextConsumerStart.tryEmitEmpty();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void drainCallbackCannotStartSiblingCleanupBeforeTheWholeOwnerIsFrozen(boolean lateOwnership) throws Exception {
        var service = ServiceKey.of("drain-reentry", String.class);
        var gate = Sinks.<Void>one();
        var siblingReleases = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            var registration = context.services().provide(service, "value");
            var firstDefinition = PluginDefinition.builder("first-child", Void.class,
                () -> (childContext, config) -> {
                    childContext.effects().add(resource(Mono.defer(() -> {
                        registration.dispose().subscribe(ignored -> { }, ignored -> { });
                        return gate.asMono();
                    }), new AtomicInteger()));
                    return Mono.empty();
                }).build();
            var siblingDefinition = PluginDefinition.builder("sibling-child", Void.class,
                () -> (childContext, config) -> {
                    childContext.effects().add(() -> Mono.fromRunnable(siblingReleases::incrementAndGet));
                    return Mono.empty();
                }).require(service).build();
            var parentDefinition = PluginDefinition.builder("reentrant-parent", Void.class,
                () -> (parentContext, config) -> {
                    parentContext.plugins().mount("first-child", firstDefinition.prepare(null));
                    parentContext.plugins().mount("sibling-child", siblingDefinition.prepare(null));
                    return Mono.empty();
                }).build();
            var parent = context.plugins().mount("reentrant-parent", parentDefinition.prepare(null));
            parent.settled().block(TIMEOUT);
            context.plugins().find("first-child").orElseThrow().settled().block(TIMEOUT);
            context.plugins().find("sibling-child").orElseThrow().settled().block(TIMEOUT);
            java.util.concurrent.CompletableFuture<Void> closing;
            if (lateOwnership) {
                var source = Sinks.<Disposable>one();
                var owner = runtime.rootScope().openChild("late-owner");
                owner.context().effects().collect(source.asMono());
                closing = owner.closeAsync().toFuture();
                source.tryEmitValue(parent);
            } else {
                closing = parent.dispose().toFuture();
            }
            try {
                context.plugins().instances();
                assertEquals(0, siblingReleases.get());
                assertFalse(closing.isDone());
            } finally {
                gate.tryEmitEmpty();
            }
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(1, siblingReleases.get());
        }
    }

    @Test
    void oldHealthFailureDuringDrainCannotReenterTheStopTransition() {
        var starts = new AtomicInteger();
        var definition = PluginDefinition.builder("draining-health", Integer.class,
            () -> (context, config) -> {
                starts.incrementAndGet();
                var health = Sinks.<Void>one();
                context.effects().supervise(health.asMono(), "old-health");
                context.effects().add(resource(Mono.fromRunnable(() ->
                    health.tryEmitError(new IllegalStateException("old health stopped during drain"))),
                    new AtomicInteger()));
                return Mono.empty();
            }).build();
        var runtime = FibraRuntime.create();
        try {
            var plugins = runtime.rootScope().context().plugins();
            var instance = plugins.mount("draining-health", definition.prepare(1));
            instance.settled().block(TIMEOUT);
            instance.update(2).block(TIMEOUT);
            plugins.instances();
            assertEquals(2, starts.get());
            assertEquals(PluginInstanceState.ACTIVE, instance.state());
            assertTrue(instance.failure().isEmpty());
        } finally {
            runtime.closeAsync().subscribe(ignored -> { }, ignored -> { });
        }
    }

    @Test
    void failedDisposedConsumerStillPinsItsProviderOnALaterOperation() {
        var service = ServiceKey.of("retained-service", String.class);
        var providerReleases = new AtomicInteger();
        var providerDefinition = PluginDefinition.builder("retained-provider", Void.class,
            () -> (context, config) -> {
                context.services().provide(service, "value");
                context.effects().add(() -> Mono.fromRunnable(providerReleases::incrementAndGet));
                return Mono.empty();
            }).provide(service).build();
        var consumerDefinition = PluginDefinition.builder("failed-consumer", Void.class,
            () -> (context, config) -> {
                context.effects().add(() -> Mono.error(new IllegalStateException("consumer cleanup failed")));
                return Mono.empty();
            }).require(service).build();
        var runtime = FibraRuntime.create();
        try {
            var plugins = runtime.rootScope().context().plugins();
            var provider = plugins.mount("retained-provider", providerDefinition.prepare(null));
            provider.settled().block(TIMEOUT);
            var consumer = plugins.mount("failed-consumer", consumerDefinition.prepare(null));
            consumer.settled().block(TIMEOUT);
            consumer.dispose().block(TIMEOUT);
            assertEquals(PluginInstanceState.DISPOSED, consumer.state());
            assertTrue(plugins.find("failed-consumer").isEmpty());
            assertThrows(RuntimeException.class, () -> provider.dispose().block(TIMEOUT));
            assertEquals(0, providerReleases.get());
            assertEquals(PluginInstanceState.FAILED, provider.state());
        } finally {
            runtime.closeAsync().onErrorResume(ignored -> Mono.empty()).block(TIMEOUT);
        }
    }

    @Test
    void childProviderPrerequisiteFailureRetainsItsOwningParent() {
        var service = ServiceKey.of("child-service", String.class);
        var providerReleases = new AtomicInteger();
        var providerDefinition = PluginDefinition.builder("child-provider", Void.class,
            () -> (context, config) -> {
                context.services().provide(service, "value");
                context.effects().add(() -> Mono.fromRunnable(providerReleases::incrementAndGet));
                return Mono.empty();
            }).provide(service).build();
        var parentDefinition = PluginDefinition.builder("owning-parent", Void.class,
            () -> (context, config) -> {
                context.plugins().mount("child-provider", providerDefinition.prepare(null));
                return Mono.empty();
            }).build();
        var consumerDefinition = PluginDefinition.builder("failed-child-consumer", Void.class,
            () -> (context, config) -> {
                context.effects().add(() -> Mono.error(new IllegalStateException("consumer cleanup failed")));
                return Mono.empty();
            }).require(service).build();
        var runtime = FibraRuntime.create();
        var domain = runtime.openDomain("owned-prerequisite");
        try {
            var plugins = domain.rootScope().context().plugins();
            var parent = plugins.mount("owning-parent", parentDefinition.prepare(null));
            parent.settled().block(TIMEOUT);
            var provider = plugins.find("child-provider").orElseThrow();
            provider.settled().block(TIMEOUT);
            var consumer = plugins.mount("failed-child-consumer", consumerDefinition.prepare(null));
            consumer.settled().block(TIMEOUT);
            consumer.dispose().block(TIMEOUT);
            assertEquals(PluginInstanceState.DISPOSED, consumer.state());
            assertThrows(RuntimeException.class, () -> parent.dispose().block(TIMEOUT));
            assertEquals(0, providerReleases.get());
            assertEquals(PluginInstanceState.FAILED, provider.state());
            assertEquals(PluginInstanceState.FAILED, parent.state());
            assertTrue(domain.snapshot().cleanupFailures().stream()
                .anyMatch(failure -> failure.ownerIdentity() == parent.identity()));
        } finally {
            runtime.closeAsync().onErrorResume(ignored -> Mono.empty()).block(TIMEOUT);
        }
    }

    @Test
    void failedDrainRetainsTheResourceAndDoesNotRunOrdinaryCleanup() {
        var released = new AtomicInteger();
        var runtime = FibraRuntime.create();
        var domain = runtime.openDomain("drain-failure");
        var scope = domain.rootScope().openChild("owner");
        scope.context().effects().add(resource(Mono.error(new IllegalStateException("drain failed")), released));
        scope.context().effects().add(() -> Mono.fromRunnable(released::incrementAndGet));
        try {
            assertThrows(RuntimeException.class, () -> scope.closeAsync().block(TIMEOUT));
            assertFalse(scope.isClosed());
            assertEquals(0, released.get());
            assertFalse(domain.snapshot().cleanupFailures().isEmpty());
        } finally {
            runtime.closeAsync().onErrorResume(ignored -> Mono.empty()).block(TIMEOUT);
        }
    }

    private static DrainingDisposable resource(Mono<Void> drain, AtomicInteger released) {
        return new DrainingDisposable() {
            @Override public Mono<Void> drain() { return drain; }
            @Override public Mono<Void> dispose() { return Mono.fromRunnable(released::incrementAndGet); }
        };
    }
}
