package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.cli.api.CliExitStatus;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/** 将进程信号收敛为一次停止准入、取消、排空和宿主关闭。 */
final class CliProcessShutdown implements AutoCloseable {
    enum Signal {
        INT(CliExitStatus.CANCELLED.code()),
        TERM(CliExitStatus.SUCCESS.code());

        private final int exitCode;

        Signal(int exitCode) {
            this.exitCode = exitCode;
        }
    }

    private final Runnable stopAdmission;
    private final Runnable closeSession;
    private final Runnable closeHost;
    private final IntConsumer gracefulExit;
    private final IntConsumer forcedExit;
    private final Duration deadline;
    private final Executor executor;
    private final CompletableFuture<Integer> result = new CompletableFuture<>();
    private boolean started;
    private boolean gracefulExitStarted;
    private boolean forcedExitStarted;
    private boolean closed;

    CliProcessShutdown(Runnable stopAdmission, Runnable closeSession, Runnable closeHost,
                       IntConsumer exit, Duration deadline, Executor executor) {
        this(stopAdmission, closeSession, closeHost, exit, exit, deadline, executor);
    }

    CliProcessShutdown(Runnable stopAdmission, Runnable closeSession, Runnable closeHost,
                       IntConsumer gracefulExit, IntConsumer forcedExit,
                       Duration deadline, Executor executor) {
        this.stopAdmission = Objects.requireNonNull(stopAdmission, "stopAdmission");
        this.closeSession = Objects.requireNonNull(closeSession, "closeSession");
        this.closeHost = Objects.requireNonNull(closeHost, "closeHost");
        this.gracefulExit = Objects.requireNonNull(gracefulExit, "gracefulExit");
        this.forcedExit = Objects.requireNonNull(forcedExit, "forcedExit");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    void interrupt(Signal signal) {
        Objects.requireNonNull(signal, "signal");
        final long deadlineNanos;
        synchronized (this) {
            if (started || closed) return;
            started = true;
            deadlineNanos = System.nanoTime() + deadline.toNanos();
        }
        CompletableFuture.delayedExecutor(deadline.toMillis(), TimeUnit.MILLISECONDS)
            .execute(this::timeout);
        try {
            stopAdmission.run();
        } catch (RuntimeException failure) {
            failForced();
            return;
        }
        try {
            executor.execute(() -> finish(signal, deadlineNanos));
        } catch (RuntimeException failure) {
            failForced();
        }
    }

    synchronized boolean requested() {
        return started;
    }

    synchronized boolean tryCompleteNormally() {
        if (started) return false;
        closed = true;
        return true;
    }

    int awaitResult() {
        return result.join();
    }

    private void finish(Signal signal, long deadlineNanos) {
        try {
            closeSession.run();
        } catch (RuntimeException exception) {
            failForced();
            return;
        }
        if (expired(deadlineNanos)) {
            timeout();
            return;
        }

        try {
            closeHost.run();
        } catch (RuntimeException exception) {
            if (expired(deadlineNanos)) timeout();
            else completeGracefully(CliExitStatus.CLOSE_ERROR.code());
            return;
        }
        if (expired(deadlineNanos)) {
            timeout();
            return;
        }
        completeGracefully(signal.exitCode);
    }

    private static boolean expired(long deadlineNanos) {
        return System.nanoTime() - deadlineNanos >= 0;
    }

    private void timeout() {
        failForced();
    }

    private void failForced() {
        synchronized (this) {
            if (forcedExitStarted || result.isDone()) return;
            forcedExitStarted = true;
        }
        try {
            forcedExit.accept(CliExitStatus.DRAIN_TIMEOUT.code());
        } finally {
            result.complete(CliExitStatus.DRAIN_TIMEOUT.code());
        }
    }

    private void completeGracefully(int exitCode) {
        synchronized (this) {
            if (gracefulExitStarted || forcedExitStarted || result.isDone()) return;
            gracefulExitStarted = true;
        }
        try {
            gracefulExit.accept(exitCode);
        } catch (RuntimeException | Error failure) {
            failForced();
            return;
        }
        synchronized (this) {
            if (!forcedExitStarted) result.complete(exitCode);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
    }
}
