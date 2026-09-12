package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.FibraException;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeDomainSettlementTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void updateBatchRegistersEveryTargetBeforeLifecycleConvergence() {
        var service = ServiceKey.of("value", Integer.class);
        var applied = new CopyOnWriteArrayList<String>();
        var providerDefinition = PluginDefinition.builder("provider", Integer.class,
            () -> (context, value) -> {
                context.services().provide(service, value);
                return Mono.empty();
            }).provide(service).build();
        var consumerDefinition = PluginDefinition.builder("consumer", String.class,
            () -> (context, mode) -> {
                applied.add(context.services().require(service) + ":" + mode);
                return Mono.empty();
            }).require(service).build();

        try (var runtime = FibraRuntime.create()) {
            var domain = runtime.openDomain("batched-update");
            var provider = domain.rootScope().context().plugins()
                .mount("provider", providerDefinition.prepare(1));
            var consumer = domain.rootScope().context().plugins()
                .mount("consumer", consumerDefinition.prepare("old"));
            domain.settled().block(TIMEOUT);

            domain.updateBatch(PluginUpdate.config(provider, 2),
                PluginUpdate.config(consumer, "new")).block(TIMEOUT);

            assertEquals(List.of("1:old", "2:new"), applied);
            assertEquals(PluginInstanceState.ACTIVE, consumer.state());
        }
    }

    @Test
    void updateBatchDistinguishesSettledPluginFailureFromRegistrationFailure() {
        var startupFailure = new IllegalStateException("runtime rejected config");
        var applied = new CopyOnWriteArrayList<String>();
        var failingDefinition = PluginDefinition.builder("failing", String.class,
            () -> (context, config) -> "bad".equals(config)
                ? Mono.error(startupFailure) : Mono.empty()).build();
        var healthyDefinition = PluginDefinition.builder("healthy", String.class,
            () -> (context, config) -> {
                applied.add(config);
                return Mono.empty();
            }).build();

        try (var runtime = FibraRuntime.create()) {
            var domain = runtime.openDomain("batched-failure");
            var failing = domain.rootScope().context().plugins()
                .mount("failing", failingDefinition.prepare("old"));
            var healthy = domain.rootScope().context().plugins()
                .mount("healthy", healthyDefinition.prepare("old"));
            domain.settled().block(TIMEOUT);

            var failure = assertThrows(FibraException.class, () -> domain.updateBatch(
                PluginUpdate.config(failing, "bad"),
                PluginUpdate.config(healthy, "new")).block(TIMEOUT));

            assertEquals(FibraException.PLUGIN_BATCH_UPDATE_FAILED, failure.code());
            assertSame(startupFailure, failure.getCause());
            assertEquals(PluginInstanceState.FAILED, failing.state());
            assertEquals("new", healthy.config());
            assertEquals(List.of("old", "new"), applied);
        }
    }

    @Test
    void updateBatchPrevalidatesEveryTargetBeforeApplyingAnyTarget() {
        var definition = PluginDefinition.builder("sample", String.class,
            () -> (context, config) -> Mono.empty()).build();

        try (var runtime = FibraRuntime.create()) {
            var domain = runtime.openDomain("batched-prevalidation");
            var first = domain.rootScope().context().plugins()
                .mount("first", definition.prepare("old-first"));
            var disposed = domain.rootScope().context().plugins()
                .mount("disposed", definition.prepare("old-disposed"));
            domain.settled().block(TIMEOUT);
            disposed.dispose().block(TIMEOUT);

            var failure = assertThrows(FibraException.class, () -> domain.updateBatch(
                PluginUpdate.config(first, "new-first"),
                PluginUpdate.config(disposed, "new-disposed")).block(TIMEOUT));

            assertEquals(FibraException.PLUGIN_DISPOSED, failure.code());
            assertEquals("old-first", first.config());
            assertEquals(PluginInstanceState.ACTIVE, first.state());
        }
    }

    @Test
    void updateBatchRejectsDuplicateInstanceBeforeApplyingEitherTarget() {
        var definition = PluginDefinition.builder("sample", String.class,
            () -> (context, config) -> Mono.empty()).build();

        try (var runtime = FibraRuntime.create()) {
            var domain = runtime.openDomain("batched-duplicate");
            var instance = domain.rootScope().context().plugins()
                .mount("sample", definition.prepare("old"));
            domain.settled().block(TIMEOUT);

            var failure = assertThrows(IllegalArgumentException.class,
                () -> domain.updateBatch(
                    PluginUpdate.config(instance, "first"),
                    PluginUpdate.config(instance, "second")).block(TIMEOUT));

            assertEquals("plugin update batch contains duplicate instance \"sample\"",
                failure.getMessage());
            assertEquals("old", instance.config());
            assertEquals(PluginInstanceState.ACTIVE, instance.state());
        }
    }

    @Test
    void pendingPluginsAreAlreadySettled() {
        var dependency = ServiceKey.of("dependency", String.class);
        var definition = PluginDefinition.builder("consumer", Void.class,
            () -> (context, config) -> Mono.empty()).require(dependency).build();
        try (var runtime = FibraRuntime.create()) {
            var domain = runtime.openDomain("pending");
            var instance = domain.rootScope().context().plugins().mount("consumer",
                definition.prepare(null));

            domain.settled().block(TIMEOUT);

            assertEquals(PluginInstanceState.PENDING, instance.state());
        }
    }

    @Test
    void failedTransitionWakesAnExistingDomainSettlementWaiter() throws Exception {
        var releaseStart = Sinks.<Void>one();
        var failure = new IllegalStateException("startup failed");
        var definition = PluginDefinition.builder("failing", Void.class,
            () -> (context, config) -> releaseStart.asMono()).build();

        try (var runtime = FibraRuntime.create()) {
            var domain = runtime.openDomain("failed-transition");
            var instance = domain.rootScope().context().plugins()
                .mount("failing", definition.prepare(null));
            var settled = domain.settled().toFuture();
            try {
                domain.snapshot();
                assertFalse(settled.isDone());

                releaseStart.tryEmitError(failure);

                settled.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                assertEquals(PluginInstanceState.FAILED, instance.state());
            } finally {
                releaseStart.tryEmitError(failure);
                settled.cancel(true);
            }
        }
    }

    @Test
    void pendingTransitionWakesAnExistingDomainSettlementWaiter() throws Exception {
        var dependency = ServiceKey.of("dependency", String.class);
        var registration = new AtomicReference<com.sstlfsj.fibra.ServiceRegistration<String>>();
        var releaseCleanup = Sinks.<Void>one();
        var providerDefinition = PluginDefinition.builder("provider", Void.class,
            () -> (context, config) -> {
                registration.set(context.services().provide(dependency, "ready"));
                return Mono.empty();
            }).provide(dependency).build();
        var consumerDefinition = PluginDefinition.builder("consumer", Void.class,
            () -> (context, config) -> {
                context.effects().add(() -> releaseCleanup.asMono());
                return Mono.empty();
            }).require(dependency).build();

        try (var runtime = FibraRuntime.create()) {
            var domain = runtime.openDomain("pending-transition");
            domain.rootScope().context().plugins()
                .mount("provider", providerDefinition.prepare(null));
            var consumer = domain.rootScope().context().plugins()
                .mount("consumer", consumerDefinition.prepare(null));
            domain.settled().block(TIMEOUT);
            var stopping = consumer.states()
                .filter(state -> state == PluginInstanceState.STOPPING).next().toFuture();
            var removal = registration.get().dispose().toFuture();
            stopping.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            var settled = domain.settled().toFuture();
            try {
                domain.snapshot();
                assertFalse(settled.isDone());

                releaseCleanup.tryEmitEmpty();

                settled.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                removal.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                assertEquals(PluginInstanceState.PENDING, consumer.state());
            } finally {
                releaseCleanup.tryEmitEmpty();
                settled.cancel(true);
            }
        }
    }

    @Test
    void waitsForDynamicChildrenThatAreStillTransitioning() throws Exception {
        var releaseChild = Sinks.<Void>one();
        var childReference = new AtomicReference<com.sstlfsj.fibra.PluginInstance<Void>>();
        var child = PluginDefinition.builder("child", Void.class,
            () -> (context, config) -> releaseChild.asMono()).build();
        var parent = PluginDefinition.builder("parent", Void.class,
            () -> (context, config) -> {
                childReference.set(context.plugins().mount("child", child.prepare(null)));
                return Mono.empty();
            }).build();
        try (var runtime = FibraRuntime.create()) {
            var domain = runtime.openDomain("dynamic");
            var parentInstance = domain.rootScope().context().plugins()
                .mount("parent", parent.prepare(null));
            parentInstance.settled().block(TIMEOUT);
            var childInstance = childReference.get();
            var settled = domain.settled().toFuture();

            assertFalse(settled.isDone());
            releaseChild.tryEmitEmpty();
            settled.get(3, TimeUnit.SECONDS);
            assertEquals(PluginInstanceState.ACTIVE, childInstance.state());
        }
    }
}
