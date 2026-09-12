package com.sstlfsj.fibra.plugins.subprocess.local;

import com.sstlfsj.fibra.EffectHandle;
import com.sstlfsj.fibra.EffectMetadata;
import com.sstlfsj.fibra.logging.FibraLogger;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessErrorCode;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessException;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessSpec;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class LinuxScopeProcessUnitTest {
    @Test void qDoesNotCompleteOrReleaseUntilTheScopeIsQuiet() throws Exception {
        var process = FakeProcess.completed(records("target-err"), "systemd diagnostic", 0);
        var range = new FakeRange(true);
        var releases = new AtomicInteger();
        var unit = unit(process, range);
        unit.ownedBy(effect(releases));

        unit.launch("node", spec());

        assertEquals("target-err", unit.done().block(Duration.ofSeconds(1)).stderr().text());
        assertTrue(range.awaiting.await(1, TimeUnit.SECONDS));
        assertFalse(unit.waitForExit().toFuture().isDone());
        assertEquals(0, releases.get());
        range.quiet.countDown();
        unit.waitForExit().block(Duration.ofSeconds(1));
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (releases.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(1, releases.get());
        assertEquals(0, range.forceCalls.get());
    }

    @Test void normalTerminateOnlyClosesTheSupervisorLeaseBeforeProtocolSettlement() throws Exception {
        var process = FakeProcess.running();
        var range = new FakeRange(false);
        process.writeControl("P\t123\n");
        var unit = unit(process, range);
        unit.launch("node", spec());

        unit.terminate();

        assertTrue(process.stdin.closed);
        assertEquals(0, range.forceCalls.get());
        process.writeControl(settlementRecords("payload"));
        process.finish(0);
        unit.waitForExit().block(Duration.ofSeconds(1));
        assertEquals(0, range.forceCalls.get());
    }

    @Test void preEstablishmentExitUsesIsolatedSystemdStderrAndForcesResidualScopeCleanup() {
        var process = FakeProcess.completed("", "systemd-run: user bus unavailable", 1);
        var range = new FakeRange(false);
        var unit = unit(process, range);

        var failure = assertThrows(SubprocessException.class, () -> unit.launch("node", spec()));

        assertEquals(SubprocessErrorCode.SPAWN_FAILED, failure.code());
        assertTrue(failure.getMessage().contains("user bus unavailable"));
        assertEquals(1, range.forceCalls.get());
    }

    @Test void malformedEstablishedProtocolForcesTheScopeAndFailsTerminationLoudly() {
        var process = FakeProcess.completed("P\t123\nBROKEN\n", "", 1);
        var range = new FakeRange(false);
        var unit = unit(process, range);

        unit.launch("node", spec());
        var failure = assertThrows(SubprocessException.class,
            () -> unit.waitForExit().block(Duration.ofSeconds(1)));

        assertEquals(SubprocessErrorCode.TERMINATION_FAILED, failure.code());
        assertEquals(1, range.forceCalls.get());
    }

    @Test void startupFailureCleanupHasAFixedBudgetIndependentOfTheRequestedGrace()
        throws Exception {
        var process = FakeProcess.running();
        var range = new FakeRange(false);
        var unit = new LinuxScopeProcessUnit(
            (node, spec) -> new LinuxScopeLaunch(process, range), new NoopLogger(),
            Duration.ofMillis(10), Duration.ofMillis(50));
        var longGrace = SubprocessSpec.builder().argv(List.of("tool"))
            .cwd("/target").stdoutMaxBytes(10).stderrMaxBytes(20)
            .grace(Duration.ofDays(20)).build();
        CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS).execute(() -> {
            try {
                process.writeControl("E\tSPAWN_FAILED\tZmFpbGVk\nQ\n");
                process.finish(0);
            } catch (IOException ignored) { }
        });

        var failure = assertTimeout(Duration.ofMillis(200),
            () -> assertThrows(SubprocessException.class,
                () -> unit.launch("node", longGrace)));

        assertEquals(SubprocessErrorCode.SPAWN_FAILED, failure.code());
        assertTrue(range.forceCalls.get() > 0);
        assertTrue(range.forceGrace.compareTo(Duration.ofMillis(50)) <= 0);
        assertFalse(process.isAlive());
    }

    @Test void cleanupFailureOverridesTheOriginalSpawnFailureAndRetainsOwnership()
        throws Exception {
        var process = FakeProcess.running();
        var range = new FakeRange(false, true, process::isAlive);
        var releases = new AtomicInteger();
        var launchFailure = new LinuxScopeLaunchException("invocation frame failed",
            new IOException("broken stdin"), new LinuxScopeLaunch(process, range));
        var unit = new LinuxScopeProcessUnit((node, spec) -> { throw launchFailure; },
            new NoopLogger(), Duration.ofMillis(10), Duration.ofMillis(100));
        unit.ownedBy(effect(releases));

        var failure = assertThrows(SubprocessException.class,
            () -> unit.launch("node", spec()));

        assertEquals(SubprocessErrorCode.TERMINATION_FAILED, failure.code());
        assertEquals(0, releases.get());
        assertEquals(1, range.forceCalls.get());
    }

    @Test void watchdogCleanupFailureKeepsTheOriginalBudgetFailureAsEvidence()
        throws Exception {
        var process = FakeProcess.running();
        var range = new FakeRange(false, true, process::isAlive);
        var unit = new LinuxScopeProcessUnit(
            (node, spec) -> new LinuxScopeLaunch(process, range), new NoopLogger(),
            Duration.ofMillis(10), Duration.ofMillis(100));

        var failure = assertThrows(SubprocessException.class,
            () -> unit.launch("node", spec()));

        assertEquals(SubprocessErrorCode.TERMINATION_FAILED, failure.code());
        assertTrue(java.util.Arrays.stream(failure.getSuppressed())
            .anyMatch(error -> error.getMessage().contains(
                "Linux scope did not establish within the startup budget")));
    }

    @Test void forcedScopeObservationWaitsForTheDirectLauncherToExit() throws Exception {
        var process = FakeProcess.running(50);
        var range = new FakeRange(false, false, process::isAlive);
        var unit = new LinuxScopeProcessUnit(
            (node, spec) -> new LinuxScopeLaunch(process, range), new NoopLogger(),
            Duration.ofMillis(10), Duration.ofMillis(200));

        assertThrows(SubprocessException.class, () -> unit.launch("node", spec()));

        assertFalse(range.forceSawDirectAlive);
        assertEquals(1, range.forceCalls.get());
    }

    @Test void controlProtocolRejectsInvalidTransitionsAndRecordsAfterQuiet() {
        String outcome = "D\t0\t\t\tfalse\t0\t\tfalse\t0\n";
        assertEquals(SubprocessErrorCode.SPAWN_FAILED,
            protocolFailure(outcome + "Q\n").code());
        for (var control : List.of(
            "P\t123\nP\t124\n" + outcome + "Q\n",
            "P\t123\n" + outcome + outcome + "Q\n",
            "P\t123\n" + outcome + "Q\nP\t124\n",
            "P\t123\nE\tOUTPUT_FAILED\tZmFpbGVk\nE\tOUTPUT_FAILED\tZmFpbGVk\nQ\n",
            "P\t123\n" + outcome + "Q\nQ\n")) {
            assertEquals(SubprocessErrorCode.TERMINATION_FAILED,
                protocolFailure(control).code(), control);
        }
    }

    @Test void initAcceptsOnlySpawnFailure() {
        assertEquals(SubprocessErrorCode.SPAWN_FAILED,
            protocolFailure("E\tSPAWN_FAILED\tZmFpbGVk\nQ\n").code());
        assertEquals(SubprocessErrorCode.SPAWN_FAILED,
            protocolFailure("E\tTERMINATION_FAILED\tZmFpbGVk\nQ\n").code());
    }

    @Test void outcomeMayBeFollowedOnlyByTerminationFailureWhileDoneStaysPublished() {
        var process = FakeProcess.completed(records("target")
            .replace("Q\n", "E\tTERMINATION_FAILED\tY2xlYW51cA==\nQ\n"), "", 0);
        var unit = unit(process, new FakeRange(false));

        unit.launch("node", spec());

        assertEquals(0, unit.done().block(Duration.ofSeconds(1)).exitCode());
        assertEquals(SubprocessErrorCode.TERMINATION_FAILED,
            assertThrows(SubprocessException.class,
                () -> unit.waitForExit().block(Duration.ofSeconds(1))).code());
        assertEquals(SubprocessErrorCode.TERMINATION_FAILED,
            protocolFailure("P\t123\nD\t0\t\t\tfalse\t0\t\tfalse\t0\n"
                + "E\tOUTPUT_FAILED\tZmFpbGVk\nQ\n").code());
    }

    @Test void failureMayOnlyUpgradeToTerminationFailure() {
        var process = FakeProcess.completed("P\t123\nE\tOUTPUT_FAILED\tb3V0cHV0\n"
            + "E\tTERMINATION_FAILED\tY2xlYW51cA==\n", "", 1);
        var unit = unit(process, new FakeRange(false));

        unit.launch("node", spec());

        assertEquals(SubprocessErrorCode.OUTPUT_FAILED,
            assertThrows(SubprocessException.class,
                () -> unit.done().block(Duration.ofSeconds(1))).code());
        var exit = assertThrows(SubprocessException.class,
            () -> unit.waitForExit().block(Duration.ofSeconds(1)));
        assertEquals(SubprocessErrorCode.TERMINATION_FAILED, exit.code());
        assertTrue(exit.getSuppressed().length > 0);

        assertEquals(SubprocessErrorCode.SPAWN_FAILED,
            protocolFailure("E\tOUTPUT_FAILED\tZmFpbGVk\nQ\n").code());
        assertEquals(SubprocessErrorCode.TERMINATION_FAILED,
            protocolFailure("P\t123\nE\tSPAWN_FAILED\tZmFpbGVk\nQ\n").code());
        var repeatedTermination = protocolFailure(
            "P\t123\nE\tOUTPUT_FAILED\tb3V0cHV0\n"
                + "E\tTERMINATION_FAILED\tY2xlYW51cA==\n"
                + "E\tTERMINATION_FAILED\tY2xlYW51cC0y\nQ\n");
        assertTrue(repeatedTermination.getMessage().contains(
            "Linux scope supervisor failed after reporting an error"));
    }

    @Test void unterminatedPartialControlRecordIsRejected() {
        assertEquals(SubprocessErrorCode.SPAWN_FAILED,
            protocolFailure("P\t123").code());
    }

    @Test void cleanupFailureKeepsTheSupervisorFailureAsEvidence() {
        var process = FakeProcess.completed("P\t123\nBROKEN\n", "", 1);
        var range = new FakeRange(false, true, process::isAlive);
        var unit = unit(process, range);

        unit.launch("node", spec());
        var failure = assertThrows(SubprocessException.class,
            () -> unit.waitForExit().block(Duration.ofSeconds(1)));

        assertEquals(SubprocessErrorCode.TERMINATION_FAILED, failure.code());
        assertTrue(failure.getSuppressed().length > 0);
        assertEquals(SubprocessErrorCode.TERMINATION_FAILED,
            ((SubprocessException) failure.getSuppressed()[0]).code());
    }

    @Test void controlProtocolRejectsMalformedOutcomeValuesAndOversizedRecords() {
        for (var outcome : List.of(
            "D\t\t\t\tfalse\t0\t\tfalse\t0\n",
            "D\t0\tSIGTERM\t\tfalse\t0\t\tfalse\t0\n",
            "D\t0\t\t\tmaybe\t0\t\tfalse\t0\n",
            "D\t0\t\t\tfalse\t-1\t\tfalse\t0\n")) {
            assertEquals(SubprocessErrorCode.TERMINATION_FAILED,
                protocolFailure("P\t123\n" + outcome + "Q\n").code());
        }
        assertEquals(SubprocessErrorCode.TERMINATION_FAILED,
            protocolFailure("P\t123\n" + "X".repeat(65 * 1024) + "\n").code());
    }

    private static LinuxScopeProcessUnit unit(FakeProcess process, FakeRange range) {
        return new LinuxScopeProcessUnit((node, spec) -> new LinuxScopeLaunch(process, range),
            new NoopLogger());
    }

    private static SubprocessException protocolFailure(String control) {
        var unit = unit(FakeProcess.completed(control, "", 0), new FakeRange(false));
        try {
            unit.launch("node", spec());
        } catch (SubprocessException failure) {
            return failure;
        }
        return assertThrows(SubprocessException.class,
            () -> unit.waitForExit().block(Duration.ofSeconds(1)));
    }

    private static String records(String stderr) {
        return "P\t123\n" + settlementRecords(stderr);
    }

    private static String settlementRecords(String stderr) {
        String encoded = java.util.Base64.getEncoder().encodeToString(stderr.getBytes(StandardCharsets.UTF_8));
        return "D\t0\t\t\tfalse\t0\t" + encoded + "\tfalse\t"
            + stderr.getBytes(StandardCharsets.UTF_8).length + "\nQ\n";
    }

    private static SubprocessSpec spec() {
        return SubprocessSpec.builder().argv(List.of("tool", "literal arg"))
            .cwd("/target").stdoutMaxBytes(10).stderrMaxBytes(20)
            .grace(Duration.ofMillis(50)).build();
    }

    private static EffectHandle effect(AtomicInteger releases) {
        return new EffectHandle() {
            @Override public Mono<EffectHandle> ready() { return Mono.just(this); }
            @Override public boolean isDisposed() { return releases.get() > 0; }
            @Override public EffectMetadata metadata() { return new EffectMetadata("test", List.of()); }
            @Override public Mono<Void> dispose() { releases.incrementAndGet(); return Mono.empty(); }
        };
    }

    private static final class FakeRange implements LinuxScopeRange {
        private final CountDownLatch awaiting = new CountDownLatch(1);
        private final CountDownLatch quiet = new CountDownLatch(1);
        private final AtomicInteger forceCalls = new AtomicInteger();
        private final boolean block;
        private final boolean failForce;
        private final BooleanSupplier directAlive;
        private volatile Duration forceGrace = Duration.ZERO;
        private volatile boolean forceSawDirectAlive;

        private FakeRange(boolean block) {
            this(block, false, () -> false);
        }

        private FakeRange(boolean block, boolean failForce, BooleanSupplier directAlive) {
            this.block = block;
            this.failForce = failForce;
            this.directAlive = directAlive;
        }

        @Override public void established() { }

        @Override public void awaitQuietAfterExit(Duration grace) throws Exception {
            awaiting.countDown();
            if (block) assertTrue(quiet.await(2, TimeUnit.SECONDS));
        }

        @Override public void forceQuiet(Duration grace) throws IOException {
            forceGrace = grace;
            forceSawDirectAlive = directAlive.getAsBoolean();
            forceCalls.incrementAndGet();
            if (failForce) throw new IOException("scope cleanup failed");
        }
    }

    private static final class FakeProcess extends Process {
        private final InputStream control;
        private final InputStream diagnostic;
        private final PipedOutputStream controlWriter;
        private final LeaseOutputStream stdin = new LeaseOutputStream();
        private final CountDownLatch exited = new CountDownLatch(1);
        private volatile int exitCode;
        private volatile boolean alive;
        private final long forcedExitDelayMillis;

        private FakeProcess(InputStream control, InputStream diagnostic,
                            PipedOutputStream controlWriter, boolean alive, int exitCode,
                            long forcedExitDelayMillis) {
            this.control = control;
            this.diagnostic = diagnostic;
            this.controlWriter = controlWriter;
            this.alive = alive;
            this.exitCode = exitCode;
            this.forcedExitDelayMillis = forcedExitDelayMillis;
            if (!alive) exited.countDown();
        }

        private static FakeProcess completed(String control, String diagnostic, int exitCode) {
            return new FakeProcess(bytes(control), bytes(diagnostic), null, false, exitCode, 0);
        }

        private static FakeProcess running() throws IOException {
            return running(0);
        }

        private static FakeProcess running(long forcedExitDelayMillis) throws IOException {
            var input = new PipedInputStream();
            return new FakeProcess(input, bytes(""), new PipedOutputStream(input), true, 0,
                forcedExitDelayMillis);
        }

        private void writeControl(String value) throws IOException {
            controlWriter.write(value.getBytes(StandardCharsets.UTF_8));
            controlWriter.flush();
        }

        private void finish(int code) throws IOException {
            exitCode = code;
            alive = false;
            controlWriter.close();
            exited.countDown();
        }

        private static InputStream bytes(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getInputStream() { return control; }
        @Override public InputStream getErrorStream() { return diagnostic; }
        @Override public int waitFor() throws InterruptedException { exited.await(); return exitCode; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exited.await(timeout, unit);
        }
        @Override public int exitValue() { return exitCode; }
        @Override public void destroy() { finishUnchecked(143); }
        @Override public Process destroyForcibly() {
            if (forcedExitDelayMillis == 0) finishUnchecked(137);
            else CompletableFuture.delayedExecutor(forcedExitDelayMillis, TimeUnit.MILLISECONDS)
                .execute(() -> finishUnchecked(137));
            return this;
        }
        @Override public boolean isAlive() { return alive; }

        private void finishUnchecked(int code) {
            try { finish(code); } catch (IOException ignored) { }
        }
    }

    private static final class LeaseOutputStream extends ByteArrayOutputStream {
        private boolean closed;

        @Override public void close() throws IOException {
            super.close();
            closed = true;
        }
    }

    private static final class NoopLogger implements FibraLogger {
        @Override public String name() { return "test"; }
        @Override public void error(Object... arguments) { }
        @Override public void info(Object... arguments) { }
        @Override public void warn(Object... arguments) { }
        @Override public void debug(Object... arguments) { }
    }
}
