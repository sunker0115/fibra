package com.sstlfsj.fibra.plugins.subprocess.local;

import com.sstlfsj.fibra.plugins.subprocess.SubprocessSpec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class SystemdScopeLauncherTest {
    @Test void launchesExactLiteralArgvInTargetCwdAndWritesBoundedInvocationFrame() throws Exception {
        var process = new CapturedProcess();
        var starts = new ArrayList<List<String>>();
        var directories = new ArrayList<File>();
        var launcher = new SystemdScopeLauncher("systemd-run", "systemctl",
            argv -> new SystemdCommandResult(0, "", ""),
            (argv, directory) -> { starts.add(List.copyOf(argv)); directories.add(directory); return process; },
            () -> "fibra-test",
            Map.of("INVOCATION_ID", "0123456789abcdef0123456789abcdef"));
        var spec = SubprocessSpec.builder()
            .argv(List.of("tool", "literal arg", "'quote'", "$(touch nope)", "--"))
            .cwd("/target path").stdoutMaxBytes(10).stderrMaxBytes(11)
            .grace(Duration.ofMillis(100)).build();

        launcher.launch("/node path", spec);

        assertEquals(List.of("systemd-run", "--user", "--scope", "--quiet", "--collect",
            "--expand-environment=no", "--unit=fibra-test", "--", "/node path",
            "--input-type=module", "-e", SystemdScopeLauncher.supervisorScript(), "--",
            "linux-scope", "100", "1000", "10", "11", "tool", "literal arg", "'quote'",
            "$(touch nope)", "--"), starts.getFirst());
        assertEquals(new File("/target path"), directories.getFirst());
        assertEquals("I\t1\tMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=\n",
            process.stdin.toString(StandardCharsets.UTF_8));
        assertFalse(process.stdin.closed);
    }

    @Test void probesTheExactScopeShapeAndLightweightManagerSeparately() throws Exception {
        var calls = new ArrayList<List<String>>();
        var launcher = new SystemdScopeLauncher("systemd-run", "systemctl",
            argv -> { calls.add(List.copyOf(argv)); return new SystemdCommandResult(0, "", ""); },
            (argv, directory) -> { throw new AssertionError(); }, () -> "probe", Map.of());

        launcher.deepProbe();
        launcher.probeManager();

        assertEquals(List.of("systemd-run", "--user", "--scope", "--quiet", "--collect",
            "--expand-environment=no", "--unit=probe", "--", "systemctl", "--user", "show",
            "probe.scope", "--property=ActiveState", "--value"), calls.get(0));
        assertEquals(List.of("systemctl", "--user", "show", "--property=Version", "--value"),
            calls.get(1));
    }

    @Test void invalidInvocationIdIsRejectedBeforeExecutingTheDeepProbe() {
        var calls = new ArrayList<List<String>>();
        var launcher = new SystemdScopeLauncher("systemd-run", "systemctl",
            argv -> { calls.add(argv); return new SystemdCommandResult(0, "", ""); },
            (argv, directory) -> { throw new AssertionError(); }, () -> "probe",
            Map.of("INVOCATION_ID", "not-a-systemd-id"));

        assertThrows(java.io.IOException.class, launcher::deepProbe);
        assertEquals(List.of(), calls);
    }

    @Test void supervisorRestoresPresentAndAbsentInvocationIdBeforeSpawningTheTarget()
        throws Exception {
        assertEquals("0123456789abcdef0123456789abcdef|preserved",
            runSupervisor("I\t1\tMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=\n"));
        assertEquals("absent|preserved", runSupervisor("I\t0\t\n"));
    }

    @Test void immediateLeaseCloseCannotLoseTheSupervisorEndSignal() throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            var process = supervisor("I\t0\t\n", "setInterval(() => {}, 1000)");
            try {
                process.getOutputStream().close();
                String control = CompletableFuture.supplyAsync(() -> {
                    try {
                        return new String(process.getInputStream().readAllBytes(),
                            StandardCharsets.UTF_8);
                    } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                }).get(4, TimeUnit.SECONDS);
                assertEquals(0, process.waitFor());
                assertTrue(control.lines().anyMatch(line -> line.equals("Q")));
            } finally {
                process.destroyForcibly();
            }
        }
    }

    @Test void malformedInvocationFrameFailsBeforeSpawningTheTarget() throws Exception {
        var process = supervisor("not-a-private-frame\n",
            "process.stdout.write('target-ran')");

        String control = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.waitFor());
        assertTrue(control.lines().anyMatch(line -> line.startsWith("E\tSPAWN_FAILED\t")));
        assertFalse(control.contains("target-ran"));
    }

    @Test void invocationFrameWriteFailureTransfersTheStartedRangeForOwnedCleanup() {
        var process = new CapturedProcess(true);
        var launcher = new SystemdScopeLauncher("systemd-run", "systemctl",
            argv -> new SystemdCommandResult(0, "", ""),
            (argv, directory) -> process, () -> "fibra-test", Map.of());

        var failure = assertThrows(LinuxScopeLaunchException.class,
            () -> launcher.launch("node", SubprocessSpec.builder().argv(List.of("tool"))
                .cwd("/target").stdoutMaxBytes(10).stderrMaxBytes(10)
                .grace(Duration.ofMillis(50)).build()));

        assertSame(process, failure.launch().process());
        assertFalse(process.isAlive());
    }

    @Test void systemdCommandOutputAndCollectorFailuresStayInsideTheFixedDeadline() {
        var blocking = new BlockingInputStream();
        var command = new CompletedCommandProcess(blocking);
        var bounded = new SystemdScopeLauncher.ProcessCommands(Duration.ofMillis(50),
            argv -> command);

        assertTimeout(Duration.ofMillis(500), () -> assertThrows(java.io.IOException.class,
            () -> bounded.execute(List.of("systemctl"))));
        assertTrue(blocking.closed);
        assertTrue(command.reapWaits > 0);

        var failing = new SystemdScopeLauncher.ProcessCommands(Duration.ofMillis(100),
            argv -> new CompletedCommandProcess(new InputStream() {
                @Override public int read() throws java.io.IOException {
                    throw new java.io.IOException("read failed");
                }
            }));
        assertThrows(java.io.IOException.class,
            () -> failing.execute(List.of("systemctl")));
    }

    private static String runSupervisor(String frame) throws Exception {
        var process = supervisor(frame,
            "process.stdout.write((process.env.INVOCATION_ID ?? 'absent') + '|'"
                + " + process.env.FIBRA_ORDINARY)");
        String control = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor());
        String record = control.lines().filter(line -> line.startsWith("D\t")).findFirst().orElseThrow();
        String[] fields = record.split("\t", -1);
        return new String(java.util.Base64.getDecoder().decode(fields[3]), StandardCharsets.UTF_8);
    }

    private static Process supervisor(String frame, String targetScript) throws Exception {
        var builder = new ProcessBuilder("node", "--input-type=module", "-e",
            SystemdScopeLauncher.supervisorScript(), "--", "linux-scope", "100", "1000",
            "1024", "1024", "node", "-e", targetScript);
        builder.environment().put("INVOCATION_ID", "fedcba9876543210fedcba9876543210");
        builder.environment().put("FIBRA_ORDINARY", "preserved");
        var process = builder.start();
        process.getOutputStream().write(frame.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        return process;
    }

    private static final class CapturedProcess extends Process {
        private final CapturedOutputStream stdin;
        private boolean alive = true;

        private CapturedProcess() {
            this(false);
        }

        private CapturedProcess(boolean failWrites) {
            stdin = new CapturedOutputStream(failWrites);
        }

        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { alive = false; }
        @Override public Process destroyForcibly() { alive = false; return this; }
        @Override public boolean isAlive() { return alive; }
    }

    private static final class CapturedOutputStream extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final boolean failWrites;
        private boolean closed;

        private CapturedOutputStream(boolean failWrites) {
            this.failWrites = failWrites;
        }

        @Override public void write(int value) throws java.io.IOException {
            if (failWrites) throw new java.io.IOException("broken stdin");
            bytes.write(value);
        }

        @Override public void write(byte[] values, int offset, int length)
            throws java.io.IOException {
            if (failWrites) throw new java.io.IOException("broken stdin");
            bytes.write(values, offset, length);
        }

        private String toString(java.nio.charset.Charset charset) {
            return bytes.toString(charset);
        }

        @Override public void close() {
            closed = true;
        }
    }

    private static final class CompletedCommandProcess extends Process {
        private final InputStream stdout;
        private boolean destroyed;
        private int reapWaits;

        private CompletedCommandProcess(InputStream stdout) {
            this.stdout = stdout;
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) {
            if (destroyed) reapWaits++;
            return true;
        }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
        @Override public Process destroyForcibly() { destroyed = true; return this; }
        @Override public boolean isAlive() { return false; }
    }

    private static final class BlockingInputStream extends InputStream {
        private boolean closed;

        @Override public synchronized int read() throws java.io.IOException {
            while (!closed) {
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("interrupted", interrupted);
                }
            }
            return -1;
        }

        @Override public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }
}
