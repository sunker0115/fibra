package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.runtime.PluginUpdate;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FibraSpecParityTest extends CordisSpecSupport {
    private static final ServiceKey<Value> FOO = ServiceKey.of("foo", Value.class);

    @Test
    void inertiaLock1() {
        var loadGate = Sinks.<Void>one();
        var unloadGate = Sinks.<Void>one();
        var registration = root.services().provide(FOO, new Value(1));
        var definition = PluginDefinition.builder("consumer", Void.class,
                () -> (context, config) -> {
                    context.effects().add(unloadGate::asMono);
                    return loadGate.asMono();
                })
            .require(FOO).build();
        var consumer = root.plugins().mount("consumer", definition.prepare(null));
        assertEquals(PluginInstanceState.STARTING, consumer.state());
        var removal = registration.dispose().toFuture();
        loadGate.tryEmitEmpty();
        root.services().provide(FOO, new Value(2));
        unloadGate.tryEmitEmpty();
        removal.join();
        await(consumer);
        assertEquals(PluginInstanceState.ACTIVE, consumer.state());
    }

    @Test
    void inertiaLock2() {
        var loadGate = Sinks.<Void>one();
        var first = root.services().provide(FOO, new Value(1));
        var loads = new AtomicInteger();
        var definition = PluginDefinition.builder("consumer", Void.class,
                () -> (context, config) -> {
                    loads.incrementAndGet();
                    return loadGate.asMono();
                })
            .require(FOO).build();
        var consumer = root.plugins().mount("consumer", definition.prepare(null));
        first.dispose().subscribe();
        root.services().provide(FOO, new Value(2));
        loadGate.tryEmitEmpty();
        await(consumer);
        assertEquals(PluginInstanceState.ACTIVE, consumer.state());
        assertEquals(1, loads.get());
    }

    @Test
    void inertiaLock3() {
        var cleanupGate = Sinks.<Void>one();
        var providerDefinition = PluginDefinition.builder("provider", Void.class,
                () -> (context, config) -> {
                    context.services().provide(FOO, new Value(1));
                    return Mono.empty();
                })
            .provide(FOO).build();
        var provider = root.plugins().mount("provider", providerDefinition.prepare(null));
        await(provider);
        var consumerDefinition = PluginDefinition.builder("consumer", Void.class,
                () -> (context, config) -> {
                    context.effects().add(cleanupGate::asMono);
                    return Mono.empty();
                })
            .require(FOO).build();
        var consumer = root.plugins().mount("consumer", consumerDefinition.prepare(null));
        await(consumer);
        var disposed = provider.dispose().toFuture();
        assertEquals(PluginInstanceState.STOPPING, consumer.state());
        cleanupGate.tryEmitEmpty();
        disposed.join();
        assertEquals(PluginInstanceState.PENDING, consumer.state());
    }

    @Test
    void pluginError() {
        var calls = new AtomicInteger();
        Plugin<Boolean> plugin = (context, enabled) -> {
            if (!enabled) {
                throw new IllegalStateException("plugin error");
            }
            calls.incrementAndGet();
            return Mono.empty();
        };
        var definition = PluginDefinition.builder("sample", Boolean.class,
            () -> plugin).build();
        var failed = root.plugins().mount("failed", definition.prepare(false));
        var active = root.plugins().mount("active", definition.prepare(true));
        assertThrows(RuntimeException.class, () -> await(failed));
        await(active);
        assertEquals(PluginInstanceState.FAILED, failed.state());
        assertEquals(PluginInstanceState.ACTIVE, active.state());
        assertEquals(1, calls.get());
    }

    @Test
    void disposeError() {
        var calls = new AtomicInteger();
        var definition = PluginDefinition.builder("dispose-error", Void.class,
            () -> (context, config) -> {
                context.effects().add(() -> Mono.fromRunnable(calls::incrementAndGet)
                    .then(Mono.error(new IllegalStateException("test"))));
                return Mono.empty();
            }).build();
        var instance = root.plugins().mount("dispose-error", definition.prepare(null));
        await(instance);
        assertDoesNotThrow(() -> instance.dispose().block(TIMEOUT));
        assertEquals(1, calls.get());
    }

    @Test
    void updateConfigOnWrappedFibra() {
        var configs = new ArrayList<String>();
        var definition = PluginDefinition.builder("config", String.class,
            () -> (context, config) -> {
                configs.add(config);
                return Mono.empty();
            }).build();
        var instance = root.plugins().mount("config", definition.prepare("hello"));
        await(instance);
        instance.update("world").block(TIMEOUT);
        instance.update("!!!").block(TIMEOUT);
        assertEquals(List.of("hello", "world", "!!!"), configs);
        assertEquals("!!!", instance.config());
    }

    @Test
    void restartWrappedFibra() {
        var calls = new AtomicInteger();
        var definition = PluginDefinition.builder("restart", Void.class,
            () -> (context, config) -> {
                calls.incrementAndGet();
                return Mono.empty();
            }).build();
        var instance = root.plugins().mount("restart", definition.prepare(null));
        await(instance);
        instance.update(null).block(TIMEOUT);
        assertEquals(2, calls.get());
        assertEquals(PluginInstanceState.ACTIVE, instance.state());
    }

    @Test
    void updateConfigWhileInjectedServiceReloads() {
        var applied = new CopyOnWriteArrayList<String>();
        var domain = runtime.openDomain("batched-config-update");
        var scopedRoot = domain.rootScope().context();
        var providerDefinition = PluginDefinition.builder("provider", Integer.class,
            () -> (context, value) -> {
                context.services().provide(FOO, new Value(value));
                return Mono.empty();
            })
            .provide(FOO).build();
        var provider = scopedRoot.plugins().mount("provider", providerDefinition.prepare(1));
        var consumerDefinition = PluginDefinition.builder("consumer", String.class,
                () -> (context, mode) -> {
                    applied.add(context.services().require(FOO).number + ":" + mode);
                    return Mono.empty();
                })
            .require(FOO).build();
        var consumer = scopedRoot.plugins().mount("consumer", consumerDefinition.prepare("old"));
        await(provider);
        await(consumer);
        domain.updateBatch(PluginUpdate.config(provider, 2),
            PluginUpdate.config(consumer, "new")).block(TIMEOUT);
        assertEquals(List.of("1:old", "2:new"), applied);
        assertEquals(PluginInstanceState.ACTIVE, consumer.state());
    }

    private record Value(int number) {
    }
}
