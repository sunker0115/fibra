package com.sstlfsj.fibra.runtime.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeProcessUnitTest {
    @Test
    void rejectsMissingManagedRangeQuiescenceProofAndRetainsTheSession(@TempDir Path work)
        throws Exception {
        var session = Files.createDirectory(work.resolve("session"));
        var status = session.resolve("termination.status");
        var process = new TestProcess(status, false);
        process.complete();
        var unit = new NodeProcessUnit(session, status, process, Duration.ofMillis(10));

        assertThrows(NodeRpcException.class, unit::close);
        assertTrue(Files.exists(session), "无法证明范围静默时必须保留诊断现场");
    }

    @Test
    void interruptionDoesNotShortCircuitManagedRangeQuiescence(@TempDir Path work)
        throws Exception {
        var session = Files.createDirectory(work.resolve("session"));
        var status = session.resolve("termination.status");
        var process = new TestProcess(status, true);
        Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(50);
                process.complete();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        });
        var unit = new NodeProcessUnit(session, status, process, Duration.ofSeconds(1));

        Thread.currentThread().interrupt();
        try {
            unit.close();
            assertFalse(process.isAlive());
            assertFalse(Files.exists(session));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void normalizesUnexpectedCleanupFailuresAndRetainsTheSession(@TempDir Path work)
        throws Exception {
        var session = Files.createDirectory(work.resolve("session"));
        var status = session.resolve("termination.status");
        var process = new TestProcess(status, false, true);
        var unit = new NodeProcessUnit(session, status, process, Duration.ofMillis(1));

        var failure = assertThrows(NodeRpcException.class, unit::close);
        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertTrue(Files.exists(session));
    }

    private static final class TestProcess extends Process {
        private final Path status;
        private final boolean proveQuiescence;
        private final boolean failDestroy;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final CompletableFuture<Process> exit = new CompletableFuture<>();
        private final OutputStream input = new ByteArrayOutputStream();

        private TestProcess(Path status, boolean proveQuiescence) {
            this(status, proveQuiescence, false);
        }

        private TestProcess(Path status, boolean proveQuiescence, boolean failDestroy) {
            this.status = status;
            this.proveQuiescence = proveQuiescence;
            this.failDestroy = failDestroy;
        }

        private void complete() {
            try {
                if (proveQuiescence) {
                    Files.writeString(status, "QUIESCENT\n");
                }
            } catch (java.io.IOException failure) {
                throw new UncheckedIOException(failure);
            }
            alive.set(false);
            exit.complete(this);
        }

        @Override public OutputStream getOutputStream() { return input; }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { exit.join(); return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                exit.get(timeout, unit);
                return true;
            } catch (java.util.concurrent.TimeoutException failure) {
                return false;
            } catch (java.util.concurrent.ExecutionException failure) {
                throw new IllegalStateException(failure);
            }
        }
        @Override public int exitValue() {
            if (alive.get()) throw new IllegalThreadStateException();
            return 0;
        }
        @Override public void destroy() {
            if (failDestroy) throw new IllegalStateException("destroy failed");
        }
        @Override public Process destroyForcibly() { return this; }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public CompletableFuture<Process> onExit() { return exit; }
        @Override public long pid() { return 123L; }
    }
}
