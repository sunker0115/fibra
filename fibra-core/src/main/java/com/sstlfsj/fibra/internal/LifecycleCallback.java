package com.sstlfsj.fibra.internal;

import reactor.core.Disposables;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps recoverable callback errors out of Reactor's JVM-fatal throw path. */
final class LifecycleCallback {
    private LifecycleCallback() { }

    static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
    }

    static <T> Outcome<T> capture(Callable<T> action) {
        try {
            return new Outcome<>(action.call(), null);
        } catch (RuntimeException | Error failure) {
            rethrowFatal(failure);
            return new Outcome<>(null, failure);
        } catch (Exception failure) {
            return new Outcome<>(null, new IllegalStateException(failure));
        }
    }

    static <T> Mono<T> failure(Throwable failure) {
        rethrowFatal(failure);
        // MonoError is Callable: scalar shortcuts would throw LinkageError again.
        return Mono.<T>error(failure).hide();
    }

    static <T> Mono<T> defer(Callable<? extends Mono<? extends T>> action) {
        return Mono.<Outcome<T>>create(sink -> {
            var upstream = Disposables.swap();
            var terminal = new AtomicBoolean();
            sink.onCancel(upstream);
            if (upstream.isDisposed()) return;
            try {
                var source = Objects.requireNonNull(capture(action).get(), "callback returned null");
                source.contextWrite(sink.contextView()).subscribe(value -> {
                    if (terminal.compareAndSet(false, true)) sink.success(new Outcome<>(value, null));
                }, failure -> {
                    rethrowFatal(failure);
                    if (terminal.compareAndSet(false, true)) {
                        cancelAfterFailure(upstream, failure);
                        sink.success(new Outcome<>(null, failure));
                    }
                }, () -> {
                    if (terminal.compareAndSet(false, true)) sink.success(new Outcome<>(null, null));
                }, subscription -> {
                    // Cancellation must reach an acquired subscription before subscribe returns.
                    upstream.update(subscription::cancel);
                    if (!upstream.isDisposed()) subscription.request(Long.MAX_VALUE);
                });
            } catch (RuntimeException | Error failure) {
                rethrowFatal(failure);
                if (!terminal.compareAndSet(false, true)) throw failure;
                cancelAfterFailure(upstream, failure);
                // Only values cross this sink: sink.error(LinkageError) is unsafe after cancel.
                sink.success(new Outcome<>(null, failure));
            }
        }).flatMap(Outcome::mono);
    }

    private static void cancelAfterFailure(Disposable upstream, Throwable failure) {
        // An operator may turn a synchronous subscribe throw into onError before it reaches us.
        // Cancel in both paths so an already acquired subscription cannot outlive this receipt.
        try {
            upstream.dispose();
        } catch (RuntimeException | Error cancellationFailure) {
            rethrowFatal(cancellationFailure);
            if (cancellationFailure != failure) failure.addSuppressed(cancellationFailure);
        }
    }

    record Outcome<T>(T value, Throwable failure) {
        T get() {
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            return value;
        }

        Mono<T> mono() {
            return failure == null ? Mono.justOrEmpty(value) : LifecycleCallback.failure(failure);
        }
    }
}
