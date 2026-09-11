package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.EventMode;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginSpecParityTest extends CordisSpecSupport {
    private static final EventKey<Signal> EVENT = EventKey.of(
        "test/plugin", Signal.class, EventMode.EMIT);

    @Test
    void applyFunctionalPlugin() {
        var observed = new AtomicReference<String>();
        var definition = PluginDefinition.builder("functional", String.class,
            () -> (context, config) -> {
                observed.set(config);
                return Mono.empty();
            }).build();
        await(root.plugins().mount("functional", definition.prepare("bar")));
        assertEquals("bar", observed.get());
    }

    @Test
    void applyObjectPlugin() {
        var definition = PluginDefinition.builder("object", String.class,
            ObjectPlugin::new).build();
        var instance = root.plugins().mount("object", definition.prepare("foo"));
        await(instance);
        assertEquals("foo", instance.config());
    }

    @Test
    void applyInvalidPlugin() {
        assertThrows(NullPointerException.class, () ->
            PluginDefinition.builder("invalid", Void.class, null));
        assertThrows(IllegalArgumentException.class, () ->
            PluginDefinition.builder(" ", Void.class,
                () -> (context, config) -> Mono.empty()));
    }

    @Test
    void inactiveContext() {
        var attempts = new AtomicInteger();
        var definition = PluginDefinition.builder("owner", Void.class,
            () -> (context, config) -> {
                context.effects().add(Disposables.from(() -> {
                    assertThrows(IllegalStateException.class,
                        () -> context.effects().effect(Disposables::noop));
                    assertThrows(IllegalStateException.class,
                        () -> context.events().on(EVENT, attempts::incrementAndGet));
                }));
                return Mono.empty();
            }).build();
        var instance = root.plugins().mount("owner", definition.prepare(null));
        await(instance);
        instance.dispose().block(TIMEOUT);
        assertEquals(0, attempts.get());
    }

    @Test
    void contextInspect() {
        var observed = new AtomicReference<String>();
        var definition = PluginDefinition.builder("named", Void.class,
            () -> (context, config) -> {
                observed.set(context.plugins().current().orElseThrow().id());
                return Mono.empty();
            }).build();
        await(root.plugins().mount("named", definition.prepare(null)));
        assertEquals("named", observed.get());
        assertTrue(root.plugins().current().isEmpty());
    }

    @Test
    void ctxRegistry() {
        var definition = PluginDefinition.builder("registry", Void.class,
            () -> (context, config) -> Mono.empty()).build();
        var instance = root.plugins().mount("registry", definition.prepare(null));
        assertEquals(1, root.plugins().instances().size());
        assertSameInstance(instance);
    }

    @Test
    void nestedPlugins() {
        var calls = new AtomicInteger();
        root.events().on(EVENT, calls::incrementAndGet);
        var parentDefinition = PluginDefinition.builder("parent", Void.class,
            () -> (parent, config) -> {
                parent.events().on(EVENT, calls::incrementAndGet);
                var childDefinition = PluginDefinition.builder("child", Void.class,
                    () -> (child, ignored) -> {
                        child.events().on(EVENT, calls::incrementAndGet);
                        return Mono.empty();
                    }).build();
                parent.plugins().mount("child", childDefinition.prepare(null));
                return Mono.empty();
            }).build();
        var parent = root.plugins().mount("parent", parentDefinition.prepare(null));
        await(parent);
        root.events().emit(EVENT, Signal::call);
        assertEquals(3, calls.get());
        parent.dispose().block(TIMEOUT);
        root.events().emit(EVENT, Signal::call);
        assertEquals(4, calls.get());
        assertTrue(root.plugins().instances().isEmpty());
    }

    @Test
    void compareSnapshot() {
        var definition = PluginDefinition.builder("snapshot", Void.class,
            () -> (context, config) -> {
                context.events().on(EVENT, () -> { });
                return Mono.empty();
            }).build();
        var first = root.plugins().mount("first", definition.prepare(null));
        await(first);
        assertEquals(1, root.plugins().instances().size());
        first.dispose().block(TIMEOUT);
        assertTrue(root.plugins().instances().isEmpty());
        var second = root.plugins().mount("second", definition.prepare(null));
        await(second);
        assertEquals(1, root.plugins().instances().size());
    }

    @Test
    void rootDispose() {
        var disposed = new AtomicInteger();
        var definition = PluginDefinition.builder("child", Void.class,
            () -> (context, config) -> {
                context.effects().add(Disposables.from(disposed::incrementAndGet));
                return Mono.empty();
            }).build();
        var child = root.plugins().mount("child", definition.prepare(null));
        await(child);
        runtime.closeAsync().block(TIMEOUT);
        assertEquals(PluginInstanceState.DISPOSED, child.state());
        assertEquals(1, disposed.get());
        runtime.closeAsync().block(TIMEOUT);
        assertEquals(1, disposed.get());
    }

    @Test
    void serviceInit() {
        var started = new AtomicBoolean();
        var stopped = new AtomicBoolean();
        var definition = PluginDefinition.builder("class-plugin", Void.class,
            () -> (context, config) -> {
                started.set(true);
                context.effects().add(Disposables.from(() -> stopped.set(true)));
                return Mono.empty();
            }).build();
        var instance = root.plugins().mount("class-plugin", definition.prepare(null));
        await(instance);
        assertTrue(started.get());
        assertFalse(stopped.get());
        instance.dispose().block(TIMEOUT);
        assertTrue(stopped.get());
    }

    private void assertSameInstance(com.sstlfsj.fibra.PluginInstance<?> instance) {
        assertEquals(instance, root.plugins().find("registry").orElseThrow());
        assertTrue(root.plugins().instances().contains(instance));
    }

    @FunctionalInterface
    private interface Signal {
        void call();
    }

    private static final class ObjectPlugin implements Plugin<String> {
        @Override
        public Mono<Void> start(com.sstlfsj.fibra.Context context, String config) {
            return Mono.empty();
        }
    }
}
