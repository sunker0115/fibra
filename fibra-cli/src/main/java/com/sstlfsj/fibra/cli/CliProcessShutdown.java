package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.cli.api.CliExitStatus;

import java.io.PrintWriter;
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

    private final CliInvocationCoordinator invocations;
    private final Runnable closeHost;
    private final IntConsumer gracefulExit;
    private final IntConsumer forcedExit;
    private final PrintWriter diagnostics;
    private final Duration deadline;
    private final Executor executor;
    private final CompletableFuture<Integer> result = new CompletableFuture<>();
    private boolean started;
    private boolean closed;

    CliProcessShutdown(CliInvocationCoordinator invocations, Runnable closeHost, IntConsumer exit,
                       PrintWriter diagnostics, Duration deadline, Executor executor) {
        this(invocations, closeHost, exit, exit, diagnostics, deadline, executor);
    }

    CliProcessShutdown(CliInvocationCoordinator invocations, Runnable closeHost,
                       IntConsumer gracefulExit, IntConsumer forcedExit,
                       PrintWriter diagnostics, Duration deadline, Executor executor) {
        this.invocations = Objects.requireNonNull(invocations, "invocations");
        this.closeHost = Objects.requireNonNull(closeHost, "closeHost");
        this.gracefulExit = Objects.requireNonNull(gracefulExit, "gracefulExit");
        this.forcedExit = Objects.requireNonNull(forcedExit, "forcedExit");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
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
            invocations.stopAdmission();
        }
        CompletableFuture.delayedExecutor(deadline.toMillis(), TimeUnit.MILLISECONDS)
            .execute(this::timeout);
        try {
            executor.execute(() -> finish(signal, deadlineNanos));
        } catch (RuntimeException failure) {
            failForced("无法启动关闭协调");
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
        final CompletableFuture<Void> drained;
        try {
            drained = invocations.stopAndCancel();
            drained.join();
        } catch (RuntimeException exception) {
            failForced("调用排空失败");
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
            else failGracefully("关闭宿主失败", CliExitStatus.CLOSE_ERROR.code());
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
        failForced("调用排空超时或宿主关闭超时");
    }

    private void failGracefully(String diagnostic, int exitCode) {
        if (!result.complete(exitCode)) return;
        diagnostics.println(diagnostic);
        gracefulExit.accept(exitCode);
    }

    private void failForced(String diagnostic) {
        if (!result.complete(CliExitStatus.DRAIN_TIMEOUT.code())) return;
        diagnostics.println(diagnostic);
        forcedExit.accept(CliExitStatus.DRAIN_TIMEOUT.code());
    }

    private void completeGracefully(int exitCode) {
        if (!result.complete(exitCode)) return;
        gracefulExit.accept(exitCode);
    }

    @Override
    public synchronized void close() {
        closed = true;
    }
}
