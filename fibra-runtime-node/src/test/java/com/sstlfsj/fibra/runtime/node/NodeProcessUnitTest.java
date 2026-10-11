package com.sstlfsj.fibra.runtime.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeProcessUnitTest {
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void supervisorProbeLauncherPreservesRealPayloadAndBundledResource(@TempDir Path work)
        throws Exception {
        var fixture = GroupProbeFixture.launch(work, false);
        try {
            fixture.unit.input().close();
            assertDoesNotThrow(fixture.unit::close);
            assertEquals("QUIESCENT", fixture.unit.finalState().range());
            assertEquals(0, fixture.unit.finalState().payload().exitCode());
            fixture.awaitNativeAbsence();
            assertFalse(Files.exists(fixture.session));
        } finally {
            fixture.cleanupAndArchive();
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void closeWaitsForUnknownGroupProbeToBecomeNativelyAbsent(@TempDir Path work)
        throws Exception {
        var fixture = GroupProbeFixture.launch(work, true);
        CompletableFuture<Void> closing = null;
        try {
            fixture.unit.input().close();
            closing = CompletableFuture.runAsync(fixture.unit::close);
            fixture.awaitTrace("INJECTED_EPERM");
            assertTrue(Files.exists(fixture.session),
                "范围查询 UNKNOWN 时必须保留 session owner");
            assertFalse(closing.isDone(), "范围查询 UNKNOWN 时 close 不能提前完成");
            Files.writeString(fixture.release, "release\n");
            fixture.awaitNativeAbsence();
            var closeResult = closing;
            assertDoesNotThrow(() -> closeResult.get(3, TimeUnit.SECONDS),
                "原 termination budget 内 UNKNOWN 转为真实 ABSENT 后 close 应成功释放 owner");
            assertEquals("QUIESCENT", fixture.unit.finalState().range());
            assertFalse(Files.exists(fixture.session));
        } finally {
            fixture.cleanupAndArchive();
            if (closing != null) closing.handle((result, failure) -> null).get(3, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"term-eperm", "kill-eperm"})
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void closeRecoversWhenASingleRangeSignalIsPermissionBlocked(String mode, @TempDir Path work)
        throws Exception {
        var fixture = GroupProbeFixture.launch(work, mode);
        try {
            fixture.unit.input().close();
            var closing = CompletableFuture.runAsync(fixture.unit::close);
            var signal = mode.startsWith("term") ? "SIGTERM" : "SIGKILL";
            fixture.awaitTrace("INJECTED_EPERM operation=" + signal);
            assertFalse(closing.isDone());
            assertTrue(Files.exists(fixture.session));
            fixture.release();
            fixture.awaitNativeAbsence();
            assertDoesNotThrow(() -> closing.get(3, TimeUnit.SECONDS));
            assertEquals("QUIESCENT", fixture.unit.finalState().range());
            fixture.assertSignalCount("SIGTERM", 1);
            fixture.assertSignalCount("SIGKILL", mode.startsWith("kill") ? 1 : 0);
            fixture.assertNoOperationAfterAbsence();
        } finally {
            fixture.cleanupAndArchive();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"term-absent", "kill-absent"})
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void nativeSignalEsrchIsAnAbsorbingRangeProof(String mode, @TempDir Path work)
        throws Exception {
        var fixture = GroupProbeFixture.launch(work, mode);
        try {
            fixture.unit.input().close();
            assertDoesNotThrow(fixture.unit::close);
            assertEquals("QUIESCENT", fixture.unit.finalState().range());
            fixture.awaitTrace("NATIVE_ABSENT operation="
                + (mode.startsWith("term") ? "SIGTERM" : "SIGKILL"));
            fixture.assertSignalCount("SIGTERM", 1);
            fixture.assertSignalCount("SIGKILL", mode.startsWith("kill") ? 1 : 0);
            fixture.assertNoOperationAfterAbsence();
        } finally {
            fixture.cleanupAndArchive();
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void acceptedSignalStillNeedsAnAbsenceObservation(@TempDir Path work) throws Exception {
        var fixture = GroupProbeFixture.launch(work, "accepted-term");
        try {
            fixture.unit.input().close();
            fixture.unit.close();
            fixture.awaitTrace("NATIVE_ACCEPTED operation=SIGTERM");
            fixture.awaitTrace("NATIVE_ABSENT operation=0");
            fixture.assertSignalCount("SIGTERM", 1);
            fixture.assertSignalCount("SIGKILL", 0);
            fixture.assertNoOperationAfterAbsence();
        } finally {
            fixture.cleanupAndArchive();
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void unknownBecomingPresentDoesNotReleaseTheRangeOwner(@TempDir Path work) throws Exception {
        var fixture = GroupProbeFixture.launch(work, "unknown-present");
        try {
            fixture.unit.input().close();
            var closing = CompletableFuture.runAsync(fixture.unit::close);
            fixture.awaitTrace("INJECTED_EPERM operation=0");
            Files.writeString(work.toRealPath().resolve("probe-release"), "release\n");
            fixture.awaitTrace("NATIVE_PRESENT operation=0");
            assertFalse(closing.isDone(), "PRESENT 不能当作 ABSENT");
            assertTrue(Files.exists(fixture.session));
            fixture.release();
            closing.get(3, TimeUnit.SECONDS);
            assertEquals("QUIESCENT", fixture.unit.finalState().range());
        } finally {
            fixture.cleanupAndArchive();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown-dead", "present-live"})
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void unresolvedRangeFailsAtTheOriginalBudgetAndCachesFailure(String mode, @TempDir Path work)
        throws Exception {
        var fixture = GroupProbeFixture.launch(work, mode);
        try {
            fixture.unit.input().close();
            var closing = CompletableFuture.runAsync(fixture.unit::close);
            fixture.awaitTrace("STATUS_WRITTEN range=FAILED");
            fixture.assertOriginalRangeBudget();
            if (mode.equals("present-live")) {
                fixture.assertNativePresent();
                assertFalse(closing.isDone(), "Java close 仍须等待真实 supervisor 退出");
            } else {
                fixture.awaitNativeAbsence();
            }
            fixture.release();
            var first = (NodeRpcException) assertThrows(java.util.concurrent.ExecutionException.class,
                () -> closing.get(3, TimeUnit.SECONDS)).getCause();
            assertEquals(NodeRpcPhase.TERMINATE, first.phase());
            assertSame(first, assertThrows(NodeRpcException.class, fixture.unit::close));
            var repeated = CompletableFuture.runAsync(fixture.unit::close);
            assertSame(first, assertThrows(java.util.concurrent.ExecutionException.class,
                () -> repeated.get(1, TimeUnit.SECONDS)).getCause());
            assertTrue(Files.exists(fixture.session));
            fixture.assertSignalCount("SIGTERM", 1);
            fixture.assertSignalCount("SIGKILL", 1);
            if (mode.equals("unknown-dead")) {
                var stderr = fixture.error.get(1, TimeUnit.SECONDS);
                assertEquals(1, stderr.lines().filter(line -> line.contains("first blocked")).count());
                assertTrue(stderr.contains("operation=0"));
                assertTrue(stderr.contains("last blocked"));
                assertTrue(stderr.length() < 1024, "永久 EPERM 诊断必须有界");
            }
        } finally {
            fixture.cleanupAndArchive();
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void otherRangeErrnoStillFailsWithoutBeingAbsorbedAsUnknown(@TempDir Path work) throws Exception {
        var fixture = GroupProbeFixture.launch(work, "other-errno");
        try {
            fixture.unit.input().close();
            var closing = CompletableFuture.runAsync(fixture.unit::close);
            fixture.awaitTrace("STATUS_WRITTEN range=FAILED");
            fixture.release();
            assertThrows(java.util.concurrent.ExecutionException.class,
                () -> closing.get(3, TimeUnit.SECONDS));
            assertTrue(Files.exists(fixture.session));
            fixture.assertSignalCount("SIGTERM", 0);
            fixture.assertSignalCount("SIGKILL", 0);
            assertTrue(fixture.error.get(1, TimeUnit.SECONDS).contains("fixture range EIO"));
        } finally {
            fixture.cleanupAndArchive();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"status-failure", "output-failure"})
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void recoveredRangeDoesNotEraseAnIndependentSupervisorFailure(String mode, @TempDir Path work)
        throws Exception {
        var fixture = GroupProbeFixture.launch(work, mode);
        try {
            if (mode.equals("status-failure")) {
                Files.createDirectory(fixture.session.resolve("termination.status"));
            }
            fixture.unit.input().close();
            var closing = CompletableFuture.runAsync(fixture.unit::close);
            fixture.awaitTrace("INJECTED_EPERM operation=0");
            if (mode.equals("output-failure")) {
                fixture.unit.output().close();
                Files.writeString(work.toRealPath().resolve("write-output"), "write\n");
                fixture.awaitTrace("OUTPUT_FAILED");
            }
            fixture.release();
            fixture.awaitTrace("NATIVE_ABSENT operation=0");
            assertThrows(java.util.concurrent.ExecutionException.class,
                () -> closing.get(3, TimeUnit.SECONDS));
            assertTrue(Files.exists(fixture.session));
            var error = fixture.error.get(1, TimeUnit.SECONDS);
            assertTrue(error.contains(mode.equals("status-failure")
                ? "termination status write failed" : "supervisor output failed"));
        } finally {
            fixture.cleanupAndArchive();
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void concurrentAndInterruptedCloseShareOnePhysicalRangeCleanup(@TempDir Path work) throws Exception {
        var fixture = GroupProbeFixture.launch(work, true);
        var interrupted = new AtomicBoolean();
        var result = new CompletableFuture<Void>();
        try {
            fixture.unit.input().close();
            var observer = Thread.ofPlatform().start(() -> {
                Thread.currentThread().interrupt();
                try {
                    fixture.unit.close();
                    interrupted.set(Thread.currentThread().isInterrupted());
                    result.complete(null);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            fixture.awaitTrace("INJECTED_EPERM operation=0");
            var concurrent = CompletableFuture.runAsync(fixture.unit::close);
            assertFalse(result.isDone());
            assertFalse(concurrent.isDone());
            fixture.release();
            result.get(3, TimeUnit.SECONDS);
            concurrent.get(3, TimeUnit.SECONDS);
            observer.join(1000);
            assertTrue(interrupted.get(), "调用方中断应恢复，但不能取消物理关闭");
            var trace = Files.readString(fixture.trace);
            fixture.unit.close();
            assertEquals(trace, Files.readString(fixture.trace), "重复 close 不能重新操作旧 PGID");
            fixture.assertNoOperationAfterAbsence();
        } finally {
            fixture.cleanupAndArchive();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"request-unknown", "request-unknown-dead"})
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void pendingRequestAndCancelledCloseObserverRetainSharedRangeCleanup(String mode, @TempDir Path work)
        throws Exception {
        var fixture = GroupProbeFixture.launch(work, mode);
        var scheduler = new java.util.concurrent.ScheduledThreadPoolExecutor(1);
        var constructor = NodeSidecar.class.getDeclaredConstructor(NodeRuntimeOptions.class,
            NodeProcessUnit.class, Runnable.class, java.util.concurrent.ScheduledExecutorService.class);
        constructor.setAccessible(true);
        var sidecar = constructor.newInstance(NodeRuntimeOptions.builder(node(), work)
            .terminateTimeout(fixture.budget).build(), fixture.unit, (Runnable) () -> { }, scheduler);
        try {
            var request = sidecar.beginRequest("unanswered", java.util.Map.of(),
                com.sstlfsj.fibra.CancellationToken.never());
            var result = request.result().toFuture();
            GroupProbeFixture.awaitFile(work.toRealPath().resolve("request-started"));
            // drain 会发起取消，须先确认原请求已送达 payload。
            var drain = request.drain().toFuture();
            var cancelled = sidecar.closeAsync().toFuture();
            fixture.awaitTrace("INJECTED_EPERM operation=0");
            assertTrue(cancelled.cancel(true));
            var observing = sidecar.closeAsync().toFuture();
            assertFalse(observing.isDone(), "取消观察不能取消同一次物理关闭");
            assertFalse(result.isDone(), "未取得原请求终态时 UNKNOWN 不能结算结果");
            assertFalse(drain.isDone(), "未取得原请求终态时 UNKNOWN 不能确认资源排空");
            assertTrue(Files.exists(fixture.session));
            if (mode.endsWith("dead")) {
                fixture.awaitTrace("STATUS_WRITTEN range=FAILED");
                fixture.assertOriginalRangeBudget();
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> observing.get(3, TimeUnit.SECONDS)).getCause();
                assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class,
                    drain::join).getCause());
                assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class,
                    result::join).getCause());
                assertSame(failure, assertThrows(NodeRpcException.class, sidecar::close));
                assertTrue(Files.exists(fixture.session));
            } else {
                fixture.release();
                observing.get(3, TimeUnit.SECONDS);
                drain.get(1, TimeUnit.SECONDS);
                assertThrows(java.util.concurrent.CompletionException.class, result::join);
                sidecar.close();
                assertFalse(Files.exists(fixture.session));
                fixture.assertNoOperationAfterAbsence();
            }
        } finally {
            fixture.cleanupAndArchive();
            scheduler.shutdownNow();
        }
    }

    /** 仅 supervisor 使用 --require；payload 和原 materialized resource 均不改写。 */
    private static final class GroupProbeFixture {
        private final Path work;
        private final Path release;
        private final Path trace;
        private final Path session;
        private final NodeProcessUnit unit;
        private final long payloadPid;
        private final ProcessHandle payloadHandle;
        private final Duration budget;
        private final CompletableFuture<String> output;
        private final CompletableFuture<String> error;

        private GroupProbeFixture(Path work, Path session, NodeProcessUnit unit, long payloadPid,
                                  Duration budget, String mode) {
            this.work = work;
            this.release = work.resolve("release");
            this.trace = work.resolve("native-trace.log");
            this.session = session;
            this.unit = unit;
            this.payloadPid = payloadPid;
            this.payloadHandle = ProcessHandle.of(payloadPid).orElseThrow();
            assertTrue(payloadHandle.info().startInstant().isPresent(),
                "fixture 回收必须持有启动时可核验出生时间的 payload 身份");
            this.budget = budget;
            output = mode.equals("output-failure") || mode.startsWith("request-")
                ? CompletableFuture.completedFuture("")
                : CompletableFuture.supplyAsync(() -> readOutput(unit));
            error = mode.startsWith("request-") ? CompletableFuture.completedFuture("")
                : CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(unit.error().readAllBytes(), StandardCharsets.UTF_8);
                } catch (java.io.IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
        }

        private static GroupProbeFixture launch(Path work, boolean injectUnknown) throws Exception {
            return launch(work, injectUnknown ? "query-unknown" : "positive");
        }

        private static GroupProbeFixture launch(Path work, String mode) throws Exception {
            work = work.toRealPath();
            var json = JsonMapper.builder().build();
            var payload = work.resolve("payload.mjs");
            Files.writeString(payload, """
                import fs from 'node:fs';
                import { spawnSync } from 'node:child_process';
                const root = %s;
                const mode = %s;
                const group = spawnSync('/bin/ps', ['-o', 'pgid=', '-p', String(process.pid)],
                  { encoding: 'utf8' });
                if (group.status !== 0) throw new Error(`PGID lookup failed: ${group.stderr}`);
                fs.writeFileSync(`${root}/identity.json`, JSON.stringify({
                  pid: process.pid, pgid: Number(group.stdout.trim()),
                  execArgv: process.execArgv, entrypoint: process.argv[1]
                }));
                if (mode !== 'accepted-term') process.on('SIGTERM', () => {
                  if (mode === 'kill-absent') process.exit(0);
                });
                process.stdin.on('data', () => fs.writeFileSync(`${root}/request-started`, 'started'));
                process.stdin.resume();
                process.stdin.once('end', () => {
                  fs.writeFileSync(`${root}/payload-eof`, 'EOF');
                  if (mode === 'positive' || mode === 'term-absent'
                    || mode.endsWith('unknown-dead')) return process.exit(0);
                  const gate = setInterval(() => {
                    if (mode === 'output-failure' && fs.existsSync(`${root}/write-output`)) {
                      fs.writeSync(1, 'x'.repeat(64 * 1024));
                    }
                    if (!fs.existsSync(`${root}/release`)) return;
                    clearInterval(gate);
                    process.exit(0);
                  }, 5);
                });
                """.formatted(json.writeValueAsString(work.toString()), json.writeValueAsString(mode)));
            var preload = work.resolve("supervisor-preload.cjs");
            Files.writeString(preload, """
                const fs = require('node:fs');
                const root = %s;
                const mode = %s;
                const nativeKill = process.kill.bind(process);
                const log = event => fs.appendFileSync(`${root}/native-trace.log`,
                  `${Date.now()} ${event}\\n`);
                log(`SUPERVISOR_START pid=${process.pid} argv=${JSON.stringify(process.argv)} root=${root}`);
                if (!process.argv[1].endsWith('/node-process-supervisor.mjs')
                  || process.argv[2] !== `${root}/payload.mjs`) {
                  throw new Error('preload must only run in the fixture supervisor');
                }
                process.on('exit', code => log(`SUPERVISOR_EXIT ${code}`));
                const nativeWrite = fs.writeFileSync.bind(fs);
                fs.writeFileSync = (path, data, options) => {
                  const result = nativeWrite(path, data, options);
                  if (path === process.argv[4]) log(`STATUS_WRITTEN range=${JSON.parse(data).range}`);
                  return result;
                };
                // 只记录真实 output error，不替换生产错误或终态。
                const nativeEmit = require('node:net').Socket.prototype.emit;
                require('node:net').Socket.prototype.emit = function(event, ...args) {
                  if (event === 'error' && !this.readable) log('OUTPUT_FAILED');
                  return nativeEmit.call(this, event, ...args);
                };
                let absent = false;
                let started = false;
                process.kill = (pid, signal) => {
                  if (pid >= 0) return nativeKill(pid, signal);
                  const identity = JSON.parse(fs.readFileSync(`${root}/identity.json`, 'utf8'));
                  if (-pid !== identity.pid || identity.pgid !== identity.pid) {
                    throw new Error('refusing a group outside this fixture ownership');
                  }
                  if (absent) {
                    log(`POST_ABSENT_OPERATION operation=${signal} pgid=${identity.pgid}`);
                    throw new Error('production reused a confirmed-absent fixture PGID');
                  }
                  if (!started) {
                    started = true;
                    log('FIRST_OPERATION');
                  }
                  log(`RANGE_OPERATION operation=${signal} pgid=${identity.pgid}`);
                  const blockedQuery = signal === 0 && (
                    mode.endsWith('unknown-dead') || mode.endsWith('-absent')
                    || mode === 'unknown-present' && !fs.existsSync(`${root}/probe-release`)
                    || ['query-unknown', 'status-failure', 'output-failure', 'request-unknown'].includes(mode)
                      && !fs.existsSync(`${root}/release`));
                  const blockedSignal = signal !== 0 && (
                    mode.endsWith('unknown-dead')
                    || mode === 'term-eperm' && signal === 'SIGTERM'
                    || ['kill-eperm', 'present-live'].includes(mode) && signal === 'SIGKILL');
                  if (blockedQuery || blockedSignal || mode === 'other-errno' && signal === 0) {
                    const code = mode === 'other-errno' ? 'EIO' : 'EPERM';
                    log(`INJECTED_${code} operation=${signal} pgid=${identity.pgid}`);
                    const error = new Error(`fixture range ${code}`);
                    error.code = code;
                    throw error;
                  }
                  try {
                    const result = nativeKill(pid, signal);
                    log(`NATIVE_${signal === 0 ? 'PRESENT' : 'ACCEPTED'} operation=${signal} pgid=${identity.pgid}`);
                    return result;
                  } catch (error) {
                    if (error.code === 'ESRCH') absent = true;
                    log(`NATIVE_${absent ? 'ABSENT' : 'ERROR'} operation=${signal} pgid=${identity.pgid} code=${error.code}`);
                    throw error;
                  }
                };
                """.formatted(json.writeValueAsString(work.toString()), json.writeValueAsString(mode)));
            var launcher = work.resolve("node-launcher");
            Files.writeString(launcher, "#!/bin/sh\nexec " + shellQuote(node().toString())
                + " --require " + shellQuote(preload.toString()) + " \"$@\"\n");
            assertTrue(launcher.toFile().setExecutable(true));
            Files.writeString(work.resolve("fixture-mode"), mode);
            var sessions = work.resolve("sessions");
            var budget = mode.equals("positive") || mode.equals("query-unknown")
                ? Duration.ofSeconds(5) : Duration.ofMillis(600);
            var unit = NodeProcessUnit.launch(payload, NodeRuntimeOptions.builder(launcher, sessions)
                .terminateTimeout(budget).build());
            try {
                awaitFile(work.resolve("identity.json"));
                var identity = json.readTree(Files.readString(work.resolve("identity.json")));
                assertEquals(identity.path("pid").longValue(), identity.path("pgid").longValue(),
                    "真实 payload 必须拥有独立 PGID");
                assertEquals(0, identity.path("execArgv").size(), "preload 不能传播到真实 payload");
                assertEquals(payload.toRealPath().toString(), identity.path("entrypoint").stringValue());
                Path session;
                try (var paths = Files.list(sessions)) {
                    session = paths.findFirst().orElseThrow();
                }
                var materialized = Files.readAllBytes(session.resolve("node-process-supervisor.mjs"));
                try (var resource = NodeProcessUnit.class.getResourceAsStream(
                    "/com/sstlfsj/fibra/runtime/node/node-process-supervisor.mjs")) {
                    org.junit.jupiter.api.Assertions.assertArrayEquals(resource.readAllBytes(), materialized,
                        "launch 必须 materialize 原 production resource bytes");
                }
                Files.write(work.resolve("materialized-supervisor.mjs"), materialized);
                return new GroupProbeFixture(work, session, unit, identity.path("pid").longValue(), budget, mode);
            } catch (Exception | Error failure) {
                try {
                    Files.writeString(work.resolve("release"), "release\n");
                    unit.input().close();
                    unit.close();
                } catch (Exception | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                if (!unit.isAlive()) {
                    Files.writeString(work.resolve("supervisor-stderr.log"),
                        new String(unit.error().readAllBytes(), StandardCharsets.UTF_8));
                }
                archiveFiles(work);
                throw failure;
            }
        }

        private void awaitTrace(String event) throws Exception {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(trace) || !Files.readString(trace).contains(event)) {
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError("fixture trace missing " + event + ": "
                        + (Files.exists(trace) ? Files.readString(trace) : "MISSING"));
                }
                Thread.sleep(5);
            }
        }

        private void awaitNativeAbsence() throws Exception {
            awaitNativeState(true);
        }

        private void assertNativePresent() throws Exception {
            awaitNativeState(false);
        }

        private void awaitNativeState(boolean expectAbsent) throws Exception {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            do {
                // 独立、只读的原生探测不影响 supervisor 的引用数、退出时序和终态。
                var probe = new ProcessBuilder(node().toString(), "-e", """
                    const pgid = Number(process.argv[1]);
                    try {
                      process.kill(-pgid, 0);
                      console.log(`TEST_NATIVE_PRESENT pgid=${pgid}`);
                      process.exitCode = 2;
                    } catch (error) {
                      const state = error.code === 'ESRCH' ? 'ABSENT'
                        : error.code === 'EPERM' ? 'UNKNOWN' : 'ERROR';
                      console.log(`TEST_NATIVE_${state} pgid=${pgid} code=${error.code}`);
                      process.exitCode = error.code === 'ESRCH' ? 0 : error.code === 'EPERM' ? 4 : 3;
                    }
                    """, Long.toString(payloadPid)).redirectErrorStream(true).start();
                try {
                    assertTrue(probe.waitFor(1, TimeUnit.SECONDS), "原生 group probe 必须及时结算");
                    var result = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    Files.writeString(trace, result, java.nio.file.StandardOpenOption.APPEND,
                        java.nio.file.StandardOpenOption.CREATE);
                    var state = probe.exitValue();
                    if (state == 4) {
                        // EPERM 只有 UNKNOWN，继续同一次 test budget 内的观察。
                    } else if (expectAbsent) {
                        if (state == 0) return;
                        assertEquals(2, state, "独立原生 group probe 失败: " + result);
                    } else {
                        assertEquals(2, state, "必须由真实原生 PRESENT 证明活成员: " + result);
                        return;
                    }
                } finally {
                    if (probe.isAlive()) probe.destroyForcibly();
                }
                Thread.sleep(5);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("fixture own group 未得到原生 "
                + (expectAbsent ? "ABSENT" : "PRESENT") + ": " + Files.readString(trace));
        }

        private void cleanupAndArchive() throws Exception {
            release();
            // 保留启动时的身份，不在原 payload 消失后重新绑定数值 PID。
            if (payloadHandle.isAlive()) payloadHandle.destroyForcibly();
            unit.onExit().get(3, TimeUnit.SECONDS);
            Files.writeString(work.resolve("supervisor-stderr.log"), error.get(3, TimeUnit.SECONDS));
            Files.writeString(work.resolve("payload-stdout.log"), output.get(3, TimeUnit.SECONDS));
            var status = session.resolve("termination.status");
            if (Files.isRegularFile(status)) {
                Files.copy(status, work.resolve("retained-termination.status"),
                    StandardCopyOption.REPLACE_EXISTING);
            }
            archiveFiles(work);
        }

        private void release() throws Exception {
            Files.writeString(release, "release\n");
        }

        private void assertSignalCount(String signal, long expected) throws Exception {
            assertEquals(expected, Files.readString(trace).lines()
                .filter(line -> line.contains("RANGE_OPERATION operation=" + signal + " ")).count());
        }

        private void assertNoOperationAfterAbsence() throws Exception {
            var events = Files.readString(trace);
            assertFalse(events.contains("POST_ABSENT_OPERATION"), events);
            var absent = events.indexOf(" NATIVE_ABSENT ");
            assertTrue(absent >= 0, events);
            assertFalse(events.substring(absent).contains("RANGE_OPERATION"), events);
        }

        private void assertOriginalRangeBudget() throws Exception {
            var events = Files.readString(trace).lines().toList();
            var first = events.stream().filter(line -> line.endsWith("FIRST_OPERATION"))
                .findFirst().orElseThrow();
            var status = events.stream().filter(line -> line.contains("STATUS_WRITTEN range=FAILED"))
                .findFirst().orElseThrow();
            var elapsed = Long.parseLong(status.split(" ")[0]) - Long.parseLong(first.split(" ")[0]);
            assertTrue(elapsed >= budget.toMillis() - 40, "不能把一次 UNKNOWN/PRESENT 提早当作终态: " + elapsed);
            assertTrue(elapsed <= budget.toMillis() + 200, "范围状态必须沿原 budget 写出: " + elapsed);
        }

        private static void archiveFiles(Path work) throws Exception {
            var evidence = System.getProperty("fibra.test.lifecycle.evidenceRoot");
            if (evidence != null) {
                var destination = Files.createDirectories(Path.of(evidence)
                    .resolve(Files.readString(work.resolve("fixture-mode")) + "-" + work.getFileName()));
                try (var paths = Files.list(work)) {
                    for (var path : paths.filter(Files::isRegularFile).toList()) {
                        Files.copy(path, destination.resolve(path.getFileName()),
                            StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }

        private static void awaitFile(Path file) throws Exception {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(file)) {
                if (System.nanoTime() >= deadline) throw new AssertionError("fixture missing " + file);
                Thread.sleep(5);
            }
        }

        private static String shellQuote(String value) {
            return "'" + value.replace("'", "'\\''") + "'";
        }
    }

    @Test
    void blockedPayloadInputStillProducesQuiescenceProofDuringClose(@TempDir Path work)
        throws Exception {
        var script = work.resolve("blocked-input.mjs");
        Files.writeString(script, "process.stdout.write(String(process.pid) + '\\n'); setInterval(() => {}, 1000);");
        var unit = NodeProcessUnit.launch(script, NodeRuntimeOptions.builder(node(), work.resolve("sessions"))
            .terminateTimeout(Duration.ofMillis(300)).build());
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(unit.output()));
        var pid = Long.parseLong(reader.readLine());
        var writing = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                unit.input().write(new byte[8 * 1024 * 1024]);
                unit.input().flush();
            } catch (java.io.IOException expectedAfterClose) { }
        });
        try {
            assertThrows(java.util.concurrent.TimeoutException.class,
                () -> writing.get(100, TimeUnit.MILLISECONDS));
            unit.close();
            writing.get(2, TimeUnit.SECONDS);
            assertEquals("QUIESCENT", unit.finalState().range());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

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
