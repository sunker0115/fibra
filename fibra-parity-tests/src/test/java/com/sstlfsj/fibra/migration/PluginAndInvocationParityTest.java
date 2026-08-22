package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.*;
import com.sstlfsj.fibra.runtime.FibraRuntime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginAndInvocationParityTest {
    private static final ServiceKey<DeferredApi> DEFERRED = ServiceKey.of("deferred", DeferredApi.class);
    private static final ServiceKey<EffectApi> EFFECT = ServiceKey.of("effect", EffectApi.class);

    private final Context context = FibraRuntime.create();

    @AfterEach
    void closeContext() {
        context.closeAsync().block();
    }

    @Test
    void constructorServiceIsInvisibleUntilItsStartPublisherCompletes() {
        var startGate = Sinks.<Void>one();
        var calls = new AtomicInteger();
        var consumer = context.plugin(PluginDescriptor.<Void>builder("consumer")
            .require(DEFERRED)
            .build(), (pluginContext, ignored) -> {
            calls.incrementAndGet();
            return Mono.empty();
        }, null);

        var provider = context.plugin(
            PluginDescriptor.<Void>builder("deferred-provider").provide(DEFERRED).build(),
            DeferredService::new,
            ignored -> startGate.asMono(),
            null
        );

        assertEquals(FibraState.LOADING, provider.state());
        assertEquals(FibraState.PENDING, consumer.state());
        startGate.tryEmitEmpty();
        provider.await().block();
        consumer.await().block();
        assertEquals(1, calls.get());
    }

    @Test
    void nestedPluginIsDisposedWithItsParent() {
        var childDisposed = new AtomicBoolean();
        var parent = context.plugin("parent", (parentContext, ignored) -> {
            parentContext.plugin("child", (childContext, childConfig) ->
                Mono.just(Disposables.from(() -> childDisposed.set(true))), null);
            return Mono.empty();
        }, null);

        parent.await().block();
        parent.dispose().block();

        assertTrue(childDisposed.get());
    }

    @Test
    void configValidationRunsOnInitialLoadAndUpdate() {
        var configs = new ArrayList<Integer>();
        var descriptor = PluginDescriptor.<Integer>builder("validated")
            .validator(value -> {
                if (value < 0) {
                    throw new IllegalArgumentException("negative");
                }
                return value * 2;
            })
            .build();
        var fibra = context.plugin(descriptor, (pluginContext, config) -> {
            configs.add(config);
            return Mono.empty();
        }, 2);

        fibra.await().block();
        fibra.update(3).block();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> fibra.update(-1));

        assertEquals(List.of(4, 6), configs);
        assertEquals(FibraState.ACTIVE, fibra.state());
    }

    @Test
    void invocationEffectsBelongToTheCallerFibra() {
        var disposed = new AtomicBoolean();
        context.provide(EFFECT, (invocation, flag) ->
            invocation.effect(() -> Disposables.from(() -> flag.set(true)), "service-effect"));
        var caller = context.plugin("caller", (callerContext, ignored) -> {
            callerContext.service(EFFECT).invoke((invocation, service) -> {
                service.register(invocation, disposed);
                return null;
            });
            return Mono.empty();
        }, null);

        caller.await().block();
        caller.dispose().block();

        assertTrue(disposed.get());
        assertFalse(context.fibra().effects().stream()
            .anyMatch(metadata -> metadata.label().equals("service-effect")));
    }

    @Test
    void registryGroupsFactoryPluginsByFactoryIdentityAndAwaitsRemoval() {
        PluginFactory<Void, DeferredService> factory = DeferredService::new;
        PluginInitializer<DeferredService> initializer = ignored -> Mono.empty();
        var descriptor = PluginDescriptor.<Void>builder("factory")
            .provide(DEFERRED)
            .build();

        var first = context.plugin(descriptor, factory, initializer, null);
        first.await().block();

        assertEquals(1, context.registry().size());
        assertTrue(context.registry().has(factory));
        assertEquals(List.of(first), context.registry().fibras(factory));

        context.registry().remove(factory).block();

        assertEquals(FibraState.DISPOSED, first.state());
        assertFalse(context.registry().has(factory));
        assertEquals(0, context.registry().size());
    }

    @FunctionalInterface
    interface DeferredApi {
        int value();
    }

    static final class DeferredService extends Service<DeferredApi> implements DeferredApi {
        DeferredService(Context context, Void ignored) {
            super(context, DEFERRED);
        }

        @Override
        public int value() {
            return 1;
        }
    }

    @FunctionalInterface
    interface EffectApi {
        EffectHandle register(InvocationContext invocation, AtomicBoolean disposed);
    }
}
