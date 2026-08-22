package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.*;
import com.sstlfsj.fibra.runtime.FibraRuntime;

import com.sstlfsj.fibra.annotation.InjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnnotationInjectionParityTest {
    private static final ServiceKey<Value> VALUE = ServiceKey.of("value", Value.class);

    private final Context context = FibraRuntime.create();

    @AfterEach
    void closeContext() {
        context.closeAsync().block();
    }

    @Test
    void fieldInjectionBecomesARealFibraDependency() {
        var observed = new AtomicReference<Value>();
        var descriptor = PluginDescriptor.<Void>builder("field-injected")
            .inject(InjectedPlugin.class)
            .build();
        var fibra = context.plugin(descriptor, InjectedPlugin::new,
            plugin -> {
                observed.set(plugin.value);
                return Mono.empty();
            }, null);

        assertEquals(FibraState.PENDING, fibra.state());
        var expected = new Value(7);
        context.provide(VALUE, expected);
        fibra.await().block();

        assertEquals(expected, observed.get());
    }

    @Test
    void methodInjectionUsesANestedFibraAndReactsToServiceReplacement() {
        var calls = new AtomicInteger();
        var owner = context.plugin(PluginDescriptor.<AtomicInteger>builder("method-injected").build(),
            MethodInjectedPlugin::new,
            ignored -> Mono.empty(),
            calls);
        owner.await().block();
        assertEquals(0, calls.get());

        var first = context.provide(VALUE, new Value(1));
        awaitCalls(calls, 1);
        first.dispose().block();
        context.provide(VALUE, new Value(2));
        awaitCalls(calls, 2);

        assertEquals(2, calls.get());
    }

    private static void awaitCalls(AtomicInteger calls, int expected) {
        org.awaitility.Awaitility.await().until(() -> calls.get() == expected);
    }

    static final class InjectedPlugin {
        @InjectService("value")
        private Value value;

        InjectedPlugin(Context context, Void ignored) {
        }
    }

    static final class MethodInjectedPlugin {
        private final AtomicInteger calls;

        MethodInjectedPlugin(Context context, AtomicInteger calls) {
            this.calls = calls;
        }

        @InjectService(value = "value", type = Value.class)
        private void onValueAvailable() {
            calls.incrementAndGet();
        }
    }

    record Value(int number) {
    }
}
