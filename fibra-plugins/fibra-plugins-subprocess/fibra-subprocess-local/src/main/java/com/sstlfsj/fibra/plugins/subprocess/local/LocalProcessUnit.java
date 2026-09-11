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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** A supervisor owns the process group; stdin is its JVM lifetime lease. */
final class LocalProcessUnit implements ProcessUnit {
    private static final long QUIESCENCE_MILLIS = 1000;
    private final CompletableFuture<Long> started = new CompletableFuture<>();
    private final CompletableFuture<SubprocessOutcome> outcome = new CompletableFuture<>();
    private final CompletableFuture<Void> exited = new CompletableFuture<>();
    private final Mono<Void> exit = Mono.fromFuture(exited, true);
    private Process supervisor;
    private boolean stopping;
    private boolean watchingDrain;
    private long graceMillis;
    private volatile long pid;
    private EffectHandle ownership;
    private Disposable cancellationListener;

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

    void launch(String executable, SubprocessSpec spec) {
        try {
            synchronized (this) {
                if (stopping) throw new IOException("caller scope closed before launch");
                graceMillis = spec.grace().toMillis();
                String script;
                try (var source = getClass().getResourceAsStream("process-supervisor.mjs")) {
                    if (source == null) throw new IOException("missing bundled process supervisor");
                    script = new String(source.readAllBytes(), StandardCharsets.UTF_8);
                }
                var argv = new ArrayList<>(List.of(executable, "--input-type=module", "-e", script,
                    "--", Long.toString(spec.grace().toMillis()),
                    Long.toString(QUIESCENCE_MILLIS),
                    Integer.toString(spec.stdoutMaxBytes()), Integer.toString(spec.stderrMaxBytes())));
                argv.addAll(spec.argv());
                supervisor = new ProcessBuilder(argv).directory(new File(spec.cwd())).start();
                Thread.ofVirtual().name("fibra-process-control").start(this::readControl);
            }
            pid = started.get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            terminate();
            var cause = failure instanceof ExecutionException ? failure.getCause() : failure;
            var error = cause instanceof SubprocessException processError ? processError
                : new SubprocessException(SubprocessErrorCode.SPAWN_FAILED, "Unable to start process unit", cause);
            if (supervisor != null) {
                try {
                    exited.join();
                } catch (CompletionException cleanupFailure) {
                    if (cleanupFailure.getCause() instanceof SubprocessException processError) {
                        error = processError;
                    }
                }
            }
            disposeCancellationListener();
            started.completeExceptionally(error);
            outcome.completeExceptionally(error);
            synchronized (this) {
                if (supervisor == null) {
                    exited.complete(null);
                    releaseOwnership();
                }
            }
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw error;
        }
    }

    private void readControl() {
        SubprocessException failure = null;
        boolean quiescent = false;
        boolean payloadStarted = false;
        try (var reader = supervisor.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                var fields = line.split("\t", -1);
                switch (fields[0]) {
                    case "P" -> {
                        payloadStarted = true;
                        started.complete(Long.parseLong(fields[1]));
                    }
                    case "D" -> {
                        watchDrain();
                        outcome.complete(SubprocessOutcome.builder()
                            .exitCode(fields[1].isEmpty() ? null : Integer.valueOf(fields[1]))
                            .signal(fields[2].isEmpty() ? null : fields[2])
                            .stdout(output(fields, 3)).stderr(output(fields, 6)).build());
                    }
                    case "E" -> {
                        failure = new SubprocessException(SubprocessErrorCode.valueOf(fields[1]), decode(fields[2]));
                        started.completeExceptionally(failure);
                        outcome.completeExceptionally(failure);
                    }
                    case "Q" -> quiescent = true;
                    default -> throw new IOException("invalid supervisor control record");
                }
            }
            int code = supervisor.waitFor();
            if (!quiescent || code != 0) {
                throw new IOException("supervisor exited without managed-tree quiescence: " + code);
            }
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            if (failure == null) failure = new SubprocessException(payloadStarted
                ? SubprocessErrorCode.TERMINATION_FAILED : SubprocessErrorCode.SPAWN_FAILED,
                "Process supervisor failed", error);
        }
        disposeCancellationListener();
        if (failure != null) {
            started.completeExceptionally(failure);
            outcome.completeExceptionally(failure);
            // A failed payload start has no managed range to drain.
            if (failure.code() == SubprocessErrorCode.SPAWN_FAILED && (quiescent || !payloadStarted)) {
                if (exited.complete(null)) releaseOwnership();
            }
            else exited.completeExceptionally(failure);
        } else {
            if (!outcome.isDone()) outcome.completeExceptionally(new SubprocessException(
                SubprocessErrorCode.OUTPUT_FAILED, "Supervisor omitted process outcome"));
            if (exited.complete(null)) releaseOwnership();
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

    private static SubprocessOutput output(String[] fields, int offset) {
        return SubprocessOutput.builder().text(decode(fields[offset]))
            .truncated(Boolean.parseBoolean(fields[offset + 1]))
            .totalBytes(Long.parseLong(fields[offset + 2])).build();
    }

    private static String decode(String text) {
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }

    @Override
    public long pid() {
        return pid;
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
    public synchronized void terminate() {
        if (stopping) return;
        stopping = true;
        if (supervisor == null) {
            exited.complete(null);
        } else {
            watchDrain();
            try {
                supervisor.getOutputStream().close();
            } catch (IOException failure) {
                exited.completeExceptionally(new SubprocessException(
                    SubprocessErrorCode.TERMINATION_FAILED, "Unable to close process lifetime lease", failure));
            }
        }
    }

    private synchronized void watchDrain() {
        if (watchingDrain || exited.isDone()) return;
        watchingDrain = true;
        CompletableFuture.delayedExecutor(graceMillis + QUIESCENCE_MILLIS + 1000,
            TimeUnit.MILLISECONDS).execute(() -> {
            if (exited.isDone()) return;
            var failure = new SubprocessException(SubprocessErrorCode.TERMINATION_FAILED,
                "Supervisor did not confirm managed-tree quiescence within the cleanup budget");
            started.completeExceptionally(failure);
            outcome.completeExceptionally(failure);
            exited.completeExceptionally(failure);
            disposeCancellationListener();
        });
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
}
