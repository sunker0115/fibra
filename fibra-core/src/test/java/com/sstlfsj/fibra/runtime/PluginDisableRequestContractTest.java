package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.DrainingDisposable;
import com.sstlfsj.fibra.FibraException;
import com.sstlfsj.fibra.ManagedPluginControl;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstance;
import com.sstlfsj.fibra.PluginInstanceState;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginDisableRequestContractTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void startingAndActivePluginRequestDisableTargetsTheExactCurrentInstance() {
        var requested = new AtomicReference<PluginInstance<?>>();
        var requestCount = new AtomicInteger();
        var definition = PluginDefinition.builder("request-disable", Void.class,
            () -> (context, config) -> {
                context.plugins().requestDisable();
                return Mono.empty();
            }).build();

        try (var runtime = FibraRuntime.create()) {
            runtime.rootScope().context().services().provide(ManagedPluginControl.KEY,
                instance -> {
                    requested.set(instance);
                    requestCount.incrementAndGet();
                });
            var instance = runtime.rootScope().context().plugins()
                .mount("request-disable-1", definition.prepare(null));
            instance.settled().block(TIMEOUT);

            assertSame(instance, requested.get());
            assertEquals(1, requestCount.get());
            instance.context().plugins().requestDisable();
            assertSame(instance, requested.get());
            assertEquals(2, requestCount.get());
        }
    }

    @Test
    void requestDisableRejectsCallsWithoutCurrentPluginOrControlPlane() {
        try (var runtime = FibraRuntime.create()) {
            var noPlugin = assertThrows(FibraException.class,
                () -> runtime.rootScope().context().plugins().requestDisable());
            assertEquals(FibraException.PLUGIN_DISABLE_UNAVAILABLE, noPlugin.code());

            var definition = PluginDefinition.builder("missing-control", Void.class,
                () -> (context, config) -> {
                    context.plugins().requestDisable();
                    return Mono.empty();
                }).build();
            var instance = runtime.rootScope().context().plugins()
                .mount("missing-control-1", definition.prepare(null));

            instance.settled().onErrorResume(error -> Mono.just(instance)).block(TIMEOUT);
            assertEquals(PluginInstanceState.FAILED, instance.state());
            assertEquals(FibraException.PLUGIN_DISABLE_UNAVAILABLE,
                ((FibraException) instance.failure().orElseThrow()).code());
        }
    }

    @Test
    void stoppingFailedAndDisposedPluginRequestsAreIgnored() {
        var requests = new AtomicInteger();
        var stopping = PluginDefinition.builder("stopping-request", Void.class,
            () -> (context, config) -> {
                context.effects().add(Disposables.from(() -> context.plugins().requestDisable()));
                return Mono.empty();
            }).build();
        var failing = PluginDefinition.builder("failed-request", Void.class,
            () -> (context, config) -> Mono.error(new IllegalStateException("failed"))).build();

        try (var runtime = FibraRuntime.create()) {
            runtime.rootScope().context().services().provide(ManagedPluginControl.KEY,
                ignored -> requests.incrementAndGet());
            var stoppingInstance = runtime.rootScope().context().plugins()
                .mount("stopping-request-1", stopping.prepare(null));
            stoppingInstance.settled().block(TIMEOUT);
            stoppingInstance.dispose().block(TIMEOUT);

            var failedInstance = runtime.rootScope().context().plugins()
                .mount("failed-request-1", failing.prepare(null));
            failedInstance.settled().onErrorResume(error -> Mono.just(failedInstance)).block(TIMEOUT);
            failedInstance.context().plugins().requestDisable();
            stoppingInstance.context().plugins().requestDisable();

            assertEquals(0, requests.get());
        }
    }

    @Test
    void scopeDrainDoesNotForwardAnActivePluginDisableRequest() throws Exception {
        var requests = new AtomicInteger();
        var drainEntered = reactor.core.publisher.Sinks.<Void>one();
        var releaseDrain = reactor.core.publisher.Sinks.<Void>one();
        var definition = PluginDefinition.builder("scope-drain-request", Void.class,
            () -> (context, config) -> {
                context.effects().add(new DrainingDisposable() {
                    @Override
                    public Mono<Void> drain() {
                        return Mono.defer(() -> {
                            context.plugins().requestDisable();
                            drainEntered.tryEmitEmpty();
                            return releaseDrain.asMono();
                        });
                    }

                    @Override
                    public Mono<Void> dispose() {
                        return Mono.empty();
                    }
                });
                return Mono.empty();
            }).build();

        try (var runtime = FibraRuntime.create()) {
            runtime.rootScope().context().services().provide(ManagedPluginControl.KEY,
                ignored -> requests.incrementAndGet());
            var scope = runtime.rootScope().openChild("draining-scope");
            scope.context().plugins().mount("scope-drain-request-1", definition.prepare(null))
                .settled().block(TIMEOUT);

            var closed = scope.closeAsync().toFuture();
            drainEntered.asMono().block(TIMEOUT);
            assertEquals(0, requests.get());
            releaseDrain.tryEmitEmpty();
            closed.get(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }
}
