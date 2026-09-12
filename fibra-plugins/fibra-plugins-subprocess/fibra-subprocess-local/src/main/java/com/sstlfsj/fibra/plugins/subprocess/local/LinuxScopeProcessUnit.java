package com.sstlfsj.fibra.plugins.subprocess.local;

import com.sstlfsj.fibra.CancellationToken;
import com.sstlfsj.fibra.EffectHandle;
import com.sstlfsj.fibra.logging.FibraLogger;
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
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** P/E/D/Q supervisor bound to one Linux user-systemd scope. */
final class LinuxScopeProcessUnit implements ProcessUnit {
    private static final int DIAGNOSTIC_LIMIT = 64 * 1024;
    private static final long QUIESCENCE_MILLIS = 1000;
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration STARTUP_CLEANUP_BUDGET = Duration.ofSeconds(5);
    private final LinuxScopeLaunchFactory launcher;
    private final FibraLogger logger;
    private final Duration startupTimeout;
    private final Duration startupCleanupBudget;
    private final CompletableFuture<Void> started = new CompletableFuture<>();
    private final CompletableFuture<SubprocessOutcome> outcome = new CompletableFuture<>();
    private final CompletableFuture<Void> exited = new CompletableFuture<>();
    private final Mono<Void> exit = Mono.fromFuture(exited, true);
    private final AtomicBoolean finalizing = new AtomicBoolean();
    private Process supervisor;
    private LinuxScopeRange range;
    private DiagnosticCollector diagnostic;
    private Duration grace;
    private long maxControlRecordChars;
    private boolean stopping;
    private boolean watchingDrain;
    private volatile boolean targetStarted;
    private EffectHandle ownership;
    private Disposable cancellationListener;

    LinuxScopeProcessUnit(LinuxScopeLaunchFactory launcher, FibraLogger logger) {
        this(launcher, logger, STARTUP_TIMEOUT, STARTUP_CLEANUP_BUDGET);
    }

