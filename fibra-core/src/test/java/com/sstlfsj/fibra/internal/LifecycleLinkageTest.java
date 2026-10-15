package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.DrainingDisposable;
import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.annotation.InjectService;
import com.sstlfsj.fibra.event.AggregateEventException;
import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.EventMode;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** A broken lane must not strand Surefire itself, including a finally block that closes the runtime. */
class LifecycleLinkageTest {
    private static final Duration LIMIT = Duration.ofSeconds(4);
    @TempDir Path work;

    @ParameterizedTest
    @ValueSource(strings = {"call", "mono", "factory", "start", "start-publisher", "start-signal",
        "effect-supplier", "effect-source", "dispose", "dispose-publisher", "drain", "drain-publisher",
        "injection", "update-validator", "parallel-supplier", "parallel-subscribe", "serial-supplier",
        "serial-subscribe", "parallel-completion", "serial-first"})
    void controlledFailureDoesNotStrandLifecycle(String scenario) throws Exception {
        var log = work.resolve(scenario + ".log");
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            LifecycleLinkageTest.class.getName(), scenario)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS),
                () -> "child did not terminate: " + scenario + "\n" + read(log));
            assertEquals(0, child.exitValue(), () -> read(log));
            assertTrue(read(log).contains("completed:" + scenario), () -> read(log));
        } finally {
            if (child.isAlive()) child.destroyForcibly();
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "owned child was reaped");
        }
    }

    private static String read(Path log) {
        try { return Files.readString(log); }
        catch (Exception failure) { return failure.toString(); }
    }

    public static void main(String[] args) throws Exception {
        var scenario = args[0];
        System.out.println("started:" + scenario);
        var original = new NoClassDefFoundError("delayed Host API: " + scenario);
        original.initCause(new ClassNotFoundException("host API is not imported"));
        switch (scenario) {
            case "call", "mono" -> dispatcher(scenario, original);
            case "factory" -> factory(original);
            case "start", "start-publisher", "start-signal" -> start(scenario, original);
            case "effect-supplier", "effect-source" -> acquisition(scenario, original);
            case "injection" -> injection(original);
            case "update-validator" -> updateValidator(original);
            case "parallel-supplier", "parallel-subscribe", "serial-supplier", "serial-subscribe" ->
                eventFailure(scenario, original);
            case "parallel-completion" -> parallelCompletion(original);
            case "serial-first" -> serialFirst();
            default -> cleanup(scenario, original);
        }
        System.out.println("completed:" + scenario);
    }

    private static void dispatcher(String scenario, Error original) {
        var dispatcher = new LifecycleDispatcher("linkage-test");
        try {
            for (var round = 0; round < 2; round++) {
                if (scenario.equals("call")) {
                    assertSame(original, assertThrows(Error.class,
                        () -> dispatcher.call(() -> { throw original; })));
                    assertSame(original, dispatcher.call(() -> assertThrows(Error.class,
                        () -> dispatcher.call(() -> { throw original; }))));
                } else {
                    assertSame(original, dispatcher.mono(() -> { throw original; })
                        .materialize().block(LIMIT).getThrowable());
                    var observed = new AtomicReference<Throwable>();
                    dispatcher.call(() -> {
                        dispatcher.mono(() -> { throw original; }).subscribe(ignored -> { }, observed::set);
                        return null;
                    });
                    assertSame(original, observed.get());
                    assertSame(original, dispatcher.run(() -> { throw original; })
                        .materialize().block(LIMIT).getThrowable());
                }
                assertEquals("healthy", dispatcher.call(() -> "healthy"));
                assertNull(dispatcher.call(() -> null));
                assertEquals("healthy", dispatcher.mono(() -> "healthy").block(LIMIT));
            }
        } finally { dispatcher.shutdown(); }
    }

    private static void factory(Error original) {
        try (var runtime = FibraRuntime.create()) {
            var plugins = runtime.rootScope().context().plugins();
            var definition = PluginDefinition.builder("bad", Void.class, () -> { throw original; }).build();
            for (var round = 0; round < 2; round++) {
                assertSame(original, assertThrows(Error.class, () -> plugins.mount("bad", definition.prepare(null))));
                assertTrue(plugins.find("bad").isEmpty());
            }
            healthy(runtime);
        }
    }

    private static void start(String scenario, Error original) {
        var disposed = new AtomicInteger();
        var signal = Sinks.<Void>one();
        Plugin<Void> plugin = (context, config) -> {
            context.effects().add(() -> Mono.fromRunnable(disposed::incrementAndGet));
            return switch (scenario) {
                case "start" -> throw original;
                case "start-publisher" -> Mono.fromRunnable(() -> { throw original; });
                default -> signal.asMono();
            };
        };
        try (var runtime = FibraRuntime.create()) {
            var instance = runtime.rootScope().context().plugins().mount("bad",
                PluginDefinition.builder("bad", Void.class, () -> plugin).build().prepare(null));
            signal.tryEmitError(original);
            assertSame(original, instance.settled().materialize().block(LIMIT).getThrowable());
            assertEquals(PluginInstanceState.FAILED, instance.state());
            assertSame(original, instance.failure().orElseThrow());
            assertEquals(1, disposed.get());
            healthy(runtime);
            instance.dispose().block(LIMIT);
        }
    }

    private static void acquisition(String scenario, Error original) {
        try (var runtime = FibraRuntime.create()) {
            var effects = runtime.rootScope().context().effects();
            if (scenario.equals("effect-supplier")) {
                assertSame(original, assertThrows(Error.class,
                    () -> effects.effect(() -> { throw original; })));
            } else {
                assertSame(original, assertThrows(Error.class,
                    () -> effects.collect(subscriber -> { throw original; })));
            }
            healthy(runtime);
        }
    }

    private static void injection(Error original) {
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            var definition = PluginDefinition.builder("injected", Void.class,
                () -> new MethodFailurePlugin(original)).inject(MethodFailurePlugin.class).build();
            var owner = context.plugins().mount("injected", definition.prepare(null));
            owner.settled().block(LIMIT);
            var child = owner.context().plugins().find("injected:inject:onDependency").orElseThrow();
            assertEquals(PluginInstanceState.PENDING, child.state());

            context.services().provide(ServiceKey.of("dependency", Runnable.class), () -> { });

            assertSame(original, child.settled().materialize().block(LIMIT).getThrowable());
            assertEquals(PluginInstanceState.FAILED, child.state());
            assertSame(original, child.failure().orElseThrow());
            assertSame(original, child.settled().materialize().block(LIMIT).getThrowable());
            assertSame(owner, owner.settled().block(LIMIT));
            assertEquals(PluginInstanceState.ACTIVE, owner.state(), "injected methods have a child lifecycle");
            healthy(runtime);
        }
    }

    private static void updateValidator(Error original) {
        var starts = new CopyOnWriteArrayList<String>();
        var stops = new AtomicInteger();
        var definition = PluginDefinition.builder("validated", String.class, () -> (context, config) -> {
            starts.add(config);
            context.effects().add(() -> Mono.fromRunnable(stops::incrementAndGet));
            return Mono.empty();
        }).validator(config -> {
            if (config.equals("invalid")) throw original;
            return config;
        }).build();
        try (var runtime = FibraRuntime.create()) {
            var instance = runtime.rootScope().context().plugins().mount("validated", definition.prepare("old"));
            instance.settled().block(LIMIT);
            for (var round = 0; round < 2; round++) {
                assertSame(original, instance.update("invalid").materialize().block(LIMIT).getThrowable());
                assertSame(instance, instance.settled().block(LIMIT));
                assertEquals("old", instance.config());
                assertEquals(PluginInstanceState.ACTIVE, instance.state());
                assertTrue(instance.failure().isEmpty());
                assertEquals(List.of("old"), starts, "validation failure must not register a new activation target");
                assertEquals(0, stops.get());
            }
            assertSame(instance, instance.update("new").block(LIMIT));
            assertEquals("new", instance.config());
            assertEquals(PluginInstanceState.ACTIVE, instance.state());
            assertEquals(List.of("old", "new"), starts);
            assertEquals(1, stops.get());
            healthy(runtime);
        }
        assertEquals(2, stops.get());
    }

    private static void eventFailure(String scenario, Error original) {
        var parallel = scenario.startsWith("parallel");
        var key = EventKey.of("linkage-event", AsyncListener.class,
            parallel ? EventMode.PARALLEL : EventMode.SERIAL);
        var independent = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            events.on(key, () -> {
                if (scenario.endsWith("supplier")) throw original;
                return subscriber -> { throw original; };
            });
            events.on(key, () -> Mono.fromRunnable(independent::incrementAndGet));
            for (var round = 0; round < 2; round++) {
                var result = parallel ? events.parallel(key, AsyncListener::call)
                    : events.serial(key, AsyncListener::call).then();
                var failure = result.materialize().block(LIMIT).getThrowable();
                if (parallel) {
                    assertEquals(List.of(original), assertInstanceOf(AggregateEventException.class, failure).causes());
                    assertEquals(round + 1, independent.get(), "parallel continues independent listeners");
                } else {
                    assertSame(original, failure);
                    assertEquals(0, independent.get(), "serial stops on its first failure");
                }
            }
            healthy(runtime);
        }
    }

    private static void parallelCompletion(Error original) throws Exception {
        var key = EventKey.of("parallel-completion", AsyncListener.class, EventMode.PARALLEL);
        var ending = Sinks.<Object>one();
        var values = new AtomicInteger();
        var independent = new AtomicInteger();
        var late = new IllegalStateException("listener failed after multiple values");
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            events.on(key, () -> { throw original; });
            events.on(key, () -> Flux.concat(Flux.just("first", "second"), ending.asMono())
                .doOnNext(ignored -> values.incrementAndGet()));
            events.on(key, () -> Mono.fromRunnable(independent::incrementAndGet));
            try {
                var receipt = events.parallel(key, AsyncListener::call).materialize().toFuture();
                assertEquals(2, values.get());
                assertEquals(1, ending.currentSubscriberCount(), "parallel retains the complete listener stream");
                assertEquals(1, independent.get(), "an earlier failure does not prevent another listener");
                assertFalse(receipt.isDone(), "aggregate waits for every listener's terminal signal");

                assertEquals(Sinks.EmitResult.OK, ending.tryEmitError(late));

                var failure = receipt.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS).getThrowable();
                assertEquals(List.of(original, late), assertInstanceOf(AggregateEventException.class, failure).causes());
                healthy(runtime);
            } finally {
                ending.tryEmitEmpty();
            }
        }
    }

    private static void serialFirst() {
        var key = EventKey.of("serial-first", AsyncListener.class, EventMode.SERIAL);
        var cancellations = new AtomicInteger();
        var values = new AtomicInteger();
        var skipped = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            events.on(key, () -> Flux.<Object>concat(Mono.just(false), Mono.never())
                .doOnNext(ignored -> values.incrementAndGet()).doOnCancel(cancellations::incrementAndGet));
            events.on(key, () -> Flux.<Object>concat(Mono.just("winner"), Mono.never())
                .doOnNext(ignored -> values.incrementAndGet()).doOnCancel(cancellations::incrementAndGet));
            events.on(key, () -> {
                skipped.incrementAndGet();
                return Mono.just("unreachable");
            });

            assertEquals("winner", events.serial(key, AsyncListener::call).block(LIMIT));
            assertEquals(2, values.get());
            assertEquals(2, cancellations.get(), "each listener is cancelled after its first item");
            assertEquals(0, skipped.get(), "a non-false first item ends serial dispatch");
            healthy(runtime);
        }
    }

    @FunctionalInterface
    private interface AsyncListener { Publisher<Object> call(); }

    private static final class MethodFailurePlugin implements Plugin<Void> {
        private final Error failure;

        private MethodFailurePlugin(Error failure) { this.failure = failure; }

        @Override public Mono<Void> start(com.sstlfsj.fibra.Context context, Void config) {
            return Mono.empty();
        }

        @InjectService(value = "dependency", type = Runnable.class)
        private void onDependency() { throw failure; }
    }

    private static void cleanup(String scenario, Error original) {
        var runtime = new DefaultFibraRuntime();
        var domain = runtime.openDomain("owner");
        var scope = domain.rootScope().openChild("scope");
        var released = new AtomicInteger();
        var attempts = new AtomicInteger();
        var draining = scenario.startsWith("drain");
        Disposable resource = draining ? new DrainingDisposable() {
            @Override public Mono<Void> drain() {
                attempts.incrementAndGet();
                if (scenario.equals("drain")) throw original;
                return Mono.fromRunnable(() -> { throw original; });
            }
            @Override public Mono<Void> dispose() { return Mono.fromRunnable(released::incrementAndGet); }
        } : () -> {
            attempts.incrementAndGet();
            if (scenario.equals("dispose")) throw original;
            return Mono.fromRunnable(() -> { throw original; });
        };
        var handle = scope.context().effects().add(resource);
        scope.context().effects().add(() -> Mono.fromRunnable(released::incrementAndGet));
        try {
            var failure = scope.closeAsync().materialize().block(LIMIT).getThrowable();
            if (draining) {
                assertNotNull(failure);
                assertTrue(contains(failure, original));
                assertSame(failure, scope.closeAsync().materialize().block(LIMIT).getThrowable());
            } else {
                assertNull(failure, "ordinary Scope close records cleanup diagnostics and completes");
                assertSame(original, handle.dispose().materialize().block(LIMIT).getThrowable());
                scope.closeAsync().block(LIMIT);
            }
            assertEquals(1, attempts.get());
            assertEquals(draining ? 0 : 1, released.get());
            assertFalse(domain.snapshot().cleanupFailures().isEmpty());
            if (draining) assertFalse(scope.isClosed(), "failed drain retains its owner");
            healthy(runtime.rootScope().context());
            var closeFailure = runtime.closeAsync().materialize().block(LIMIT).getThrowable();
            if (draining) {
                assertNotNull(closeFailure);
                assertFalse(runtime.isClosed(), "retained drain resources prevent successful runtime close");
                assertSame(closeFailure, runtime.closeAsync().materialize().block(LIMIT).getThrowable());
            } else {
                assertNull(closeFailure);
                assertTrue(runtime.isClosed());
                runtime.closeAsync().block(LIMIT);
            }
        } finally {
            // Only release the test's lane after proving the retained-owner failure receipt.
            // This is not a successful runtime close and does not release retained resources.
            runtime.lifecycle().shutdown();
        }
    }

    private static boolean contains(Throwable root, Throwable original) {
        if (root == original) return true;
        if (root.getCause() != null && contains(root.getCause(), original)) return true;
        for (var suppressed : root.getSuppressed()) if (contains(suppressed, original)) return true;
        return false;
    }

    private static void healthy(FibraRuntime runtime) {
        healthy(runtime.rootScope().context());
    }

    private static void healthy(com.sstlfsj.fibra.Context context) {
        var id = "healthy-" + context.plugins().instances().size();
        var instance = context.plugins().mount(id,
            PluginDefinition.builder(id, Void.class, () -> (pluginContext, config) -> Mono.empty()).build().prepare(null));
        instance.settled().block(LIMIT);
        assertEquals(PluginInstanceState.ACTIVE, instance.state());
    }
}
