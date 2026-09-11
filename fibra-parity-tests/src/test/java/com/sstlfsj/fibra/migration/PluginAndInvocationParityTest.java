package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.EffectHandle;
import com.sstlfsj.fibra.FibraException;
import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginAndInvocationParityTest {
    private static final ServiceKey<DeferredApi> DEFERRED =
        ServiceKey.of("deferred", DeferredApi.class);
    private static final ServiceKey<EffectApi> EFFECT =
        ServiceKey.of("effect", EffectApi.class);

    @Test
    void constructorServiceIsInvisibleUntilItsStartPublisherCompletes() {
        var startGate = Sinks.<Void>one();
        var calls = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            var consumerDefinition = PluginDefinition.builder("consumer", Void.class,
                    () -> (pluginContext, ignored) -> {
                        calls.incrementAndGet();
                        return Mono.empty();
                    })
                .require(DEFERRED)
                .build();
            var consumer = context.plugins().mount("consumer", consumerDefinition.prepare(null));
            var providerDefinition = PluginDefinition.builder("deferred-provider", Void.class,
                    () -> (pluginContext, ignored) -> {
                        pluginContext.services().provide(DEFERRED, () -> 1);
                        return startGate.asMono();
                    })
                .provide(DEFERRED)
                .build();
            var provider = context.plugins().mount(
                "deferred-provider", providerDefinition.prepare(null));

            assertEquals(PluginInstanceState.STARTING, provider.state());
            assertEquals(PluginInstanceState.PENDING, consumer.state());
            assertTrue(context.services().find(DEFERRED).isEmpty());
            startGate.tryEmitEmpty();
            provider.settled().block();
            consumer.settled().block();

            assertEquals(1, calls.get());
        }
    }

    @Test
    void nestedPluginIsDisposedWithItsParent() {
        var childDisposed = new AtomicBoolean();
        try (var runtime = FibraRuntime.create()) {
            var childDefinition = PluginDefinition.builder("child", Void.class,
                () -> (childContext, ignored) -> {
                    childContext.effects().add(
                        Disposables.from(() -> childDisposed.set(true)));
                    return Mono.empty();
                }).build();
            var parentDefinition = PluginDefinition.builder("parent", Void.class,
                () -> (parentContext, ignored) -> {
                    parentContext.plugins().mount("child", childDefinition.prepare(null));
                    return Mono.empty();
                }).build();
            var parent = runtime.rootScope().context().plugins()
                .mount("parent", parentDefinition.prepare(null));

            parent.settled().block();
            parent.dispose().block();

            assertTrue(childDisposed.get());
        }
    }

    @Test
    void configValidationRunsOnInitialLoadAndUpdate() {
        var configs = new ArrayList<Integer>();
        try (var runtime = FibraRuntime.create()) {
            var definition = PluginDefinition.builder("validated", Integer.class,
                    () -> (pluginContext, config) -> {
                        configs.add(config);
                        return Mono.empty();
                    })
                .validator(value -> {
                    if (value < 0) {
                        throw new IllegalArgumentException("negative");
                    }
                    return value * 2;
                })
                .build();
            var plugin = runtime.rootScope().context().plugins()
                .mount("validated", definition.prepare(2));

            plugin.settled().block();
            plugin.update(3).block();
            assertThrows(IllegalArgumentException.class,
                () -> plugin.update(-1).block());

            assertEquals(List.of(4, 6), configs);
            assertEquals(PluginInstanceState.ACTIVE, plugin.state());
        }
    }

    @Test
    void invocationEffectsBelongToTheCallerFibra() {
        var disposed = new AtomicBoolean();
        var registered = new AtomicReference<EffectHandle>();
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            context.services().provide(EFFECT, (invocation, flag) -> {
                var handle = invocation.effects().add(
                    Disposables.from(() -> flag.set(true)));
                registered.set(handle);
                return handle;
            });
            var definition = PluginDefinition.builder("caller", Void.class,
                () -> (callerContext, ignored) -> {
                    callerContext.services().reference(EFFECT)
                        .invoke((invocation, service) ->
                            service.register(invocation, disposed));
                    return Mono.empty();
                }).build();
            var caller = context.plugins().mount("caller", definition.prepare(null));

            caller.settled().block();
            caller.dispose().block();

            assertTrue(disposed.get());
            assertTrue(registered.get().isDisposed());
        }
    }

    @Test
    void registryGroupsFactoryPluginsByFactoryIdentityAndAwaitsRemoval() {
        try (var runtime = FibraRuntime.create()) {
            var plugins = runtime.rootScope().context().plugins();
            var definition = PluginDefinition.builder("factory", Void.class,
                () -> (context, config) -> Mono.empty()).build();
            var first = plugins.mount("factory-1", definition.prepare(null));
            first.settled().block();

            assertEquals(List.of(first), plugins.instances());
            assertEquals(first, plugins.find("factory-1").orElseThrow());
            var duplicate = assertThrows(FibraException.class,
                () -> plugins.mount("factory-1", definition.prepare(null)));
            assertEquals(FibraException.PLUGIN_DUPLICATE, duplicate.code());

            first.dispose().block();

            assertEquals(PluginInstanceState.DISPOSED, first.state());
            assertFalse(plugins.find("factory-1").isPresent());
            assertEquals(0, plugins.instances().size());
        }
    }

    @FunctionalInterface
    private interface DeferredApi {
        int value();
    }

    @FunctionalInterface
    private interface EffectApi {
        EffectHandle register(InvocationContext invocation, AtomicBoolean disposed);
    }
}