    LinuxScopeProcessUnit(LinuxScopeLaunchFactory launcher, FibraLogger logger,
                          Duration startupTimeout, Duration startupCleanupBudget) {
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.startupTimeout = Objects.requireNonNull(startupTimeout, "startupTimeout");
        this.startupCleanupBudget = Objects.requireNonNull(
            startupCleanupBudget, "startupCleanupBudget");
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

    void launch(String nodeExecutable, SubprocessSpec spec) {
        try {
            synchronized (this) {
                if (stopping) throw new IOException("caller scope closed before launch");
                grace = spec.grace();
                maxControlRecordChars = Math.max(DIAGNOSTIC_LIMIT,
                    64L + encodedLength(spec.stdoutMaxBytes())
                        + encodedLength(spec.stderrMaxBytes()));
                var launched = launcher.launch(nodeExecutable, spec);
                supervisor = launched.process();
                range = launched.range();
                diagnostic = new DiagnosticCollector(supervisor.getErrorStream());
                Thread.ofVirtual().name("fibra-linux-scope-control").start(this::readControl);
            }
            started.get(startupTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (Exception failure) {
            if (failure instanceof LinuxScopeLaunchException incomplete) {
                synchronized (this) {
                    supervisor = incomplete.launch().process();
                    range = incomplete.launch().range();
                    diagnostic = new DiagnosticCollector(supervisor.getErrorStream());
                }
            }
            terminate();
            var cause = failure instanceof ExecutionException ? failure.getCause() : failure;
            var error = cause instanceof SubprocessException processError ? processError
                : new SubprocessException(SubprocessErrorCode.SPAWN_FAILED,
                    "Unable to start Linux systemd scope", cause);
            if (supervisor != null) {
                try {
                    exited.get(startupCleanupBudget.toNanos(), TimeUnit.NANOSECONDS);
                } catch (ExecutionException cleanupFailure) {
                    if (cleanupFailure.getCause() instanceof SubprocessException processError) {
                        if (processError != error) processError.addSuppressed(error);
                        error = processError;
                    }
                } catch (java.util.concurrent.TimeoutException cleanupTimeout) {
                    var cleanupError = new SubprocessException(
                        SubprocessErrorCode.TERMINATION_FAILED,
                        "Linux startup cleanup exceeded its fixed budget", cleanupTimeout);
                    cleanupError.addSuppressed(error);
                    error = cleanupError;
                } catch (InterruptedException cleanupInterrupted) {
                    Thread.currentThread().interrupt();
                    var cleanupError = new SubprocessException(
                        SubprocessErrorCode.TERMINATION_FAILED,
                        "Interrupted before Linux startup cleanup was proven", cleanupInterrupted);
                    cleanupError.addSuppressed(error);
                    error = cleanupError;
                }
            } else {
                exited.complete(null);
                releaseOwnership();
                disposeCancellationListener();
            }
            started.completeExceptionally(error);
            outcome.completeExceptionally(error);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw error;
        }
    }

    private void readControl() {
        SubprocessException failure = null;
        boolean legalRecord = false;
        boolean payloadStarted = false;
        boolean quiescent = false;
        ControlState state = ControlState.INIT;
        try (var reader = new InputStreamReader(supervisor.getInputStream(),
            StandardCharsets.UTF_8)) {
            String line;
            while ((line = readControlLine(reader)) != null) {
                var fields = line.split("\t", -1);
                switch (fields[0]) {
                    case "P" -> {
                        requireState(state, ControlState.INIT);
                        requireFields(fields, 2);
                        long payloadPid = Long.parseLong(fields[1]);
                        if (payloadPid <= 0) throw new IOException("invalid supervisor payload pid");
                        state = ControlState.RUNNING;
                        legalRecord = true;
                        payloadStarted = true;
                        targetStarted = true;
                        range.established();
                        started.complete(null);
                    }
                    case "D" -> {
                        requireState(state, ControlState.RUNNING);
                        requireFields(fields, 9);
                        var reported = reportedOutcome(fields);
                        state = ControlState.OUTCOME;
                        legalRecord = true;
                        range.established();
                        watchDrain();
                        outcome.complete(reported);
                    }
                    case "E" -> {
                        requireFields(fields, 3);
                        var code = SubprocessErrorCode.valueOf(fields[1]);
                        if (!allowsError(state, code, failure)) {
                            throw new IOException("invalid supervisor control transition");
                        }
                        var reported = new SubprocessException(
                            code, decode(fields[2]));
                        if (failure != null) reported.addSuppressed(failure);
                        state = ControlState.FAILED;
                        legalRecord = true;
                        range.established();
                        failure = reported;
                        started.completeExceptionally(reported);
                        outcome.completeExceptionally(reported);
                    }
                    case "Q" -> {
                        if (state != ControlState.OUTCOME && state != ControlState.FAILED) {
                            throw new IOException("invalid supervisor control transition");
                        }
                        requireFields(fields, 1);
                        state = ControlState.QUIET;
                        legalRecord = true;
                        range.established();
                        quiescent = true;
                    }
                    default -> throw new IOException("invalid supervisor control record");
                }
            }
            int code = supervisor.waitFor();
            String systemdDiagnostic = diagnostic.await();
            if (!legalRecord) {
                failure = new SubprocessException(SubprocessErrorCode.SPAWN_FAILED,
                    "Linux systemd scope exited before supervisor establishment"
                        + diagnosticSuffix(systemdDiagnostic));
            } else if (state != ControlState.QUIET || !quiescent || code != 0) {
                throw new IOException("supervisor exited without managed-group quiescence: " + code);
            }
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            if (failure == null) {
                failure = new SubprocessException(payloadStarted || legalRecord
                    ? SubprocessErrorCode.TERMINATION_FAILED : SubprocessErrorCode.SPAWN_FAILED,
                    "Linux scope supervisor failed", error);
            } else {
                var protocolFailure = new SubprocessException(
                    SubprocessErrorCode.TERMINATION_FAILED,
                    "Linux scope supervisor failed after reporting an error", error);
                protocolFailure.addSuppressed(failure);
                failure = protocolFailure;
            }
        }
        finish(failure, !quiescent || failure != null, legalRecord, quiescent, payloadStarted);
    }

    private void finish(SubprocessException failure, boolean force,
                        boolean legalRecord, boolean quiescent, boolean payloadStarted) {
        if (!finalizing.compareAndSet(false, true)) return;
        boolean quiet = false;
        try {
            if (force) {
                forceDirectExit();
                range.forceQuiet(cleanupGrace(payloadStarted));
            } else {
                range.awaitQuietAfterExit(grace);
            }
            quiet = true;
        } catch (Exception cleanupFailure) {
            if (cleanupFailure instanceof InterruptedException) Thread.currentThread().interrupt();
            var scopeFailure = new SubprocessException(SubprocessErrorCode.TERMINATION_FAILED,
                "Unable to prove Linux systemd scope quiescence", cleanupFailure);
            if (failure != null) scopeFailure.addSuppressed(failure);
            failure = scopeFailure;
        }
        var collector = diagnostic;
        if (collector != null && collector.failure() != null) {
            logger.warn("Unable to drain systemd-run diagnostic stderr", collector.failure());
        }
        if (failure != null) {
            started.completeExceptionally(failure);
            outcome.completeExceptionally(failure);
        } else if (!outcome.isDone()) {
            outcome.completeExceptionally(new SubprocessException(SubprocessErrorCode.OUTPUT_FAILED,
                "Supervisor omitted process outcome"));
        }
        if (quiet) {
            if (failure == null || failure.code() == SubprocessErrorCode.SPAWN_FAILED
                && (quiescent || !legalRecord)) {
                exited.complete(null);
            } else {
                exited.completeExceptionally(failure);
            }
            releaseOwnership();
            disposeCancellationListener();
        } else if (failure != null) {
            exited.completeExceptionally(failure);
        }
    }

    private static void requireFields(String[] fields, int count) throws IOException {
        if (fields.length != count) throw new IOException("invalid supervisor control record shape");
    }

    private static void requireState(ControlState actual, ControlState expected)
        throws IOException {
        if (actual != expected) throw new IOException("invalid supervisor control transition");
    }

    private static boolean allowsError(ControlState state, SubprocessErrorCode code,
                                       SubprocessException failure) {
        return switch (state) {
            case INIT -> code == SubprocessErrorCode.SPAWN_FAILED;
            case RUNNING -> code == SubprocessErrorCode.OUTPUT_FAILED
                || code == SubprocessErrorCode.TERMINATION_FAILED;
            case OUTCOME -> code == SubprocessErrorCode.TERMINATION_FAILED;
            case FAILED -> failure != null
                && failure.code() == SubprocessErrorCode.OUTPUT_FAILED
                && code == SubprocessErrorCode.TERMINATION_FAILED;
            case QUIET -> false;
        };
    }

    private String readControlLine(Reader reader) throws IOException {
        var line = new StringBuilder();
        int value;
        while ((value = reader.read()) >= 0) {
            if (value == '\n') return line.toString();
            if (line.length() >= maxControlRecordChars) {
                throw new IOException("supervisor control record exceeds its configured limit");
            }
            line.append((char) value);
        }
        if (line.isEmpty()) return null;
        throw new IOException("unterminated supervisor control record");
    }

    private static long encodedLength(int bytes) {
        return ((bytes + 2L) / 3L) * 4L;
    }

    private static String diagnosticSuffix(String diagnostic) {
        return diagnostic.isBlank() ? "" : ": " + diagnostic;
    }

    private static SubprocessOutcome reportedOutcome(String[] fields) throws IOException {
        boolean hasExitCode = !fields[1].isEmpty();
        boolean hasSignal = !fields[2].isEmpty();
        if (hasExitCode == hasSignal) {
            throw new IOException("process outcome must report exactly one of exitCode or signal");
        }
        Integer exitCode = null;
        if (hasExitCode) {
            try {
                exitCode = Integer.valueOf(fields[1]);
            } catch (NumberFormatException invalid) {
                throw new IOException("invalid process exitCode", invalid);
            }
            if (exitCode < 0) throw new IOException("process exitCode must not be negative");
        }
        return SubprocessOutcome.builder().exitCode(exitCode)
            .signal(hasSignal ? fields[2] : null)
            .stdout(output(fields, 3)).stderr(output(fields, 6)).build();
    }

    private static SubprocessOutput output(String[] fields, int offset) throws IOException {
        if (!fields[offset + 1].equals("true") && !fields[offset + 1].equals("false")) {
            throw new IOException("invalid process output truncation flag");
        }
        long totalBytes;
        try {
            totalBytes = Long.parseLong(fields[offset + 2]);
        } catch (NumberFormatException invalid) {
            throw new IOException("invalid process output byte count", invalid);
        }
        if (totalBytes < 0) throw new IOException("process output byte count must not be negative");
        return SubprocessOutput.builder().text(decode(fields[offset]))
            .truncated(Boolean.parseBoolean(fields[offset + 1]))
            .totalBytes(totalBytes).build();
    }

    private static String decode(String text) {
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
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

    private synchronized void watchDrain() {
        if (watchingDrain || exited.isDone()) return;
        watchingDrain = true;
        boolean startedAtScheduling = targetStarted;
        long delayMillis = startedAtScheduling
            ? grace.toMillis() + QUIESCENCE_MILLIS + 1000 : 0;
        CompletableFuture.delayedExecutor(delayMillis,
            TimeUnit.MILLISECONDS).execute(() -> {
            if (exited.isDone() || !finalizing.compareAndSet(false, true)) return;
            var failure = new SubprocessException(startedAtScheduling
                ? SubprocessErrorCode.TERMINATION_FAILED : SubprocessErrorCode.SPAWN_FAILED,
                startedAtScheduling
                    ? "Supervisor did not settle the Linux systemd scope within the cleanup budget"
                    : "Linux scope did not establish within the startup budget");
            boolean quiet = false;
            try {
                forceDirectExit();
                range.forceQuiet(cleanupGrace(startedAtScheduling));
                quiet = true;
            } catch (Exception cleanupFailure) {
                var cleanupError = new SubprocessException(SubprocessErrorCode.TERMINATION_FAILED,
                    "Unable to prove Linux startup cleanup", cleanupFailure);
                cleanupError.addSuppressed(failure);
                failure = cleanupError;
            }
            started.completeExceptionally(failure);
            outcome.completeExceptionally(failure);
            exited.completeExceptionally(failure);
            if (quiet) {
                releaseOwnership();
                disposeCancellationListener();
            }
        });
    }

    private Duration cleanupGrace(boolean started) {
        return started || grace.compareTo(startupCleanupBudget) <= 0
            ? grace : startupCleanupBudget;
    }

    private void forceDirectExit() throws Exception {
        if (!supervisor.isAlive()) return;
        supervisor.destroyForcibly();
        if (!supervisor.waitFor(startupCleanupBudget.toNanos(), TimeUnit.NANOSECONDS)) {
            throw new IOException("Linux scope launcher did not exit after forced termination");
        }
    }

    @Override public Mono<SubprocessOutcome> done() { return Mono.fromFuture(outcome, true); }

    @Override public Mono<Void> waitForExit() { return exit; }

    @Override public synchronized void terminate() {
        if (stopping) return;
        stopping = true;
        if (supervisor == null) {
            exited.complete(null);
            return;
        }
        watchDrain();
        try {
            supervisor.getOutputStream().close();
        } catch (IOException failure) {
            var error = new SubprocessException(SubprocessErrorCode.TERMINATION_FAILED,
                "Unable to close process lifetime lease", failure);
            started.completeExceptionally(error);
            outcome.completeExceptionally(error);
        }
    }

    @Override public Mono<Void> drain() { terminate(); return exit; }

    @Override public Mono<Void> dispose() { terminate(); return exit; }

    private static final class DiagnosticCollector {
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private volatile Throwable failure;

        private DiagnosticCollector(java.io.InputStream input) {
            Thread.ofVirtual().name("fibra-systemd-run-stderr").start(() -> {
                try (input) {
                    var tail = new ByteArrayOutputStream();
                    var buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (count == 0) continue;
                        tail.write(buffer, 0, count);
                        if (tail.size() > DIAGNOSTIC_LIMIT) {
                            var bytes = tail.toByteArray();
                            tail.reset();
                            tail.write(bytes, bytes.length - DIAGNOSTIC_LIMIT, DIAGNOSTIC_LIMIT);
                        }
                    }
                    result.complete(tail.toString(StandardCharsets.UTF_8));
                } catch (IOException error) {
                    failure = error;
                    result.complete("");
                }
            });
        }

        private String await() throws Exception {
            return result.get(5, TimeUnit.SECONDS);
        }

        private Throwable failure() {
            return failure;
        }
    }

    private enum ControlState { INIT, RUNNING, OUTCOME, FAILED, QUIET }
}
