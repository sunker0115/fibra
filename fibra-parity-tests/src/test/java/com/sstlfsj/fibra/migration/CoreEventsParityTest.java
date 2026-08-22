package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.*;
import com.sstlfsj.fibra.runtime.FibraRuntime;

import com.sstlfsj.fibra.event.CoreEvents;
import com.sstlfsj.fibra.event.EventKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CoreEventsParityTest {
    private static final ServiceKey<Value> VALUE = ServiceKey.of("value", Value.class);
    private static final EventKey<Signal> SIGNAL = EventKey.of("test/signal", Signal.class);

    private final Context context = FibraRuntime.create();

    @AfterEach
    void closeContext() {
        context.closeAsync().block();
    }

    @Test
    void getAndSetUseInternalWaterfalls() {
        var original = new Value(1);
        var replacement = new Value(2);
        var registration = context.provide(VALUE, original);
        context.on(CoreEvents.GET, (caller, key, next) ->
            key.equals(VALUE) ? replacement : next.call());
        context.on(CoreEvents.SET, (caller, key, value, next) -> false);

        assertSame(replacement, context.get(VALUE));
        context.set(VALUE, new Value(3));

        assertSame(replacement, context.get(VALUE));
        assertSame(original, registration.value());
    }

    @Test
    void listenerAndDispatchEventsAreWiredWithoutRecursiveDispatch() {
        var listenerChanges = new ArrayList<Boolean>();
        var dispatches = new ArrayList<String>();
        context.on(CoreEvents.LISTENER, (owner, key, listener, added) -> {
            if (key.equals(SIGNAL)) {
                listenerChanges.add(added);
            }
        });
        context.on(CoreEvents.DISPATCH, (mode, key, target) ->
            dispatches.add(mode + ":" + key.name()));

        var handle = context.on(SIGNAL, () -> {
        });
        context.emit(SIGNAL, Signal::run);
        handle.dispose().block();

        assertEquals(List.of(true, false), listenerChanges);
        assertEquals(List.of("emit:test/signal"), dispatches);
    }

    @Test
    void pluginStatusAndServiceEventsExposeLifecycleChanges() {
        var plugins = new ArrayList<String>();
        var states = new ArrayList<FibraState>();
        var services = new ArrayList<Object>();
        context.on(CoreEvents.PLUGIN, fibra -> plugins.add(fibra.name()));
        context.on(CoreEvents.STATUS, (fibra, previous) -> {
            if (fibra.name().equals("observed")) {
                states.add(fibra.state());
            }
        });
        context.on(CoreEvents.SERVICE, (key, value) -> {
            if (key.equals(VALUE)) {
                services.add(value);
            }
        });

        var fibra = context.plugin("observed", (pluginContext, ignored) -> Mono.empty(), null);
        fibra.await().block();
        var registration = context.provide(VALUE, new Value(1));
        registration.dispose().block();

        assertEquals(List.of("observed"), plugins);
        assertEquals(List.of(FibraState.LOADING, FibraState.ACTIVE), states);
        assertEquals(2, services.size());
        assertEquals(null, services.getLast());
    }

    @Test
    void updateEventCanReplaceTheDefaultUpdateFlow() {
        var seen = new ArrayList<Integer>();
        var descriptor = PluginDescriptor.<Integer>builder("updated").build();
        var fibra = context.plugin(descriptor, (pluginContext, value) -> {
            seen.add(value);
            return Mono.empty();
        }, 1);
        fibra.await().block();
        context.on(CoreEvents.UPDATE, (target, config, noSave, next) ->
            target == fibra ? Mono.just(target) : next.call());

        fibra.update(2).block();

        assertEquals(List.of(1), seen);
        assertEquals(FibraState.ACTIVE, fibra.state());
    }

    record Value(int number) {
    }

    @FunctionalInterface
    interface Signal {
        void run();
    }
}
