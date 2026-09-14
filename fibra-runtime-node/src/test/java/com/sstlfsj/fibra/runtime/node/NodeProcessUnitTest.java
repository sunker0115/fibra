package com.sstlfsj.fibra.runtime.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeProcessUnitTest {
    @Test
    void settlesActualPayloadSpawnFailureWithoutLeavingStdoutOpen(@TempDir Path work)
        throws Exception {
        var session = Files.createDirectory(work.resolve("session"));
        var status = session.resolve("termination.status");
        var supervisorScript = session.resolve("supervisor.mjs");
        try (var resource = NodeProcessUnit.class.getResourceAsStream(
            "/com/sstlfsj/fibra/runtime/node/node-process-supervisor.mjs")) {
            // 使用真实 spawn 的 ENOENT，注入只影响监督器为 payload 选择的可执行文件。
            Files.writeString(supervisorScript,
                "process.execPath = new URL('./missing-node', import.meta.url).pathname;\n"
                    + new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        }
        var process = new ProcessBuilder(node().toString(), supervisorScript.toString(),
            work.resolve("unused-entrypoint.mjs").toString(), "1000", status.toString())
            .directory(session.toFile()).start();
        var unit = new NodeProcessUnit(session, status, process, Duration.ofSeconds(1));
        var output = CompletableFuture.supplyAsync(() -> readOutput(unit));
        try {
            assertEquals("", output.get(3, TimeUnit.SECONDS));
            unit.onExit().get(3, TimeUnit.SECONDS);
            assertEquals(0, unit.exitValue());
            assertTrue(unit.finalState().payload().startFailure().contains("ENOENT"));
        } finally {
            unit.input().close();
            unit.close();
        }
        assertFalse(Files.exists(session));
        assertTrue(unit.finalState().payload().startFailure().contains("ENOENT"));
    }

    @Test
    void terminatesManagedPayloadWhenHostClosesStdout(@TempDir Path work) throws Exception {
        assertManagedPayloadTerminatesAfterOutputFailure(work, false);
    }

    @Test
    void terminatesManagedPayloadWhenHostClosesBothOutputStreams(@TempDir Path work)
        throws Exception {
        assertManagedPayloadTerminatesAfterOutputFailure(work, true);
    }

    private void assertManagedPayloadTerminatesAfterOutputFailure(Path work, boolean closeStderr)
        throws Exception {
        var marker = work.resolve("payload.pid");
        var script = work.resolve("broken-output.mjs");
        Files.writeString(script, """
            import fs from 'node:fs';
            fs.writeFileSync('%s', String(process.pid));
            process.stdout.on('error', () => {});
            process.stdin.resume();
            process.stdin.once('data', () => {
              setInterval(() => process.stdout.write('x'.repeat(65536)), 5);
            });
            setTimeout(() => process.exit(0), 15000);
            """.formatted(marker.toString().replace("\\", "\\\\")));
        var sessions = work.resolve("sessions");
        var unit = NodeProcessUnit.launch(script, NodeRuntimeOptions.builder(node(), sessions)
            .terminateTimeout(Duration.ofSeconds(1)).build());
        ProcessHandle payload = null;
        try {
            var deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (Files.notExists(marker) && System.nanoTime() < deadline) Thread.sleep(10);
            payload = ProcessHandle.of(Long.parseLong(Files.readString(marker))).orElseThrow();
            unit.output().close();
            if (closeStderr) unit.error().close();
            unit.input().write("start\n".getBytes(StandardCharsets.UTF_8));
            unit.input().flush();
            unit.onExit().get(5, TimeUnit.SECONDS);
            assertFalse(payload.isAlive(), "host 关闭读端后监督器仍须清理受管 payload");
            assertEquals(1, unit.exitValue());
            assertThrows(NodeRpcException.class, unit::close);
            assertEquals("QUIESCENT", unit.finalState().range());
            try (var paths = Files.list(sessions)) {
                assertTrue(paths.findAny().isPresent());
            }
        } finally {
            if (payload != null && payload.isAlive()) {
                payload.destroyForcibly();
                payload.onExit().get(5, TimeUnit.SECONDS);
            }
            unit.input().close();
            try {
                unit.close();
            } catch (NodeRpcException expected) {
                // 输出传输失败应保留会话，finally 只负责终止真实进程。
            }
        }
    }

    @Test
    void retainsUnknownPayloadOutcomeWhenRangeTerminationFails(@TempDir Path work)
        throws Exception {
        var session = Files.createDirectory(work.resolve("session"));
        var status = session.resolve("termination.status");
        Files.writeString(status, "{\"payload\":null,\"range\":\"FAILED\"}");
        var process = new TestProcess(status, false);
        process.exitCode = 1;
        process.complete();
        var unit = new NodeProcessUnit(session, status, process, Duration.ofMillis(10));

        assertThrows(NodeRpcException.class, unit::close);
        assertNull(unit.finalState().payload());
        assertEquals("FAILED", unit.finalState().range());
        assertTrue(Files.exists(session));
    }

    @Test
    void preservesSignalledPayloadOutcomeAfterSessionCleanup(@TempDir Path work)
        throws Exception {
        var session = Files.createDirectory(work.resolve("session"));
        var status = session.resolve("termination.status");
        Files.writeString(status,
            "{\"payload\":{\"kind\":\"SIGNALLED\",\"signal\":\"SIGTERM\"},\"range\":\"QUIESCENT\"}");
        var process = new TestProcess(status, false);
        process.complete();
        var unit = new NodeProcessUnit(session, status, process, Duration.ofMillis(10));

        unit.close();
        assertFalse(Files.exists(session));
        assertEquals("SIGTERM", unit.finalState().payload().signal());
        assertNull(unit.finalState().payload().exitCode());
    }

    @Test
    void drainsALargeStdoutTailBeforePublishingEof(@TempDir Path work) throws Exception {
        var script = work.resolve("large-tail.mjs");
        Files.writeString(script, """
            import fs from 'node:fs';
            const output = fs.createWriteStream(null, {fd: 1, autoClose: true});
            output.end('x'.repeat(4 * 1024 * 1024) + 'tail-end');
            """);
        var sessions = work.resolve("sessions");
        var unit = NodeProcessUnit.launch(script, NodeRuntimeOptions.defaults(node(), sessions));
        var output = CompletableFuture.supplyAsync(() -> readOutput(unit));
        try (AutoCloseable cleanup = () -> closeWithDiagnostics(unit, sessions)) {
            assertEquals("x".repeat(4 * 1024 * 1024) + "tail-end",
                output.get(10, TimeUnit.SECONDS));
            unit.onExit().get(5, TimeUnit.SECONDS);
        }
    }

    private static void closeWithDiagnostics(NodeProcessUnit unit, Path sessions) throws Exception {
        try (unit) {
            unit.input().close();
        } catch (Exception | Error failure) {
            try {
                failure.addSuppressed(new AssertionError(processDiagnostics(unit, sessions)));
            } catch (Exception | Error diagnosticFailure) {
                failure.addSuppressed(diagnosticFailure);
            }
            throw failure;
        }
    }

    private static String processDiagnostics(NodeProcessUnit unit, Path sessions) throws Exception {
        var diagnostic = new StringBuilder("supervisor exit=")
            .append(unit.isAlive() ? "RUNNING" : unit.exitValue());
        try (var paths = Files.list(sessions)) {
            for (var session : paths.toList()) {
                var status = session.resolve("termination.status");
                diagnostic.append(", status path=").append(status).append(", raw=");
                if (Files.exists(status)) {
                    try (var content = Files.newInputStream(status)) {
                        diagnostic.append(new String(content.readNBytes(16384), StandardCharsets.UTF_8));
                    }
                } else {
                    diagnostic.append("MISSING");
                }
            }
        }
        var stderr = unit.error();
        var available = unit.isAlive() ? Math.min(stderr.available(), 16384) : 16384;
        diagnostic.append(", stderr=")
            .append(new String(stderr.readNBytes(available), StandardCharsets.UTF_8));
        return diagnostic.toString();
    }

    @Test
    void separatesPayloadExitFromSupervisorExit(@TempDir Path work) throws Exception {
        var sessions = work.resolve("sessions");
        var script = work.resolve("exit-seven.mjs");
        Files.writeString(script, "process.exit(7);\n");
        var unit = NodeProcessUnit.launch(script, NodeRuntimeOptions.defaults(node(), sessions));
        try {
            unit.onExit().get(5, TimeUnit.SECONDS);
            assertEquals(0, unit.exitValue(), "监督成功不能复制 payload 的业务退出码");
            Path session;
            try (var paths = Files.list(sessions)) {
                session = paths.findFirst().orElseThrow();
            }
            var state = JsonMapper.builder().build().readTree(
                Files.readString(session.resolve("termination.status")));
            assertEquals(7, state.path("payload").path("exitCode").intValue());
            assertEquals("QUIESCENT", state.path("range").stringValue());
        } finally {
            unit.input().close();
            unit.close();
        }
        assertEquals(7, unit.finalState().payload().exitCode());
        assertEquals("QUIESCENT", unit.finalState().range());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "QUIESCENT\n", "not-json", "{}",
        "{\"range\":\"QUIESCENT\"}",
        "{\"payload\":{\"kind\":\"EXITED\",\"exitCode\":0}}",
        "{\"payload\":{\"kind\":\"EXITED\",\"exitCode\":0},\"range\":\"FAILED\"}",
        "{\"payload\":{\"kind\":\"EXITED\",\"exitCode\":0},\"range\":\"UNKNOWN\"}",
        "{\"payload\":{\"kind\":\"EXITED\",\"exitCode\":0},\"range\":\"QUIESCENT\",\"extra\":true}",
        "{\"payload\":{\"kind\":\"EXITED\",\"exitCode\":0},\"range\":\"FAILED\",\"range\":\"QUIESCENT\"}",
        "{\"payload\":{\"kind\":\"UNKNOWN\",\"exitCode\":0},\"range\":\"QUIESCENT\"}",
        "{\"payload\":{\"kind\":\"EXITED\"},\"range\":\"QUIESCENT\"}",
        "{\"payload\":{\"kind\":\"EXITED\",\"exitCode\":\"0\"},\"range\":\"QUIESCENT\"}",
        "{\"payload\":{\"kind\":\"EXITED\",\"exitCode\":0.5},\"range\":\"QUIESCENT\"}",
        "{\"payload\":{\"kind\":\"EXITED\",\"exitCode\":null},\"range\":\"QUIESCENT\"}",
        "{\"payload\":{\"kind\":\"EXITED\",\"exitCode\":0,\"signal\":\"SIGTERM\"},\"range\":\"QUIESCENT\"}",
        "{\"payload\":{\"kind\":\"EXITED\",\"exitCode\":7,\"exitCode\":0},\"range\":\"QUIESCENT\"}",
        "{\"payload\":{\"kind\":\"SIGNALLED\",\"signal\":\"\"},\"range\":\"QUIESCENT\"}",
        "{\"payload\":{\"kind\":\"START_FAILED\",\"error\":\"\"},\"range\":\"QUIESCENT\"}",
        "{\"payload\":{\"kind\":\"EXITED\",\"exitCode\":0},\"range\":\"QUIESCENT\"} {}"
    })
    void rejectsInvalidOrFailedFinalStatesAndRetainsSession(String content, @TempDir Path work)
        throws Exception {
        var session = Files.createDirectory(work.resolve("session"));
        var status = session.resolve("termination.status");
        Files.writeString(status, content);
        var process = new TestProcess(status, false);
        process.complete();
        var unit = new NodeProcessUnit(session, status, process, Duration.ofMillis(10));

        assertThrows(NodeRpcException.class, unit::close);
        assertTrue(Files.exists(session));
    }

    @Test
    void acceptsKnownStartFailureWhenManagedRangeIsQuiescent(@TempDir Path work)
        throws Exception {
        var session = Files.createDirectory(work.resolve("session"));
        var status = session.resolve("termination.status");
        Files.writeString(status,
            "{\"payload\":{\"kind\":\"START_FAILED\",\"error\":\"ENOENT\"},\"range\":\"QUIESCENT\"}");
        var process = new TestProcess(status, false);
        process.complete();
        var unit = new NodeProcessUnit(session, status, process, Duration.ofMillis(10));

        unit.close();
        assertFalse(Files.exists(session));
        assertEquals("ENOENT", unit.finalState().payload().startFailure());
    }

    @Test
    void retainsSessionWhenSupervisorFailsDespiteQuiescenceProof(@TempDir Path work)
        throws Exception {
        var session = Files.createDirectory(work.resolve("session"));
        var status = session.resolve("termination.status");
        var process = new TestProcess(status, true);
        process.exitCode = 1;
        process.complete();
        var unit = new NodeProcessUnit(session, status, process, Duration.ofMillis(10));

        assertThrows(NodeRpcException.class, unit::close);
        assertTrue(Files.exists(session));
    }

    private static Path node() {
        return Path.of(System.getProperty("fibra.test.node", "node"));
    }

    private static String readOutput(NodeProcessUnit unit) {
        try {
            return new String(unit.output().readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Test
    void forwardsStdoutTailAndEofWhilePayloadAndSupervisorRemainAlive(@TempDir Path work)
        throws Exception {
        var script = work.resolve("close-stdout.mjs");
        Files.writeString(script, """
            import fs from 'node:fs';
            fs.writeSync(1, 'complete-tail\\n');
            fs.closeSync(1);
            process.stdin.resume();
            process.stdin.on('data', () => process.stderr.write('stderr-after-eof\\n'));
            const lifetime = setTimeout(() => process.exit(0), 15000);
            process.stdin.on('end', () => {
              clearTimeout(lifetime);
              process.exit(0);
            });
            """);
        var options = NodeRuntimeOptions.builder(
                Path.of(System.getProperty("fibra.test.node", "node")), work.resolve("sessions"))
            .terminateTimeout(Duration.ofSeconds(1)).build();
        var unit = NodeProcessUnit.launch(script, options);
        var output = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(unit.output().readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
        try {
            var tail = assertDoesNotThrow(() -> output.get(3, TimeUnit.SECONDS),
                "payload 已关闭 fd1，host 必须在 supervisor 退出前读到真实 EOF");
            assertEquals("complete-tail\n", tail);
            assertTrue(unit.isAlive(), "stdout EOF 不能依赖 supervisor 退出");
            unit.input().write("after-eof\n".getBytes(StandardCharsets.UTF_8));
            unit.input().flush();
            var stderr = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(unit.error().readNBytes("stderr-after-eof\n".length()),
                        StandardCharsets.UTF_8);
                } catch (java.io.IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
            assertEquals("stderr-after-eof\n", stderr.get(3, TimeUnit.SECONDS));
        } finally {
            unit.input().close();
            unit.close();
            output.get(3, TimeUnit.SECONDS);
        }
    }

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
        private int exitCode;
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
                    Files.writeString(status,
                        "{\"payload\":{\"kind\":\"EXITED\",\"exitCode\":0},\"range\":\"QUIESCENT\"}");
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
            return exitCode;
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
