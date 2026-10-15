package com.sstlfsj.fibra.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleCallbackTest {
    private static final Duration LIMIT = Duration.ofSeconds(4);

    @Test
    void invokesFactoryForEachSubscriptionAndPreservesValuesAndEmptyCompletion() {
        var calls = new AtomicInteger();
        var result = LifecycleCallback.defer(() -> Mono.just(calls.incrementAndGet()));

        assertEquals(1, result.block(LIMIT));
        assertEquals(2, result.block(LIMIT));
        assertNull(LifecycleCallback.defer(Mono::empty).block(LIMIT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"factory", "subscribe", "runnable", "signal"})
    void preservesLinkageFailureIdentityAcrossOperatorsAndCachedReceipts(String boundary) throws Exception {
        var original = linkage();
        var scheduler = Schedulers.newSingle("lifecycle-callback-test", true);
        try {
            var receipt = Mono.just("start").flatMap(ignored -> failureAt(boundary, original))
                .then(Mono.just("unreachable")).publishOn(scheduler).cache();

            assertSame(original, failure(receipt));
            assertSame(original, failure(receipt));
            assertInstanceOf(ClassNotFoundException.class, original.getCause());
        } finally {
            scheduler.dispose();
        }
    }

    @Test
    void preservesSubscriberContextInReturnedSource() {
        var result = LifecycleCallback.defer(() ->
            Mono.deferContextual(context -> Mono.just(context.<String>get("trace"))));

        assertEquals("caller-trace", result.contextWrite(Context.of("trace", "caller-trace")).block(LIMIT));
    }

    @Test
    void immediateCancellationDoesNotInvokeFactory() {
        var calls = new AtomicInteger();
        var result = LifecycleCallback.defer(() -> {
            calls.incrementAndGet();
            return Mono.<Void>empty();
        });

        result.subscribe(new BaseSubscriber<>() {
            @Override protected void hookOnSubscribe(Subscription subscription) { cancel(); }
        });

        assertEquals(0, calls.get());
    }

    @Test
    void transfersCancellationBeforeSourceSubscribeReturnsAndToleratesLateLinkageSignal() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancellations = new AtomicInteger();
        var escaped = new AtomicReference<Throwable>();
        var observer = new RecordingSubscriber();
        var source = new Mono<Void>() {
            @Override public void subscribe(CoreSubscriber<? super Void> subscriber) {
                subscriber.onSubscribe(subscription(cancellations::incrementAndGet));
                entered.countDown();
                try {
                    if (!release.await(LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new AssertionError("test did not release source subscription");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                subscriber.onError(linkage());
            }
        };
        var subscribing = new Thread(() -> {
            try {
                LifecycleCallback.defer(() -> source).subscribe(observer);
            } catch (Throwable failure) {
                escaped.set(failure);
            }
        }, "lifecycle-callback-subscribe-test");
        subscribing.setDaemon(true);
        subscribing.start();
        try {
            assertTrue(entered.await(LIMIT.toMillis(), TimeUnit.MILLISECONDS));
            observer.subscription.get().cancel();
            assertEquals(1, cancellations.get(), "cancel must not await subscribe returning");
        } finally {
            release.countDown();
            subscribing.join(LIMIT.toMillis());
        }

        assertFalse(subscribing.isAlive(), "owned subscription thread was reaped");
        assertNull(escaped.get(), "a cancelled receipt must not rethrow a late LinkageError");
        assertEquals(0, observer.errors.get());
        assertEquals(0, observer.completions.get());
        assertEquals(1, cancellations.get());
    }

    @ParameterizedTest
    @MethodSource("ordinaryFailures")
    void synchronousSubscribeThrowCancelsAcquiredSubscriptionExactlyOnce(Throwable original) throws Exception {
        var cancellations = new AtomicInteger();
        var source = throwsAfterSubscription(original, cancellations::incrementAndGet);

        assertSame(original, failure(LifecycleCallback.defer(() -> source)));
        assertEquals(1, cancellations.get());
    }

    @ParameterizedTest
    @MethodSource("ordinaryFailures")
    void cancellationFailureIsSuppressedWithoutReplacingSubscribeFailure(Throwable original) throws Exception {
        var cancellationFailure = new IllegalStateException("upstream cancellation failed");
        var cancellations = new AtomicInteger();
        var source = throwsAfterSubscription(original, () -> {
            cancellations.incrementAndGet();
            throw cancellationFailure;
        });

        assertSame(original, failure(LifecycleCallback.defer(() -> source)));
        assertEquals(1, cancellations.get());
        assertTrue(Arrays.stream(original.getSuppressed()).anyMatch(value -> value == cancellationFailure));
    }

    @Test
    void duplicateSourceCompletionDoesNotDeliverAnotherTerminal() {
        var observer = new RecordingSubscriber();
        var source = new Mono<Void>() {
            @Override public void subscribe(CoreSubscriber<? super Void> subscriber) {
                subscriber.onSubscribe(subscription(() -> { }));
                subscriber.onComplete();
                subscriber.onComplete();
            }
        };

        LifecycleCallback.defer(() -> source).subscribe(observer);

        assertEquals(1, observer.completions.get());
        assertEquals(0, observer.errors.get());
    }

    @Test
    void duplicateSubscriptionIsCancelledWithoutReplacingTheFirst() {
        var firstCancellations = new AtomicInteger();
        var duplicateCancellations = new AtomicInteger();
        var source = new Mono<Void>() {
            @Override public void subscribe(CoreSubscriber<? super Void> subscriber) {
                subscriber.onSubscribe(subscription(firstCancellations::incrementAndGet));
                subscriber.onSubscribe(subscription(duplicateCancellations::incrementAndGet));
                subscriber.onComplete();
            }
        };
        var observer = new RecordingSubscriber();

        LifecycleCallback.defer(() -> source).subscribe(observer);

        assertEquals(0, firstCancellations.get());
        assertEquals(1, duplicateCancellations.get());
        assertEquals(1, observer.completions.get());
        assertEquals(0, observer.errors.get());
    }

    @Test
    void throwAfterSourceCompletionDoesNotBecomeAnotherReceipt() {
        var source = new Mono<Void>() {
            @Override public void subscribe(CoreSubscriber<? super Void> subscriber) {
                subscriber.onSubscribe(subscription(() -> { }));
                subscriber.onComplete();
                throw new IllegalStateException("invalid source threw after completion");
            }
        };
        var observer = new RecordingSubscriber();

        LifecycleCallback.defer(() -> source).subscribe(observer);

        assertEquals(1, observer.completions.get());
        assertEquals(0, observer.errors.get());
    }

    @Test
    void synchronousCapturePreservesCheckedExceptionWrappingAndFatalIdentity() {
        var checked = new java.io.IOException("checked callback failure");
        var wrapped = assertThrows(IllegalStateException.class,
            () -> LifecycleCallback.capture(() -> { throw checked; }).get());
        assertSame(checked, wrapped.getCause());
        var fatal = new InternalError("preconstructed VM fatal");
        assertSame(fatal, assertThrows(InternalError.class,
            () -> LifecycleCallback.capture(() -> { throw fatal; })));
    }

    @Test
    void explicitCancellationFailureEscapesWithoutDeliveringAnOrdinaryError() {
        var original = linkage();
        var observer = new RecordingSubscriber();
        var source = new Mono<Void>() {
            @Override public void subscribe(CoreSubscriber<? super Void> subscriber) {
                subscriber.onSubscribe(subscription(() -> { throw original; }));
            }
        };
        LifecycleCallback.defer(() -> source).subscribe(observer);

        assertSame(original, assertThrows(LinkageError.class, () -> observer.subscription.get().cancel()));
        assertEquals(0, observer.errors.get());
        assertEquals(0, observer.completions.get());
    }

    @ParameterizedTest
    @MethodSource("fatalBoundaries")
    void sourceFatalEscapesUnchangedInsteadOfBecomingAnOrdinarySignal(String boundary, Error fatal) {
        var observer = new RecordingSubscriber();

        assertSame(fatal, assertThrows(Error.class, () -> failureAt(boundary, fatal).subscribe(observer)));
        assertEquals(0, observer.errors.get());
        assertEquals(0, observer.completions.get());
    }

    private static Stream<Arguments> fatalBoundaries() {
        return Stream.of("factory", "subscribe", "signal").flatMap(boundary -> Stream.of(
            Arguments.of(boundary, new InternalError("preconstructed VM fatal")),
            Arguments.of(boundary, new ThreadDeath())));
    }

    private static Stream<Throwable> ordinaryFailures() {
        return Stream.of(linkage(), new IllegalStateException("synchronous subscribe failed"),
            new AssertionError("synchronous subscribe failed"));
    }

    private static Mono<Void> failureAt(String boundary, Error original) {
        return LifecycleCallback.defer(() -> {
            if (boundary.equals("factory")) throw original;
            if (boundary.equals("runnable")) return Mono.fromRunnable(() -> { throw original; });
            if (boundary.equals("subscribe")) return new Mono<Void>() {
                @Override public void subscribe(CoreSubscriber<? super Void> subscriber) { throw original; }
            };
            return Mono.error(original);
        });
    }

    private static Mono<Void> throwsAfterSubscription(Throwable failure, Runnable cancel) {
        return new Mono<>() {
            @Override public void subscribe(CoreSubscriber<? super Void> subscriber) {
                subscriber.onSubscribe(subscription(cancel));
                if (failure instanceof Error error) throw error;
                throw (RuntimeException) failure;
            }
        };
    }

    private static Subscription subscription(Runnable cancel) {
        return new Subscription() {
            @Override public void request(long count) { }
            @Override public void cancel() { cancel.run(); }
        };
    }

    private static Throwable failure(Mono<?> source) throws Exception {
        return assertThrows(ExecutionException.class,
            () -> source.toFuture().get(LIMIT.toMillis(), TimeUnit.MILLISECONDS)).getCause();
    }

    private static NoClassDefFoundError linkage() {
        var original = new NoClassDefFoundError("delayed Host API");
        original.initCause(new ClassNotFoundException("host API is not imported"));
        return original;
    }

    private static final class RecordingSubscriber implements CoreSubscriber<Void> {
        private final AtomicReference<Subscription> subscription = new AtomicReference<>();
        private final AtomicInteger errors = new AtomicInteger();
        private final AtomicInteger completions = new AtomicInteger();

        @Override public void onSubscribe(Subscription upstream) {
            subscription.set(upstream);
            upstream.request(Long.MAX_VALUE);
        }
        @Override public void onNext(Void ignored) { fail("a completion source must not emit a value"); }
        @Override public void onError(Throwable failure) { errors.incrementAndGet(); }
        @Override public void onComplete() { completions.incrementAndGet(); }
    }
}
