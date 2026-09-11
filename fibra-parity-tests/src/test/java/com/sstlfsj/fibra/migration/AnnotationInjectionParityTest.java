package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.annotation.InjectService;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnnotationInjectionParityTest {
    private static final ServiceKey<Value> VALUE = ServiceKey.of("value", Value.class);

    @Test
    void fieldInjectionBecomesARealFibraDependency() {
        var observed = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var definition = PluginDefinition.builder("field-injected", Void.class,
                    () -> new InjectedPlugin(observed))
                .inject(InjectedPlugin.class)
                .build();
            var instance = runtime.rootScope().context().plugins()
                .mount("field-injected", definition.prepare(null));

            assertEquals(PluginInstanceState.PENDING, instance.state());
            runtime.rootScope().context().services().provide(VALUE, new Value(7));
            instance.settled().block();

            assertEquals(7, observed.get());
        }
    }

    @Test
    void methodInjectionUsesANestedFibraAndReactsToServiceReplacement() {
        var starts = new AtomicInteger();
        var stops = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var definition = PluginDefinition.builder("method-injected", Void.class,
                    () -> new MethodInjectedPlugin(starts, stops))
                .inject(MethodInjectedPlugin.class)
                .build();
            var owner = runtime.rootScope().context().plugins()
                .mount("method-injected", definition.prepare(null));
            owner.settled().block();
            var child = owner.context().plugins()
                .find("method-injected:inject:onValueAvailable").orElseThrow();

            var first = runtime.rootScope().context().services()
                .provide(VALUE, new Value(1));
            child.settled().block();
            assertEquals(1, starts.get());
            first.dispose().block();
            assertEquals(1, stops.get());
            runtime.rootScope().context().services().provide(VALUE, new Value(2));
            child.settled().block();

            assertEquals(2, starts.get());
        }
    }

    private static final class InjectedPlugin implements Plugin<Void> {
        private final AtomicInteger observed;

        private InjectedPlugin(AtomicInteger observed) {
            this.observed = observed;
        }

        @InjectService("value")
        private Value value;

        @Override
        public Mono<Void> start(com.sstlfsj.fibra.Context context, Void config) {
            observed.set(value.number());
            return Mono.empty();
        }
    }

    private static final class MethodInjectedPlugin implements Plugin<Void> {
        private final AtomicInteger starts;
        private final AtomicInteger stops;

        private MethodInjectedPlugin(AtomicInteger starts, AtomicInteger stops) {
            this.starts = starts;
            this.stops = stops;
        }

        @Override
        public Mono<Void> start(com.sstlfsj.fibra.Context context, Void config) {
            return Mono.empty();
        }

        @InjectService(value = "value", type = Value.class)
        private Disposable onValueAvailable() {
            starts.incrementAndGet();
            return Disposables.from(stops::incrementAndGet);
        }
    }

    private record Value(int number) {
    }
}
