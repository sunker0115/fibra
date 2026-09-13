package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.cli.api.CliApplication;
import com.sstlfsj.fibra.cli.api.CliProfile;
import com.sstlfsj.fibra.engine.PublishedRuntime;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** 参考入口与公开 CliSession 共用的单 lane 执行、取消和关闭实现。 */
final class CliExecutionSession implements AutoCloseable {
    private final CliInvocationCoordinator invocations = new CliInvocationCoordinator();
    private final CliTerminalSession terminal;
    private final CliApplicationRunner runner;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private boolean executing;
    private boolean stopping;
    private boolean closeStarted;
    private Thread executionThread;

    CliExecutionSession(CliApplication application, Supplier<PublishedRuntime> published,
                        Supplier<CliProfile> profile, CliTerminalSession terminal,
                        CliApplicationRunner.BaseLineFactory baseLineFactory,
                        Path historyFile, List<String> rootOptions) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        runner = new CliApplicationRunner(application, published, profile, terminal, invocations,
            baseLineFactory, historyFile, rootOptions);
    }

    int execute(String... arguments) {
        Objects.requireNonNull(arguments, "arguments");
        synchronized (this) {
            if (stopping) throw new IllegalStateException("CLI session is closing or closed");
            if (executing) throw new IllegalStateException(
                "CLI session already has an active execution");
            executing = true;
            executionThread = Thread.currentThread();
        }
        try {
            return runner.execute(arguments);
        } finally {
            synchronized (this) {
                executing = false;
                executionThread = null;
                notifyAll();
            }
        }
    }

    int repl(Path historyFile, String sessionSummary, List<String> forbiddenRootOptions) {
        return runner.repl(historyFile, sessionSummary, forbiddenRootOptions);
    }

    boolean printAbove(String value) {
        Objects.requireNonNull(value, "value");
        synchronized (this) {
            if (stopping) return false;
        }
        return terminal.printAbove(value);
    }

    CliInvocationCoordinator invocations() {
        return invocations;
    }

    CompletableFuture<Void> stopAdmission() {
        synchronized (this) {
            if (stopping) return invocations.stopAdmission();
            stopping = true;
            return invocations.stopAdmission();
        }
    }

    @Override public void close() {
        final boolean owner;
        synchronized (this) {
            if (executing && executionThread == Thread.currentThread()) {
                throw new IllegalStateException(
                    "CLI session cannot close from its active execution lane");
            }
            owner = !closeStarted;
            if (owner) closeStarted = true;
        }
        if (!owner) {
            awaitClosed();
            return;
        }

        Throwable failure = null;
        var interrupted = false;
        var draining = stopAdmission();
        try {
            terminal.requestStop();
        } catch (Throwable caught) {
            failure = caught;
        }
        try {
            invocations.cancelActive();
        } catch (Throwable caught) {
            failure = merge(failure, caught);
        }
        try {
            synchronized (this) {
                while (executing) {
                    try {
                        wait();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            }
            draining.join();
        } catch (Throwable caught) {
            failure = merge(failure, caught);
        }
        try {
            terminal.close();
        } catch (Throwable caught) {
            failure = merge(failure, caught);
        }
        if (failure == null) closed.complete(null);
        else closed.completeExceptionally(failure);
        try {
            awaitClosed();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void awaitClosed() {
        try {
            closed.join();
        } catch (CompletionException failure) {
            throw new IllegalStateException("CLI session close failed", failure.getCause());
        }
    }

    private static Throwable merge(Throwable failure, Throwable cleanupFailure) {
        if (failure == null) return cleanupFailure;
        if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
        return failure;
    }
}
