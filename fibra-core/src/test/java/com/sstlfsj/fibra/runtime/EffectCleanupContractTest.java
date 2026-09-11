package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.EffectHandle;
import com.sstlfsj.fibra.PluginDefinition;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EffectCleanupContractTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void abortedSetupWithCleanupFailureSettlesBothPublicWaiters() {
        try (var runtime = FibraRuntime.create()) {
            var source = TestPublisher.<Disposable>create();
            var failure = new IllegalStateException("cleanup failed");
            var handle = runtime.rootScope().context().effects().collect(source.flux());
            StepVerifier.create(handle.dispose())
                .then(() -> source.next(() -> Mono.error(failure)))
                .expectErrorSatisfies(error -> assertSame(failure, error)).verify(TIMEOUT);
            source.assertCancelled();

            StepVerifier.create(handle.ready())
                .expectErrorSatisfies(error -> assertSame(failure, error)).verify(TIMEOUT);
        }
    }

    @Test
    void sourceFailureWaitsForCleanupAndKeepsBothFailureCauses() {
        try (var runtime = FibraRuntime.create()) {
            var source = TestPublisher.<Disposable>create();
            var cleanup = Sinks.<Void>one();
            var setupFailure = new IllegalStateException("setup failed");
            var cleanupFailure = new IllegalArgumentException("cleanup failed");
            var handle = runtime.rootScope().context().effects().collect(source.flux());
            source.next(cleanup::asMono);
            source.error(setupFailure);

            StepVerifier.create(handle.ready())
                .then(() -> assertEquals(1, cleanup.currentSubscriberCount()))
                .then(() -> assertEquals(Sinks.EmitResult.OK, cleanup.tryEmitError(cleanupFailure)))
                .expectErrorSatisfies(error -> assertSame(setupFailure, error)).verify(TIMEOUT);
            StepVerifier.create(handle.dispose())
                .expectErrorSatisfies(error -> {
                    assertSame(cleanupFailure, error);
                    assertArrayEquals(new Throwable[]{setupFailure}, error.getSuppressed());
                }).verify(TIMEOUT);
        }
    }

    @Test
    void sameFailureInstanceDoesNotPreventEitherWaiterFromTerminating() {
        try (var runtime = FibraRuntime.create()) {
            var source = TestPublisher.<Disposable>create();
            var failure = new IllegalStateException("shared failure");
            var handle = runtime.rootScope().context().effects().collect(source.flux());
            source.next(() -> Mono.error(failure));
            source.error(failure);

            StepVerifier.create(handle.ready())
                .expectErrorSatisfies(error -> assertSame(failure, error)).verify(TIMEOUT);
            StepVerifier.create(handle.dispose())
                .expectErrorSatisfies(error -> assertSame(failure, error)).verify(TIMEOUT);
            assertEquals(0, failure.getSuppressed().length);
        }
    }

    @Test
    void supervisionDisposedBeforeSubscriptionSettlesReadyAndCancelsLateSubscription() {
        var subscriber = new AtomicReference<Subscriber<? super Object>>();
        var handle = new AtomicReference<EffectHandle>();
        var definition = PluginDefinition.builder("supervised", String.class, () -> (context, config) -> {
            handle.set(context.effects().supervise(subscriber::set, "delayed subscription"));
            return Mono.empty();
        }).build();
        try (var runtime = FibraRuntime.create()) {
            runtime.rootScope().context().plugins().mount("supervised", definition.prepare(""))
                .settled().block(TIMEOUT);
            handle.get().dispose().block(TIMEOUT);
            StepVerifier.create(handle.get().ready()).expectNext(handle.get())
                .expectComplete().verify(TIMEOUT);
            var cancelled = new AtomicBoolean();
            var requested = new AtomicBoolean();
            subscriber.get().onSubscribe(new Subscription() {
                @Override
                public void request(long count) {
                    requested.set(true);
                }

                @Override
                public void cancel() {
                    cancelled.set(true);
                }
            });
            assertTrue(cancelled.get());
            assertFalse(requested.get());
        }
    }
}
