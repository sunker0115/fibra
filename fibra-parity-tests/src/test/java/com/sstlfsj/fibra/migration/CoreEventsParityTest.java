package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.EventMode;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;

import static com.sstlfsj.fibra.migration.MigrationTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreEventsParityTest {
    private static final ServiceKey<Value> VALUE = ServiceKey.of("value", Value.class);
    private static final EventKey<Signal> SIGNAL = EventKey.of(
        "test/signal", Signal.class, EventMode.EMIT);

    @Test
    void getAndSetUseInternalWaterfalls() {
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            var original = new Value(1);
            var replacement = new Value(2);
            var registration = context.services().provide(VALUE, original);
            var derived = context.withIntercept(VALUE, replacement);

            assertSame(replacement, derived.intercept(VALUE));
            assertSame(original, derived.services().require(VALUE));
            assertSame(original, registration.value());
        }
    }

    @Test
    void listenerAndDispatchEventsAreWiredWithoutRecursiveDispatch() {
        var calls = new ArrayList<String>();
        try (var runtime = FibraRuntime.create();
             var domain = runtime.openDomain("events")) {
            var events = domain.rootScope().context().events();
            var handle = events.on(SIGNAL, value -> calls.add(value));

            assertEquals(1, domain.snapshot().events().getFirst().listeners().size());
            events.emit(SIGNAL, listener -> listener.run("value"));
            handle.dispose().block();

            assertEquals(List.of("value"), calls);
            assertTrue(domain.snapshot().events().getFirst().listeners().isEmpty());
        }
    }

    @Test
    void pluginStatusAndServiceEventsExposeLifecycleChanges() {
        var states = new ArrayList<PluginInstanceState>();
        var start = Sinks.<Void>one();
        try (var runtime = FibraRuntime.create();
             var domain = runtime.openDomain("diagnostics")) {
            var context = domain.rootScope().context();
            var definition = PluginDefinition.builder("observed", Void.class,
                () -> (pluginContext, ignored) -> start.asMono()).build();
            var plugin = context.plugins().mount("observed", definition.prepare(null));
            plugin.states().subscribe(states::add);
            var registration = context.services().provide(VALUE, new Value(1));

            assertEquals(1, domain.snapshot().services().size());
            start.tryEmitEmpty();
            plugin.settled().block();
            await(() -> states.contains(PluginInstanceState.ACTIVE));
            assertEquals(PluginInstanceState.ACTIVE,
                domain.snapshot().plugins().getFirst().state());
            registration.dispose().block();

            assertTrue(states.contains(PluginInstanceState.STARTING));
            assertTrue(states.contains(PluginInstanceState.ACTIVE));
            assertTrue(domain.snapshot().services().isEmpty());
        }
    }

    @Test
    void updateEventCanReplaceTheDefaultUpdateFlow() {
        var seen = new ArrayList<Integer>();
        try (var runtime = FibraRuntime.create()) {
            var definition = PluginDefinition.builder("updated", Integer.class,
                    () -> (context, value) -> {
                        seen.add(value);
                        return Mono.empty();
                    })
                .validator(value -> {
                    if (value == 2) {
                        throw new IllegalArgumentException("veto");
                    }
                    return value;
                })
                .build();
            var plugin = runtime.rootScope().context().plugins()
                .mount("updated", definition.prepare(1));
            plugin.settled().block();

            assertThrows(IllegalArgumentException.class,
                () -> plugin.update(2).block());

            assertEquals(List.of(1), seen);
            assertEquals(PluginInstanceState.ACTIVE, plugin.state());
        }
    }

    private record Value(int number) {
    }

    @FunctionalInterface
    private interface Signal {
        void run(String value);
    }
}
