package com.sstlfsj.fibra.plugins.subprocess.local;

import com.sstlfsj.fibra.CancellationToken;
import com.sstlfsj.fibra.EffectHandle;
import com.sstlfsj.fibra.plugins.subprocess.ProcessUnit;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessErrorCode;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessException;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessOutcome;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessOutput;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessSpec;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Windows process unit backed by one kill-on-close Job owner. */
final class WindowsJobProcessUnit implements ProcessUnit {
    private final WindowsJobOwnerFactory owners;
    private final CompletableFuture<SubprocessOutcome> outcome = new CompletableFuture<>();
    private final CompletableFuture<Void> exited = new CompletableFuture<>();
    private final Mono<Void> exit = Mono.fromFuture(exited, true);
    private WindowsJobOwner owner;
    private EffectHandle ownership;
    private Disposable cancellationListener;
    private boolean stopping;
    private boolean terminationSent;

    WindowsJobProcessUnit(WindowsJobOwnerFactory owners) {
        this.owners = Objects.requireNonNull(owners, "owners");
    }

    synchronized void ownedBy(EffectHandle ownership) {
        this.ownership = Objects.requireNonNull(ownership, "ownership");
    }

    void cancelOn(CancellationToken cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        var listener = cancellation.cancelled()
            .subscribe(ignored -> { }, ignored -> terminate(), this::terminate);
        synchronized (this) {
            if (exited.isDone()) listener.dispose();
            else cancellationListener = listener;
        }
        if (cancellation.isCancelled()) terminate();
    }

    void launch(SubprocessSpec spec) {
        Objects.requireNonNull(spec, "spec");
        synchronized (this) {
            if (stopping) {
                var failure = spawnFailure("caller scope closed before launch", null);
                outcome.completeExceptionally(failure);
                exited.complete(null);
                disposeCancellationListener();
                releaseOwnership();
                throw failure;
            }
        }
        final WindowsJobOwner launched;
        try {
            launched = Objects.requireNonNull(owners.create(spec),
                "Windows Job owner factory returned null");
        } catch (Exception | LinkageError failure) {
            var error = spawnFailure("Unable to start Windows Job process", failure);
            outcome.completeExceptionally(error);
            exited.complete(null);
            disposeCancellationListener();
            releaseOwnership();
            throw error;
        }
        boolean terminate;
        synchronized (this) {
            owner = launched;
            terminate = stopping;
        }
        watchDirect(spec, launched);
        watchRange(launched);
        if (terminate) terminateOwner(launched);
    }

    private void watchDirect(SubprocessSpec spec, WindowsJobOwner launched) {
        var stdout = collect(launched.stdout(), spec.stdoutMaxBytes());
        var stderr = collect(launched.stderr(), spec.stderrMaxBytes());
        Thread.ofVirtual().name("fibra-windows-process").start(() -> {
            try {
                int exitCode = launched.waitForDirectExit();
                awaitOutputDrain(stdout, stderr, spec.grace().toNanos());
                outcome.complete(SubprocessOutcome.builder().exitCode(exitCode)
                    .stdout(stdout.result().join()).stderr(stderr.result().join()).build());
            } catch (CompletionException failure) {
                failDirect(outputFailure(failure.getCause()));
            } catch (Exception | LinkageError failure) {
                failDirect(terminationFailure("Unable to observe direct Windows process", failure));
            }
        });
    }

    private void watchRange(WindowsJobOwner launched) {
        Thread.ofVirtual().name("fibra-windows-job").start(() -> {
            try {
                launched.waitForEmpty();
                launched.close();
                exited.complete(null);
            } catch (Exception | LinkageError failure) {
                closeAfterFailure(launched, failure);
                exited.completeExceptionally(terminationFailure(
                    "Unable to prove Windows Job quiescence", failure));
            } finally {
                disposeCancellationListener();
                releaseOwnership();
            }
        });
    }

    private static OutputCollector collect(InputStream input, int limit) {
        return new OutputCollector(input, limit);
    }

