package com.sstlfsj.fibra.engine;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

final class FibraReconcileController implements AutoCloseable {
    private final Object monitor = new Object();
    private final ReconcileAction action;
    private final long resyncNanos;
    private final long initialBackoffNanos;
    private final long maximumBackoffNanos;
    private final Consumer<Throwable> failureSink;

    private boolean started;
    private boolean closed;
    private boolean dirty;
    private Thread worker;

    FibraReconcileController(ReconcileAction action, Duration resyncInterval,
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
                throw new IllegalStateException("reconcile controller is closed");
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

    private void run() {
        var retryNanos = initialBackoffNanos;
        var nextResync = System.nanoTime() + resyncNanos;
        while (true) {
            synchronized (monitor) {
                while (!closed && !dirty) {
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
                dirty = false;
            }
            try {
                action.run();
                retryNanos = initialBackoffNanos;
                nextResync = System.nanoTime() + resyncNanos;
            } catch (Throwable failure) {
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
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            dirty = false;
            monitor.notifyAll();
            current = worker;
        }
        if (current != null && current != Thread.currentThread()) {
            try {
                current.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while closing reconcile controller",
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
}
