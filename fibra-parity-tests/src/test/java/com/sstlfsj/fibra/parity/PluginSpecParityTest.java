package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.*;
import com.sstlfsj.fibra.event.EventKey;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Cordis packages/core/tests/plugin.spec.ts 的 10 项逐条映射。 */
class PluginSpecParityTest extends CordisSpecSupport {
    private static final EventKey<Signal> EVENT = EventKey.of("test/plugin", Signal.class);

    @Test
    void applyFunctionalPlugin() {
        var observed = new AtomicReference<String>();
        var fibra = root.plugin("functional", (context, config) -> {
            observed.set(config); return Mono.empty();
        }, "bar");
        await(fibra);
        assertEquals("bar", observed.get());
    }

    @Test
    void applyObjectPlugin() {
        Plugin<String> plugin = new ObjectPlugin();
        var fibra = root.plugin(PluginDescriptor.<String>builder("object").build(), plugin, "foo");
        await(fibra);
        assertEquals("foo", fibra.config());
    }

    @Test
    void applyInvalidPlugin() {
        var descriptor = PluginDescriptor.<Void>builder("invalid").build();
        assertThrows(NullPointerException.class, () -> root.plugin(descriptor, (Plugin<Void>) null, null));
        assertThrows(IllegalArgumentException.class, () -> PluginDescriptor.builder(" "));
    }

    @Test
    void inactiveContext() {
        var attempts = new AtomicInteger();
        var fibra = root.plugin("owner", (context, config) -> Mono.just(() -> Mono.fromRunnable(() -> {
            assertThrows(IllegalStateException.class,
                () -> context.effect(() -> Disposables.noop()));
            assertThrows(IllegalStateException.class,
                () -> context.on(EVENT, attempts::incrementAndGet));
        })), null);
        await(fibra);
        fibra.dispose().block();
        assertEquals(0, attempts.get());
    }

    @Test
    void contextInspect() {
        var observed = new AtomicReference<String>();
        var fibra = root.plugin("named", (context, config) -> {
            observed.set(context.fibra().name()); return Mono.empty();
        }, null);
        await(fibra);
        assertEquals("named", observed.get());
        assertEquals("root", root.fibra().name());
    }

    @Test
    void ctxRegistry() {
        Plugin<Void> plugin = (context, config) -> Mono.empty();
        root.plugin(PluginDescriptor.<Void>builder("registry").build(), plugin, null);
        assertEquals(1, root.registry().size());
        assertEquals(1, root.registry().keys().size());
        assertEquals(1, root.registry().values().size());
        assertEquals(1, root.registry().entries().size());
        assertTrue(root.registry().has(plugin));
    }

    @Test
    void nestedPlugins() {
        var calls = new AtomicInteger();
        root.on(EVENT, calls::incrementAndGet);
        var parent = root.plugin("parent", (parentContext, config) -> {
            parentContext.on(EVENT, calls::incrementAndGet);
            parentContext.plugin("child", (childContext, ignored) -> {
                childContext.on(EVENT, calls::incrementAndGet);
                return Mono.empty();
            }, null);
            return Mono.empty();
        }, null);
        await(parent);
        root.emit(EVENT, Signal::call);
        assertEquals(3, calls.get());
        parent.dispose().block();
        root.emit(EVENT, Signal::call);
        assertEquals(4, calls.get());
        assertEquals(0, root.registry().size());
    }

    @Test
    void compareSnapshot() {
        Plugin<Void> plugin = (context, config) -> {
            context.on(EVENT, () -> {}); return Mono.empty();
        };
        var first = root.plugin(PluginDescriptor.<Void>builder("snapshot").build(), plugin, null);
        await(first);
        assertEquals(1, root.registry().fibras(plugin).size());
        root.registry().remove(plugin).block();
        assertEquals(0, root.registry().size());
        var second = root.plugin(PluginDescriptor.<Void>builder("snapshot").build(), plugin, null);
        await(second);
        assertEquals(1, root.registry().fibras(plugin).size());
    }

    @Test
    void rootDispose() {
        var disposed = new AtomicInteger();
        var child = root.plugin("child", (context, config) ->
            Mono.just(Disposables.from(disposed::incrementAndGet)), null);
        await(child);
        root.fibra().dispose().block();
        assertEquals(0L, root.fibra().uid());
        assertNull(child.uid());
        assertEquals(1, disposed.get());
        root.fibra().dispose().block();
        assertEquals(1, disposed.get());
    }

    @Test
    void serviceInit() {
        var started = new AtomicBoolean();
        var stopped = new AtomicBoolean();
        var fibra = root.plugin(PluginDescriptor.<Void>builder("class-plugin").build(),
            (context, config) -> new Object(), ignored -> {
                started.set(true);
                return Mono.just(Disposables.from(() -> stopped.set(true)));
            }, null);
        await(fibra);
        assertTrue(started.get()); assertFalse(stopped.get());
        fibra.dispose().block();
        assertTrue(stopped.get());
    }

    @FunctionalInterface interface Signal { void call(); }
    static final class ObjectPlugin implements Plugin<String> {
        public org.reactivestreams.Publisher<? extends Disposable> apply(Context context, String config) {
            return Mono.empty();
        }
    }
}