    private static void awaitOutputDrain(OutputCollector stdout, OutputCollector stderr,
                                         long graceNanos) throws IOException {
        try {
            CompletableFuture.allOf(stdout.result(), stderr.result())
                .get(graceNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            stdout.seal();
            stderr.seal();
        } catch (ExecutionException failure) {
            throw new CompletionException(failure.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while draining Windows process output", interrupted);
        }
    }

    private void failDirect(SubprocessException failure) {
        outcome.completeExceptionally(failure);
        terminate();
    }

    @Override
    public Mono<SubprocessOutcome> done() {
        return Mono.fromFuture(outcome, true);
    }

    @Override
    public Mono<Void> waitForExit() {
        return exit;
    }

    @Override
    public void terminate() {
        WindowsJobOwner current;
        synchronized (this) {
            stopping = true;
            if (terminationSent || owner == null) return;
            terminationSent = true;
            current = owner;
        }
        terminateOwner(current);
    }

    private void terminateOwner(WindowsJobOwner current) {
        synchronized (this) {
            if (!terminationSent) terminationSent = true;
        }
        try {
            current.terminate();
        } catch (Exception | LinkageError failure) {
            var error = terminationFailure("Unable to terminate Windows Job", failure);
            outcome.completeExceptionally(error);
            exited.completeExceptionally(error);
            closeAfterFailure(current, failure);
            disposeCancellationListener();
            releaseOwnership();
        }
    }

    private static void closeAfterFailure(WindowsJobOwner owner, Throwable failure) {
        try {
            owner.close();
        } catch (Exception | LinkageError closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private void releaseOwnership() {
        EffectHandle handle;
        synchronized (this) {
            handle = ownership;
            ownership = null;
        }
        if (handle != null) handle.dispose();
    }

    private void disposeCancellationListener() {
        Disposable listener;
        synchronized (this) {
            listener = cancellationListener;
            cancellationListener = null;
        }
        if (listener != null) listener.dispose();
    }

    private static SubprocessException spawnFailure(String message, Throwable cause) {
        return new SubprocessException(SubprocessErrorCode.SPAWN_FAILED, message, cause);
    }

    private static SubprocessException outputFailure(Throwable cause) {
        return new SubprocessException(SubprocessErrorCode.OUTPUT_FAILED,
            "Unable to collect Windows process output", cause);
    }

    private static SubprocessException terminationFailure(String message, Throwable cause) {
        return new SubprocessException(SubprocessErrorCode.TERMINATION_FAILED, message, cause);
    }

    @Override
    public Mono<Void> drain() {
        terminate();
        return exit;
    }

    @Override
    public Mono<Void> dispose() {
        terminate();
        return exit;
    }

    private static final class OutputCollector {
        private final InputStream input;
        private final int limit;
        private final ByteArrayOutputStream tail = new ByteArrayOutputStream();
        private final CompletableFuture<SubprocessOutput> result = new CompletableFuture<>();
        private long total;
        private boolean sealed;

        private OutputCollector(InputStream input, int limit) {
            this.input = input;
            this.limit = limit;
            Thread.ofVirtual().name("fibra-windows-output").start(this::read);
        }

        private void read() {
            try {
                var buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count == 0) continue;
                    synchronized (this) {
                        if (sealed) return;
                        total += count;
                        tail.write(buffer, 0, count);
                        if (tail.size() > limit) {
                            var bytes = tail.toByteArray();
                            tail.reset();
                            tail.write(bytes, bytes.length - limit, limit);
                        }
                    }
                }
                complete();
            } catch (IOException | LinkageError failure) {
                synchronized (this) {
                    if (!sealed) result.completeExceptionally(failure);
                }
            } finally {
                closeInput();
            }
        }

        private CompletableFuture<SubprocessOutput> result() {
            return result;
        }

        private void seal() {
            complete();
            closeInput();
        }

        private synchronized void complete() {
            if (sealed) return;
            sealed = true;
            result.complete(SubprocessOutput.builder()
                .text(tail.toString(StandardCharsets.UTF_8))
                .truncated(total > limit).totalBytes(total).build());
        }

        private void closeInput() {
            try {
                input.close();
            } catch (IOException ignored) {
                // A sealed collector already has the final bounded output snapshot.
            }
        }
    }

}
