package com.sstlfsj.fibra.engine;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class ReconcileCoordinator implements AutoCloseable {
    private final Object monitor = new Object();
    private final ReconcileAction action;
    private final long resyncNanos;
    private final long initialBackoffNanos;
    private final long maximumBackoffNanos;
    private final Consumer<Throwable> failureSink;
    private final ArrayDeque<Operation<?>> operations = new ArrayDeque<>();

    private boolean started;
    private boolean closed;
    private boolean dirty;
    private Thread worker;

    ReconcileCoordinator(ReconcileAction action, Duration resyncInterval,
                         Duration initialBackoff, Duration maximumBackoff,
                         Consumer<Throwable> failureSink) {
        this.action = Objects.requireNonNull(action, "action");
        this.resyncNanos = positive(resyncInterval, "resyncInterval");
        this.initialBackoffNanos = positive(initialBackoff, "initialBackoff");
        this.maximumBackoffNanos = positive(maximumBackoff, "maximumBackoff");
        if (maximumBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maximumBackoff must not be less than initialBackoff");
        }
        this.failureSink = Objects.requireNonNull(failureSink, "failureSink");
    }

    void start() {
        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("reconcile coordinator is closed");
            }
            if (started) {
                return;
            }
            started = true;
            worker = Thread.ofPlatform().daemon().name("fibra-reconcile").start(this::run);
        }
    }

    void request() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            dirty = true;
            monitor.notifyAll();
        }
    }

    <T> T execute(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        var operation = new Operation<>(action);
        synchronized (monitor) {
            if (!started) {
                throw new IllegalStateException("reconcile coordinator is not started");
            }
            if (closed) {
                throw new IllegalStateException("reconcile coordinator is closed");
            }
            if (Thread.currentThread() == worker) {
                throw new IllegalStateException(
                    "reconcile coordinator operation cannot re-enter its worker");
            }
            operations.addLast(operation);
            monitor.notifyAll();
        }
        var interrupted = false;
        while (true) {
            try {
                var result = operation.result.get();
                restoreInterrupt(interrupted);
                return result;
            } catch (InterruptedException exception) {
                synchronized (monitor) {
                    if (operations.remove(operation)) {
                        var cancellation = new IllegalStateException(
                            "interrupted before reconcile coordinator operation started",
                            exception);
                        operation.fail(cancellation);
                        Thread.currentThread().interrupt();
                        throw cancellation;
                    }
                }
                interrupted = true;
            } catch (ExecutionException exception) {
                restoreInterrupt(interrupted);
                var cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("reconcile coordinator operation failed", cause);
            }
        }
    }

    private static void restoreInterrupt(boolean interrupted) {
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        var retryNanos = initialBackoffNanos;
        var nextResync = System.nanoTime() + resyncNanos;
        while (true) {
            Operation<?> operation;
            synchronized (monitor) {
                while (!closed && operations.isEmpty() && !dirty) {
                    var remaining = nextResync - System.nanoTime();
                    if (remaining <= 0) {
                        dirty = true;
                        break;
                    }
                    waitNanos(remaining);
                }
                if (closed) {
                    return;
                }
                operation = operations.pollFirst();
                if (operation == null) {
                    dirty = false;
                }
            }
            if (operation != null) {
                operation.run();
                continue;
            }
            try {
                action.run();
                retryNanos = initialBackoffNanos;
                nextResync = System.nanoTime() + resyncNanos;
            } catch (Exception failure) {
                publishFailure(failure);
                synchronized (monitor) {
                    if (closed) {
                        return;
                    }
                    dirty = true;
                    waitNanos(retryNanos);
                }
                retryNanos = Math.min(maximumBackoffNanos,
                    retryNanos > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : retryNanos * 2);
            }
        }
    }

    private void publishFailure(Throwable failure) {
        try {
            failureSink.accept(failure);
        } catch (RuntimeException ignored) {
            // failure sink 不能终止收敛线程。
        }
    }

    private void waitNanos(long nanos) {
        try {
            var millis = nanos / 1_000_000;
            var remainder = (int) (nanos % 1_000_000);
            monitor.wait(millis, remainder);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            closed = true;
        }
    }

    @Override
    public void close() {
        Thread current;
        java.util.List<Operation<?>> pending;
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            dirty = false;
            pending = java.util.List.copyOf(operations);
            operations.clear();
            monitor.notifyAll();
            current = worker;
        }
        var closeFailure = new IllegalStateException("reconcile coordinator is closed");
        pending.forEach(operation -> operation.fail(closeFailure));
        if (current != null && current != Thread.currentThread()) {
            try {
                current.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while closing reconcile coordinator",
                    exception);
            }
        }
    }

    private static long positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value.toNanos();
    }

    private static final class Operation<T> {
        private final Supplier<T> action;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        private Operation(Supplier<T> action) {
            this.action = action;
        }

        private void run() {
            try {
                result.complete(action.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }

        private void fail(Throwable failure) {
            result.completeExceptionally(failure);
        }
    }
}
