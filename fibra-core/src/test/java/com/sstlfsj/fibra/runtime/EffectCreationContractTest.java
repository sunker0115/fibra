package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EffectCreationContractTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void synchronousSetupFailureDoesNotAllowPluginActivation() {
        var expected = new IllegalStateException("setup failed");
        var continued = new AtomicInteger();
        var definition = PluginDefinition.builder("broken", String.class, () -> (context, config) -> {
            context.effects().effect(() -> { throw expected; });
            continued.incrementAndGet();
            return Mono.empty();
        }).build();
        try (var runtime = FibraRuntime.create()) {
            var instance = runtime.rootScope().context().plugins().mount("broken", definition, "");
            StepVerifier.create(instance.settled())
                .expectErrorSatisfies(error -> assertSame(expected, error)).verify(TIMEOUT);
            assertEquals(PluginInstanceState.FAILED, instance.state());
            assertSame(expected, instance.failure().orElseThrow());
            assertEquals(0, continued.get());
        }
    }

    @Test
    void reentrantScopeCloseWaitsForSynchronousSetupAndDisposesItsResult() {
        var disposed = new AtomicInteger();
        var closing = new AtomicReference<Mono<Void>>();
        try (var runtime = FibraRuntime.create()) {
            var child = runtime.rootScope().openChild("reentrant");
            var handle = child.context().effects().effect(() -> {
                closing.set(child.closeAsync());
                assertFalse(child.isClosed());
                assertEquals(0, disposed.get());
                return Disposables.from(disposed::incrementAndGet);
            });
            closing.get().block(TIMEOUT);
            assertTrue(child.isClosed());
            assertEquals(1, disposed.get());
            handle.dispose().block(TIMEOUT);
            assertEquals(1, disposed.get());
        }
    }

    @Test
    void closedScopeRejectsBeforeInvokingSupplier() {
        var calls = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var child = runtime.rootScope().openChild("closed");
            child.closeAsync().block(TIMEOUT);
            assertThrows(com.sstlfsj.fibra.FibraException.class, () -> child.context().effects().effect(() -> {
                calls.incrementAndGet();
                return Disposables.noop();
            }));
            assertEquals(0, calls.get());
        }
    }
}
