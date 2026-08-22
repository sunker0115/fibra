package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Cordis packages/core/tests/fiber.spec.ts 的 8 项逐条映射；Java 名称固定为 Fibra。 */
class FibraSpecParityTest extends CordisSpecSupport {
    private static final ServiceKey<Value> FOO = ServiceKey.of("foo", Value.class);

    @Test
    void inertiaLock1() {
        var loadGate = Sinks.<Void>one();
        var unloadGate = Sinks.<Void>one();
        var registration = root.provide(FOO, new Value(1));
        var consumer = root.plugin(PluginDescriptor.<Void>builder("consumer").require(FOO).build(),
            (context, config) -> loadGate.asMono().thenReturn((Disposable) () -> unloadGate.asMono()), null);
        assertEquals(FibraState.LOADING, consumer.state());
        var removal = registration.dispose().toFuture();
        loadGate.tryEmitEmpty();
        org.awaitility.Awaitility.await().until(() -> consumer.state() == FibraState.UNLOADING);
        root.provide(FOO, new Value(2));
        unloadGate.tryEmitEmpty(); removal.join();
        await(consumer);
        assertEquals(FibraState.ACTIVE, consumer.state());
    }

    @Test
    void inertiaLock2() {
        var loadGate = Sinks.<Void>one();
        var first = root.provide(FOO, new Value(1));
        var loads = new AtomicInteger();
        var consumer = root.plugin(PluginDescriptor.<Void>builder("consumer").require(FOO).build(),
            (context, config) -> { loads.incrementAndGet(); return loadGate.asMono().thenMany(Mono.empty()); }, null);
        first.dispose().subscribe();
        root.provide(FOO, new Value(2));
        loadGate.tryEmitEmpty(); await(consumer);
        assertEquals(FibraState.ACTIVE, consumer.state());
        assertEquals(1, loads.get());
    }

    @Test
    void inertiaLock3() {
        var cleanupGate = Sinks.<Void>one();
        var provider = root.plugin(PluginDescriptor.<Void>builder("provider").provide(FOO).build(),
            (context, config) -> { context.provide(FOO, new Value(1)); return Mono.empty(); }, null);
        await(provider);
        var consumer = root.plugin(PluginDescriptor.<Void>builder("consumer").require(FOO).build(),
            (context, config) -> Mono.just((Disposable) () -> cleanupGate.asMono()), null);
        await(consumer);
        var disposed = provider.dispose().toFuture();
        assertEquals(FibraState.UNLOADING, consumer.state());
        cleanupGate.tryEmitEmpty(); disposed.join();
        assertEquals(FibraState.PENDING, consumer.state());
    }

    @Test
    void pluginError() {
        var calls = new AtomicInteger();
        Plugin<Boolean> plugin = (context, enabled) -> {
            if (!enabled) throw new IllegalStateException("plugin error");
            calls.incrementAndGet(); return Mono.empty();
        };
        var failed = root.plugin(PluginDescriptor.<Boolean>builder("failed").build(), plugin, false);
        var active = root.plugin(PluginDescriptor.<Boolean>builder("active").build(), plugin, true);
        assertThrows(RuntimeException.class, () -> await(failed));
        await(active);
        assertEquals(FibraState.FAILED, failed.state());
        assertEquals(FibraState.ACTIVE, active.state());
        assertEquals(1, calls.get());
    }

    @Test
    void disposeError() {
        var calls = new AtomicInteger();
        var fibra = root.plugin("dispose-error", (context, config) -> Mono.just(() ->
            Mono.fromRunnable(calls::incrementAndGet).then(Mono.error(new IllegalStateException("test")))), null);
        await(fibra);
        assertDoesNotThrow(() -> fibra.dispose().block());
        assertEquals(1, calls.get());
    }

    @Test
    void updateConfigOnWrappedFibra() {
        var configs = new ArrayList<String>();
        var fibra = root.plugin("config", (context, config) -> { configs.add(config); return Mono.empty(); }, "hello");
        await(fibra); fibra.update("world").block(); fibra.update("!!!").block();
        assertEquals(List.of("hello", "world", "!!!"), configs);
        assertEquals("!!!", fibra.config());
    }

    @Test
    void restartWrappedFibra() {
        var calls = new AtomicInteger();
        var fibra = root.plugin("restart", (context, config) -> { calls.incrementAndGet(); return Mono.empty(); }, null);
        await(fibra); fibra.restart().block();
        assertEquals(2, calls.get());
        assertEquals(FibraState.ACTIVE, fibra.state());
    }

    @Test
    void updateConfigWhileInjectedServiceReloads() {
        var applied = new CopyOnWriteArrayList<String>();
        var provider = root.plugin(PluginDescriptor.<Integer>builder("provider").provide(FOO).build(),
            (context, value) -> { context.provide(FOO, new Value(value)); return Mono.empty(); }, 1);
        var consumer = root.plugin(PluginDescriptor.<String>builder("consumer").require(FOO).build(),
            (context, mode) -> { applied.add(context.get(FOO).number() + ":" + mode); return Mono.empty(); }, "old");
        await(provider); await(consumer);
        provider.update(2);
        consumer.update("new");
        await(provider); await(consumer);
        assertEquals(List.of("1:old", "2:new"), applied);
        assertEquals(FibraState.ACTIVE, consumer.state());
    }

    record Value(int number) {}
}
