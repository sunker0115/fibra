package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.CancellationToken;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeSidecarTest {
    @Test
    void rangeProofFailureRetainsProtocolFailureFromDelayedHalfFrameEof(@TempDir Path work)
        throws Exception {
        var tailReleased = new java.util.concurrent.CompletableFuture<Void>();
        var readerEntered = new java.util.concurrent.CountDownLatch(1);
        var requestWritten = new java.util.concurrent.CountDownLatch(1);
        var alive = new AtomicBoolean(true);
        var exit = new java.util.concurrent.CompletableFuture<Process>();
        var tail = new java.util.concurrent.atomic.AtomicReference<java.io.InputStream>();
        var input = new java.io.ByteArrayOutputStream() {
            @Override public void flush() { requestWritten.countDown(); }
        };
        var output = new java.io.InputStream() {
            @Override public int read() throws java.io.IOException {
                readerEntered.countDown();
                tailReleased.join();
                return tail.get().read();
            }
        };
        var process = new Process() {
            @Override public java.io.OutputStream getOutputStream() { return input; }
            @Override public java.io.InputStream getInputStream() { return output; }
            @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
            @Override public boolean isAlive() { return alive.get(); }
            @Override public java.util.concurrent.CompletableFuture<Process> onExit() { return exit; }
            @Override public int waitFor() { exit.join(); return 0; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() { }
            @Override public Process destroyForcibly() { return this; }
        };
        var sessionDirectory = Files.createDirectory(work.resolve("session"));
        var options = NodeRuntimeOptions.builder(node(), work).terminateTimeout(Duration.ofMillis(20)).build();
        var unit = new NodeProcessUnit(sessionDirectory, sessionDirectory.resolve("termination.status"),
            process, options.terminateTimeout());
        var scheduler = new ScheduledThreadPoolExecutor(1);
        var constructor = NodeSidecar.class.getDeclaredConstructor(NodeRuntimeOptions.class,
            NodeProcessUnit.class, Runnable.class, java.util.concurrent.ScheduledExecutorService.class);
        constructor.setAccessible(true);
        var session = constructor.newInstance(options, unit, (Runnable) () -> { }, scheduler);
        try {
            var request = session.beginRequest("last", Map.of(), CancellationToken.never());
            var result = request.result().toFuture();
            assertTrue(readerEntered.await(1, TimeUnit.SECONDS));
            assertTrue(requestWritten.await(1, TimeUnit.SECONDS));
            var json = tools.jackson.databind.json.JsonMapper.builder().build();
            var id = json.readValue(input.toByteArray(), Map.class).get("id");
            tail.set(new java.io.ByteArrayInputStream(("{\"jsonrpc\":\"2.0\",\"id\":" + id)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            alive.set(false);
            exit.complete(process);
            var closing = session.closeAsync().toFuture();
            var proofFailure = assertThrows(NodeRpcException.class, unit::close);
            assertEquals(NodeRpcPhase.TERMINATE, proofFailure.phase());
            assertThrows(java.util.concurrent.TimeoutException.class,
                () -> closing.get(100, TimeUnit.MILLISECONDS), "必须等到尾部 EOF 才能汇总协议失败");

            tailReleased.complete(null);
            org.junit.jupiter.api.Assertions.assertSame(proofFailure,
                assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> closing.get(1, TimeUnit.SECONDS)).getCause());
            assertEquals(1, proofFailure.getSuppressed().length);
            var protocolFailure = (NodeRpcException) proofFailure.getSuppressed()[0];
            assertEquals(NodeRpcPhase.PROTOCOL, protocolFailure.phase());
            assertTrue(protocolFailure.getMessage().contains("incomplete frame"));
            org.junit.jupiter.api.Assertions.assertSame(proofFailure,
                assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> result.get(1, TimeUnit.SECONDS)).getCause());
            org.junit.jupiter.api.Assertions.assertSame(proofFailure,
                assertThrows(NodeRpcException.class, () -> request.drain().block(Duration.ofSeconds(1))));
            org.junit.jupiter.api.Assertions.assertSame(proofFailure,
                assertThrows(NodeRpcException.class, () -> session.termination().block(Duration.ofSeconds(1))));
            org.junit.jupiter.api.Assertions.assertSame(proofFailure,
                assertThrows(NodeRpcException.class, session::close));
            assertEquals(1, java.util.Arrays.stream(proofFailure.getSuppressed())
                .filter(value -> value == protocolFailure).count());
            assertTrue(Files.isDirectory(sessionDirectory));
        } finally {
            tailReleased.complete(null);
            scheduler.shutdownNow();
        }
    }

    @Test
    void exitedSupervisorStillDeliversTailResponseWhenRangeProofIsMissing(@TempDir Path work)
        throws Exception {
        var tailReleased = new java.util.concurrent.CompletableFuture<Void>();
        var readerEntered = new java.util.concurrent.CountDownLatch(1);
        var requestWritten = new java.util.concurrent.CountDownLatch(1);
        var alive = new AtomicBoolean(true);
        var exit = new java.util.concurrent.CompletableFuture<Process>();
        var tail = new java.util.concurrent.atomic.AtomicReference<java.io.InputStream>();
        var input = new java.io.ByteArrayOutputStream() {
            @Override public void flush() { requestWritten.countDown(); }
        };
        var output = new java.io.InputStream() {
            @Override public int read() throws java.io.IOException {
                readerEntered.countDown();
                tailReleased.join();
                return tail.get().read();
            }
        };
        var process = new Process() {
            @Override public java.io.OutputStream getOutputStream() { return input; }
            @Override public java.io.InputStream getInputStream() { return output; }
            @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
            @Override public boolean isAlive() { return alive.get(); }
            @Override public java.util.concurrent.CompletableFuture<Process> onExit() { return exit; }
            @Override public int waitFor() { exit.join(); return 0; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() { }
            @Override public Process destroyForcibly() { return this; }
        };
        var sessionDirectory = Files.createDirectory(work.resolve("session"));
        var options = NodeRuntimeOptions.builder(node(), work).terminateTimeout(Duration.ofMillis(20)).build();
        var unit = new NodeProcessUnit(sessionDirectory, sessionDirectory.resolve("termination.status"),
            process, options.terminateTimeout());
        var scheduler = new ScheduledThreadPoolExecutor(1);
        var constructor = NodeSidecar.class.getDeclaredConstructor(NodeRuntimeOptions.class,
            NodeProcessUnit.class, Runnable.class, java.util.concurrent.ScheduledExecutorService.class);
        constructor.setAccessible(true);
        var session = constructor.newInstance(options, unit, (Runnable) () -> { }, scheduler);
        try {
            var request = session.beginRequest("last", Map.of(), CancellationToken.never());
            var result = request.result().toFuture();
            assertTrue(readerEntered.await(1, TimeUnit.SECONDS));
            assertTrue(requestWritten.await(1, TimeUnit.SECONDS));
            var json = tools.jackson.databind.json.JsonMapper.builder().build();
            var id = json.readValue(input.toByteArray(), Map.class).get("id");
            tail.set(new java.io.ByteArrayInputStream((json.writeValueAsString(
                Map.of("jsonrpc", "2.0", "id", id, "result", "complete-tail")) + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            alive.set(false);
            exit.complete(process);
            var closing = session.closeAsync().toFuture();
            var proofFailure = assertThrows(NodeRpcException.class, unit::close);
            assertEquals(NodeRpcPhase.TERMINATE, proofFailure.phase());
            assertThrows(java.util.concurrent.TimeoutException.class,
                () -> closing.get(100, TimeUnit.MILLISECONDS), "范围证明失败不能抢先丢弃完整尾响应");

            tailReleased.complete(null);
            assertEquals("complete-tail", result.get(1, TimeUnit.SECONDS).value());
            request.drain().block(Duration.ofSeconds(1));
            org.junit.jupiter.api.Assertions.assertSame(proofFailure,
                assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> closing.get(1, TimeUnit.SECONDS)).getCause());
            assertTrue(Files.isDirectory(sessionDirectory));
        } finally {
            tailReleased.complete(null);
            scheduler.shutdownNow();
        }
    }

    @Test
    void terminationFailureDoesNotWaitForBlockedSessionStreams(@TempDir Path work) throws Exception {
        var streamsReleased = new java.util.concurrent.CompletableFuture<Void>();
        var readersEntered = new java.util.concurrent.CountDownLatch(2);
        var writerEntered = new java.util.concurrent.CountDownLatch(1);
        var blockedOutput = new java.io.InputStream() {
            @Override public int read() {
                readersEntered.countDown();
                streamsReleased.join();
                return -1;
            }
        };
        var process = new Process() {
            private final java.util.concurrent.CompletableFuture<Process> exit =
                new java.util.concurrent.CompletableFuture<>();
            private final java.io.OutputStream input = new java.io.OutputStream() {
                @Override public void write(int value) {
                    writerEntered.countDown();
                    streamsReleased.join();
                }
            };
            @Override public java.io.OutputStream getOutputStream() { return input; }
            @Override public java.io.InputStream getInputStream() { return blockedOutput; }
            @Override public java.io.InputStream getErrorStream() { return blockedOutput; }
            @Override public boolean isAlive() { return true; }
            @Override public java.util.concurrent.CompletableFuture<Process> onExit() { return exit; }
            @Override public int waitFor() { exit.join(); return 0; }
            @Override public int exitValue() { throw new IllegalThreadStateException(); }
            @Override public void destroy() { }
            @Override public Process destroyForcibly() { return this; }
        };
        var sessionDirectory = Files.createDirectory(work.resolve("session"));
        var options = NodeRuntimeOptions.builder(node(), work)
            .terminateTimeout(Duration.ofMillis(20)).build();
        var unit = new NodeProcessUnit(sessionDirectory, sessionDirectory.resolve("termination.status"),
            process, options.terminateTimeout());
        var scheduler = new ScheduledThreadPoolExecutor(1);
        var constructor = NodeSidecar.class.getDeclaredConstructor(NodeRuntimeOptions.class,
            NodeProcessUnit.class, Runnable.class, java.util.concurrent.ScheduledExecutorService.class);
        constructor.setAccessible(true);
        var session = constructor.newInstance(options, unit, (Runnable) () -> { }, scheduler);
        try {
            var request = session.beginRequest("blocked", Map.of(), CancellationToken.never());
            assertTrue(readersEntered.await(1, TimeUnit.SECONDS));
            assertTrue(writerEntered.await(1, TimeUnit.SECONDS));
            var result = request.result().toFuture();
            var cleanup = request.drain().toFuture();
            var closing = session.closeAsync().toFuture();

            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> closing.get(1, TimeUnit.SECONDS));
            assertEquals(NodeRpcPhase.TERMINATE, ((NodeRpcException) failure.getCause()).phase());
            org.junit.jupiter.api.Assertions.assertSame(failure.getCause(),
                assertThrows(CompletionException.class, result::join).getCause());
            org.junit.jupiter.api.Assertions.assertSame(failure.getCause(),
                assertThrows(CompletionException.class, cleanup::join).getCause());
            org.junit.jupiter.api.Assertions.assertSame(failure.getCause(),
                assertThrows(NodeRpcException.class, session::close));
            assertFalse(streamsReleased.isDone(), "cleanup failure 必须在阻塞流释放之前传播");
            assertTrue(Files.isDirectory(sessionDirectory));
        } finally {
            streamsReleased.complete(null);
            scheduler.shutdownNow();
        }
    }

    @Test
    void naturalHalfFrameEofIsAProtocolFailure(@TempDir Path work) throws Exception {
        var script = work.resolve("partial.mjs");
        Files.writeString(script, baseScript("""
            if (method === 'partial') process.stdout.write('{"jsonrpc":"2.0","id":', () => process.exit(0));
            """));
        try (var session = NodeSidecar.start(script,
            NodeRuntimeOptions.defaults(node(), work.resolve("sessions")), () -> { }).block()) {
            var failure = assertThrows(NodeRpcException.class,
                () -> session.request("partial", Map.of()).block(Duration.ofSeconds(5)));
            assertEquals(NodeRpcPhase.PROTOCOL, failure.phase());
        }
    }

    @Test
    void malformedResponseCannotConfirmCleanupWhenRangeProofIsMissing(@TempDir Path work)
        throws Exception {
        var script = work.resolve("malformed.mjs");
        Files.writeString(script, "import fs from 'node:fs';\n" + baseScript("""
            if (method === 'malformed') {
              fs.writeFileSync('termination.status', 'FAILED\\n');
              process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, error:{message:'missing code'}}) + '\\n');
            }
            """));
        var session = NodeSidecar.start(script,
            NodeRuntimeOptions.builder(node(), work.resolve("sessions"))
                .terminateTimeout(Duration.ofMillis(200)).build(), () -> { }).block();
        var request = session.beginRequest("malformed", Map.of(), CancellationToken.never());
        try {
            assertThrows(NodeRpcException.class, () -> request.result().block(Duration.ofSeconds(3)));
            var failure = assertThrows(NodeRpcException.class,
                () -> request.drain().block(Duration.ofSeconds(3)));
            assertEquals(NodeRpcPhase.TERMINATE, failure.phase());
        } finally {
            try { session.close(); } catch (NodeRpcException expected) { }
        }
    }

    @Test
    void cancellingHandshakeClosesTheAcquiredSession(@TempDir Path work) throws Exception {
        var marker = work.resolve("pid");
        var script = work.resolve("handshake.mjs");
        Files.writeString(script, "import fs from 'node:fs'; fs.writeFileSync("
            + jsonPath(marker) + ", String(process.pid)); setInterval(() => {}, 1000);");
        var sessions = work.resolve("sessions");
        var subscription = NodeSidecar.start(script,
            NodeRuntimeOptions.builder(node(), sessions)
                .handshakeTimeout(Duration.ofSeconds(30))
                .terminateTimeout(Duration.ofMillis(200)).build(), () -> { }).subscribe();
        awaitTrue(() -> Files.exists(marker));
        var pid = Long.parseLong(Files.readString(marker));
        try {
            subscription.dispose();
            awaitTrue(() -> !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            awaitTrue(() -> {
                try (var remaining = Files.list(sessions)) {
                    return remaining.findAny().isEmpty();
                }
            });
        } finally {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    void blockedWriteDoesNotBlockRequestAdmissionCancellationOrClose(@TempDir Path work)
        throws Exception {
        var script = work.resolve("blocked.mjs");
        Files.writeString(script, "setInterval(() => {}, 1000);\n" + baseScript("""
            if (method === 'pause') { process.stdin.pause(); reply(id, [process.pid, process.ppid]); }
            """));
        var session = NodeSidecar.start(script, NodeRuntimeOptions.builder(node(), work.resolve("sessions"))
            .maxMessageBytes(16 * 1024 * 1024).heartbeatInterval(Duration.ofSeconds(30))
            .requestCancellationTimeout(Duration.ofMillis(100))
            .terminateTimeout(Duration.ofMillis(200)).build(), () -> { }).block();
        var pids = (java.util.List<?>) session.request("pause", Map.of()).block();
        var source = new com.sstlfsj.fibra.CancellationSource();
        var sending = java.util.concurrent.CompletableFuture.supplyAsync(() ->
            session.beginRequest("blocked", "x".repeat(8 * 1024 * 1024), source.token()));
        try {
            var request = sending.get(1, TimeUnit.SECONDS);
            awaitTrue(NodeSidecarTest::writerIsBlocked);
            var cancelling = java.util.concurrent.CompletableFuture.runAsync(source::cancel);
            cancelling.get(1, TimeUnit.SECONDS);
            var closing = java.util.concurrent.CompletableFuture.runAsync(session::close);
            closing.get(3, TimeUnit.SECONDS);
            request.drain().block(Duration.ofSeconds(1));
            session.close();
            for (var pid : pids) {
                assertFalse(ProcessHandle.of(((Number) pid).longValue()).map(ProcessHandle::isAlive).orElse(false));
            }
        } finally {
            for (var pid : pids) {
                ProcessHandle.of(((Number) pid).longValue()).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    void heartbeatDeadlineClosesASessionWhileItsWriterIsBlocked(@TempDir Path work)
        throws Exception {
        var script = work.resolve("heartbeat-blocked.mjs");
        Files.writeString(script, "setInterval(() => {}, 1000);\n" + baseScript("""
            if (method === 'pause') { process.stdin.pause(); reply(id, process.pid); }
            """));
        try (var session = NodeSidecar.start(script,
            NodeRuntimeOptions.builder(node(), work.resolve("sessions"))
                .maxMessageBytes(16 * 1024 * 1024).heartbeatInterval(Duration.ofMillis(500))
                .heartbeatTimeout(Duration.ofMillis(100)).terminateTimeout(Duration.ofMillis(300)).build(),
            () -> { }).block()) {
            var pid = ((Number) session.request("pause", Map.of()).block()).longValue();
            var request = session.beginRequest("blocked", "x".repeat(8 * 1024 * 1024), CancellationToken.never());
            awaitTrue(NodeSidecarTest::writerIsBlocked);
            assertThrows(NodeRpcException.class, () -> session.termination().block(Duration.ofSeconds(4)));
            request.drain().block(Duration.ofSeconds(1));
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void readerAndTimerCallbacksCanRequestTheSameCloseBarrier(@TempDir Path work) throws Exception {
        var script = work.resolve("callbacks.mjs");
        Files.writeString(script, baseScript("""
            if (method === 'disable') {
              process.stdout.write(JSON.stringify({jsonrpc:'2.0', method:'fibra.disable', params:{}}) + '\\n');
              reply(id, true);
            }
            """));
        var reference = new java.util.concurrent.atomic.AtomicReference<NodeSidecar>();
        var callbackDone = new java.util.concurrent.CompletableFuture<Void>();
        var scheduler = new ScheduledThreadPoolExecutor(1);
        try (var session = NodeSidecar.start(script,
            NodeRuntimeOptions.builder(node(), work.resolve("sessions"))
                .heartbeatInterval(Duration.ofSeconds(30)).build(), () -> {
                    reference.get().close();
                    callbackDone.complete(null);
                }, scheduler).block()) {
            reference.set(session);
            var timerDone = new java.util.concurrent.CompletableFuture<Void>();
            scheduler.schedule(() -> {
                reference.get().close();
                timerDone.complete(null);
            }, 200, TimeUnit.MILLISECONDS);
            session.request("disable", Map.of()).onErrorResume(ignored -> Mono.empty()).block();
            callbackDone.get(4, TimeUnit.SECONDS);
            timerDone.get(4, TimeUnit.SECONDS);
            assertFalse(session.isAlive());
        }
    }

    private static boolean writerIsBlocked() {
        return Thread.getAllStackTraces().entrySet().stream().anyMatch(entry ->
            entry.getKey().getName().equals("fibra-node-writer")
                && java.util.Arrays.stream(entry.getValue()).anyMatch(frame ->
                    frame.getClassName().equals("java.io.FileOutputStream")
                        && frame.getMethodName().equals("writeBytes")));
    }

    @Test
    void disableCallbackFailureStillTerminatesTheSession(@TempDir Path work) throws Exception {
        var script = work.resolve("callback-failure.mjs");
        Files.writeString(script, baseScript("""
            if (method === 'disable') {
              process.stdout.write(JSON.stringify({jsonrpc:'2.0', method:'fibra.disable', params:{}}) + '\\n');
              reply(id, true);
            }
            """));
        try (var session = NodeSidecar.start(script, NodeRuntimeOptions.defaults(node(), work.resolve("sessions")),
            () -> { throw new IllegalStateException("disable callback failed"); }).block()) {
            session.request("disable", Map.of()).onErrorResume(ignored -> Mono.empty()).block();
            var failure = assertThrows(NodeRpcException.class,
                () -> session.termination().block(Duration.ofSeconds(3)));
            assertEquals(NodeRpcPhase.PROTOCOL, failure.phase());
        }
    }

    @Test
    void preservesALargeFinalResponseImmediatelyBeforeExit(@TempDir Path work) throws Exception {
        var script = work.resolve("tail.mjs");
        Files.writeString(script, baseScript("""
            if (method === 'last') {
              process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result:'x'.repeat(4 * 1024 * 1024)}) + '\\n',
                () => process.exit(0));
            }
            """));
        try (var session = NodeSidecar.start(script,
            NodeRuntimeOptions.builder(node(), work.resolve("sessions"))
                .maxMessageBytes(5 * 1024 * 1024).heartbeatInterval(Duration.ofSeconds(30)).build(),
            () -> { }).block()) {
            assertEquals(4 * 1024 * 1024,
                ((String) session.request("last", Map.of()).block(Duration.ofSeconds(5))).length());
        }
    }

    private static String jsonPath(Path path) {
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(path.toString());
    }

    @Test
    void acceptsAResponseOnlyAfterItsFragmentedFrameIsComplete(@TempDir Path work) throws Exception {
        var firstHalf = work.resolve("first-half");
        var release = work.resolve("release-frame");
        var script = work.resolve("fragmented.mjs");
        Files.writeString(script, "import fs from 'node:fs';\n" + baseScript("""
              if (method === 'fragment') {
                const frame = JSON.stringify({jsonrpc:'2.0', id, result:'fragmented'}) + '\\n';
                const split = Math.floor(frame.length / 2);
                process.stdout.write(frame.slice(0, split), () => {
                  fs.writeFileSync(%s, 'ready');
                  const timer = setInterval(() => {
                    if (!fs.existsSync(%s)) return;
                    clearInterval(timer);
                    process.stdout.write(frame.slice(split));
                  }, 10);
                });
              }
            """.formatted(jsonPath(firstHalf), jsonPath(release))));
        var options = NodeRuntimeOptions.builder(node(), work.resolve("sessions"))
            .heartbeatInterval(Duration.ofSeconds(30)).build();
        try (var session = NodeSidecar.start(script, options, () -> { }).block(Duration.ofSeconds(5))) {
            var result = session.request("fragment", Map.of(), Duration.ofSeconds(5)).toFuture();
            try {
                var deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (Files.notExists(firstHalf) && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(Files.exists(firstHalf));
                assertFalse(result.isDone());
            } finally {
                Files.writeString(release, "release");
            }
            assertEquals("fragmented", result.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void doesNotSendARequestWhoseDeadlineCannotBeOwned(@TempDir Path work)
        throws Exception {
        var marker = work.resolve("handshake-sent");
        var script = work.resolve("sidecar.mjs");
        Files.writeString(script, """
            import fs from 'node:fs';
            import readline from 'node:readline';
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              if (message.method === 'fibra.handshake') {
                fs.writeFileSync('%s', 'sent');
              }
            });
            """.formatted(marker.toString().replace("\\", "\\\\")));
        var scheduler = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                var deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                while (Files.notExists(marker) && System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                throw new RejectedExecutionException("deadline ownership rejected");
            }
        };

        try {
            assertThrows(NodeRpcException.class, () -> NodeSidecar.start(script,
                NodeRuntimeOptions.defaults(node(), work.resolve("sessions")), () -> { },
                scheduler).block());
            assertFalse(Files.exists(marker), "没有 deadline 所有权的请求不得发送");
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void rejectsANonPositiveRequestCancellationTimeout(@TempDir Path work) {
        assertThrows(IllegalArgumentException.class, () -> NodeRuntimeOptions.builder(
            node(), work.resolve("sessions"))
            .requestCancellationTimeout(Duration.ZERO).build());
    }

    @Test
    void handshakesAndRoutesJsonRpcWithoutShell(@TempDir Path work) throws Exception {
        var script = work.resolve("sidecar.mjs");
        Files.writeString(script, responsiveScript());
        var options = NodeRuntimeOptions.defaults(node(), work.resolve("sessions"));

        try (var session = NodeSidecar.start(script, options, () -> { }).block()) {
            var result = session.request("echo", Map.of("value", "hello"),
                Duration.ofSeconds(2)).block();

            assertEquals(Map.of("value", "hello"), result);
            assertTrue(session.isAlive());
        }
    }

    @Test
    void rejectsANullHandshakeResult(@TempDir Path work) throws Exception {
        var script = work.resolve("sidecar.mjs");
        Files.writeString(script, """
            import readline from 'node:readline';
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              if (message.method === 'fibra.handshake') {
                process.stdout.write(JSON.stringify({jsonrpc:'2.0', id:message.id, result:null}) + '\\n');
              }
            });
            """);

        var failure = assertThrows(NodeRpcException.class, () -> NodeSidecar.start(script,
            NodeRuntimeOptions.defaults(node(), work.resolve("sessions")), () -> { }).block());

        assertEquals(NodeRpcPhase.HANDSHAKE, failure.phase());
    }

    @Test
    void timesOutCancelsAndTerminatesTheCompleteProcessTree(@TempDir Path work)
        throws Exception {
        var script = work.resolve("sidecar.mjs");
        Files.writeString(script, blockingScript());
        var options = NodeRuntimeOptions.builder(node(), work.resolve("sessions"))
            .handshakeTimeout(Duration.ofSeconds(2))
            .defaultRequestTimeout(Duration.ofMillis(100))
            .requestCancellationTimeout(Duration.ofMillis(100))
            .heartbeatInterval(Duration.ofSeconds(5))
            .heartbeatTimeout(Duration.ofSeconds(2))
            .maxMessageBytes(64 * 1024)
            .terminateTimeout(Duration.ofSeconds(2)).build();
        var session = NodeSidecar.start(script, options, () -> { }).block();
        var childPid = Long.parseLong(session.request("childPid", Map.of(),
            Duration.ofSeconds(2)).block().toString());

        assertThrows(NodeRpcException.class,
            () -> session.request("never", Map.of(), Duration.ofMillis(100)).block());
        assertFalse(session.isAlive());

        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
        try (var sessions = Files.list(work.resolve("sessions"))) {
            assertTrue(sessions.findAny().isEmpty());
        }
    }

    @Test
    void timeoutDoesNotCompleteOtherRequestsBeforeTheManagedRangeStops(@TempDir Path work)
        throws Exception {
        var script = work.resolve("sidecar.mjs");
        Files.writeString(script, blockingScript());
        var options = NodeRuntimeOptions.builder(node(), work.resolve("sessions"))
            .handshakeTimeout(Duration.ofSeconds(2)).heartbeatInterval(Duration.ofSeconds(5))
            .heartbeatTimeout(Duration.ofSeconds(2)).requestCancellationTimeout(Duration.ofMillis(100))
            .terminateTimeout(Duration.ofSeconds(2)).build();
        var session = NodeSidecar.start(script, options, () -> { }).block();
        var childPid = Long.parseLong(session.request("childPid", Map.of(),
            Duration.ofSeconds(2)).block().toString());
        var childAliveWhenSiblingCompleted = new AtomicBoolean();
        var sibling = session.request("never", Map.of(), Duration.ofMillis(150)).toFuture();
        sibling.whenComplete((ignored, failure) -> childAliveWhenSiblingCompleted.set(
            ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)));

        assertThrows(NodeRpcException.class,
            () -> session.request("never", Map.of(), Duration.ofMillis(100)).block());
        assertThrows(CompletionException.class, sibling::join);
        assertFalse(childAliveWhenSiblingCompleted.get());
    }

    @Test
    void managedRangeCleanupFailureFailsRequestDrainAndRetainsTheSession(@TempDir Path work)
        throws Exception {
        var marker = work.resolve("request-started");
        var script = work.resolve("sidecar.mjs");
        Files.writeString(script, """
            import fs from 'node:fs';
            import readline from 'node:readline';
            const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              if (message.method === 'fibra.handshake') reply(message.id, {protocol:1});
              else if (message.method === 'fibra.ping') reply(message.id, {ok:true});
              else if (message.method === 'poison') {
                fs.writeFileSync('termination.status', 'FAILED\\n');
                fs.writeFileSync('%s', 'started');
              }
            });
            """.formatted(marker.toString().replace("\\", "\\\\")));
        var sessions = work.resolve("sessions");
        var sidecar = NodeSidecar.start(script,
            NodeRuntimeOptions.defaults(node(), sessions), () -> { }).block();
        var request = sidecar.beginRequest("poison", Map.of(), CancellationToken.never());
        awaitTrue(() -> Files.exists(marker));
        var draining = request.drain().toFuture();

        var failure = assertThrows(NodeRpcException.class, sidecar::close);
        assertEquals(NodeRpcPhase.TERMINATE, failure.phase());
        var drainFailure = assertThrows(CompletionException.class, draining::join);
        assertEquals(NodeRpcPhase.TERMINATE,
            ((NodeRpcException) drainFailure.getCause()).phase());
        assertThrows(NodeRpcException.class, () -> sidecar.termination().block());
        try (var retained = Files.list(sessions)) {
            assertTrue(retained.findAny().isPresent(), "清理失败必须保留会话诊断现场");
        }
    }

    @Test
    void sendFailureDisposesTheCancellationSubscription(@TempDir Path work) throws Exception {
        var script = work.resolve("sidecar.mjs");
        Files.writeString(script, responsiveScript());
        var options = NodeRuntimeOptions.builder(node(), work.resolve("sessions"))
            .handshakeTimeout(Duration.ofSeconds(2)).heartbeatInterval(Duration.ofSeconds(5))
            .heartbeatTimeout(Duration.ofSeconds(2)).maxMessageBytes(256)
            .terminateTimeout(Duration.ofSeconds(2)).build();
        var disposed = new AtomicBoolean();
        var token = new CancellationToken() {
            @Override public boolean isCancelled() { return false; }
            @Override public Mono<Void> cancelled() {
                return Mono.<Void>never().doOnCancel(() -> disposed.set(true));
            }
        };

        try (var session = NodeSidecar.start(script, options, () -> { }).block()) {
            assertThrows(NodeRpcException.class, () -> session.beginRequest(
                "echo", Map.of("value", "x".repeat(1024)), token));
            assertTrue(disposed.get());
        }
    }

    @Test
    void directSubscriberCancellationKeepsTheSharedSidecarAvailable(@TempDir Path work)
        throws Exception {
        var script = work.resolve("sidecar.mjs");
        Files.writeString(script, cooperativeCancellationScript());
        var options = NodeRuntimeOptions.builder(node(), work.resolve("sessions"))
            .handshakeTimeout(Duration.ofSeconds(2)).heartbeatInterval(Duration.ofSeconds(5))
            .heartbeatTimeout(Duration.ofSeconds(2)).terminateTimeout(Duration.ofSeconds(2)).build();
        try (var session = NodeSidecar.start(script, options, () -> { }).block()) {
            var request = session.request("wait", Map.of(), Duration.ofSeconds(2)).subscribe();

            awaitTrue(() -> Boolean.TRUE.equals(session.request("started", Map.of(),
                Duration.ofSeconds(2)).block()));
            var sibling = session.request("delayed", Map.of(), Duration.ofSeconds(2)).toFuture();
            request.dispose();

            assertEquals("sibling", sibling.join());
            awaitTrue(() -> Boolean.TRUE.equals(session.request("cancelled", Map.of(),
                Duration.ofSeconds(2)).block()));
            assertTrue(session.isAlive());
        }
    }

    @Test
    void requestTimeoutWaitsForCooperativeSettlementWithoutKillingSiblings(
        @TempDir Path work) throws Exception {
        var script = work.resolve("sidecar.mjs");
        Files.writeString(script, cooperativeCancellationScript());
        var options = NodeRuntimeOptions.builder(node(), work.resolve("sessions"))
            .handshakeTimeout(Duration.ofSeconds(2)).heartbeatInterval(Duration.ofSeconds(5))
            .heartbeatTimeout(Duration.ofSeconds(2)).terminateTimeout(Duration.ofSeconds(2)).build();

        try (var session = NodeSidecar.start(script, options, () -> { }).block()) {
            var sibling = session.request("delayed", Map.of(), Duration.ofSeconds(2)).toFuture();
            var failure = assertThrows(NodeRpcException.class,
                () -> session.request("wait", Map.of(), Duration.ofMillis(100)).block());

            assertEquals(NodeRpcPhase.TIMEOUT, failure.phase());
            assertEquals("sibling", sibling.join());
            assertTrue(session.isAlive());
        }
    }

    @Test
    void rejectsOversizedFramesAndReportsUnexpectedExit(@TempDir Path work)
        throws Exception {
        var script = work.resolve("sidecar.mjs");
        Files.writeString(script, oversizedScript());
        var options = NodeRuntimeOptions.builder(node(), work.resolve("sessions"))
            .handshakeTimeout(Duration.ofSeconds(2))
            .defaultRequestTimeout(Duration.ofSeconds(1))
            .requestCancellationTimeout(Duration.ofMillis(100))
            .heartbeatInterval(Duration.ofSeconds(5))
            .heartbeatTimeout(Duration.ofSeconds(2)).maxMessageBytes(256)
            .terminateTimeout(Duration.ofSeconds(2)).build();
        var session = NodeSidecar.start(script, options, () -> { }).block();

        assertThrows(NodeRpcException.class,
            () -> session.request("oversized", Map.of(), Duration.ofSeconds(2)).block());
        assertThrows(NodeRpcException.class, () -> session.termination().block());
    }

    @Test
    void heartbeatFailureTerminatesTheSidecar(@TempDir Path work) throws Exception {
        var script = work.resolve("sidecar.mjs");
        Files.writeString(script, handshakeOnlyScript());
        var options = NodeRuntimeOptions.builder(node(), work.resolve("sessions"))
            .handshakeTimeout(Duration.ofSeconds(2))
            .heartbeatInterval(Duration.ofMillis(50))
            .heartbeatTimeout(Duration.ofMillis(50))
            .requestCancellationTimeout(Duration.ofMillis(50))
            .terminateTimeout(Duration.ofSeconds(2)).build();
        var disableRequests = new AtomicInteger();
        var session = NodeSidecar.start(script, options, disableRequests::incrementAndGet).block();

        assertThrows(NodeRpcException.class,
            () -> session.termination().block(Duration.ofSeconds(3)));
        assertFalse(session.isAlive());
        assertEquals(0, disableRequests.get());
    }

    @Test
    void unexpectedExitDoesNotRequestDisable(@TempDir Path work) throws Exception {
        var script = work.resolve("sidecar.mjs");
        Files.writeString(script, exitsAfterHandshakeScript());
        var disableRequests = new AtomicInteger();
        var options = NodeRuntimeOptions.builder(node(), work.resolve("sessions"))
            .handshakeTimeout(Duration.ofSeconds(2))
            .heartbeatInterval(Duration.ofSeconds(5)).build();
        var session = NodeSidecar.start(script, options, disableRequests::incrementAndGet)
            .block();

        assertThrows(NodeRpcException.class,
            () -> session.termination().block(Duration.ofSeconds(2)));
        assertEquals(0, disableRequests.get());
    }

    @Test
    void eachDisableNotificationIsForwardedAndKeepsTheSidecarRunning(
        @TempDir Path work) throws Exception {
        var script = work.resolve("sidecar.mjs");
        Files.writeString(script, disableThenRespondScript());
        var disableRequests = new AtomicInteger();
        var options = NodeRuntimeOptions.defaults(node(), work.resolve("sessions"));

        try (var session = NodeSidecar.start(script, options,
            disableRequests::incrementAndGet).block()) {
            assertEquals("ok", session.request("trigger-disable", Map.of(),
                Duration.ofSeconds(2)).block());
            assertEquals("still-running", session.request("echo", Map.of(),
                Duration.ofSeconds(2)).block());
            assertEquals(2, disableRequests.get());
        }
    }

    @Test
    void rejectsMalformedDisableNotifications(@TempDir Path work) throws Exception {
        for (var notification : new String[] {
            "{jsonrpc:'2.0',id:null,method:'fibra.disable',params:{}}",
            "{jsonrpc:'2.0',method:'fibra.disable'}",
            "{jsonrpc:'2.0',method:'fibra.disable',params:{value:true}}",
            "{jsonrpc:'2.0',method:'fibra.disable',params:{},result:{ok:true}}"
        }) {
            var script = work.resolve("sidecar-" + notification.hashCode() + ".mjs");
            Files.writeString(script, malformedDisableScript(notification));
            var disableRequests = new AtomicInteger();
            var options = NodeRuntimeOptions.builder(node(), work.resolve("sessions"))
                .handshakeTimeout(Duration.ofSeconds(2)).build();
            var session = NodeSidecar.start(script, options,
                disableRequests::incrementAndGet).block();

            assertThrows(NodeRpcException.class,
                () -> session.request("trigger-disable", Map.of(),
                    Duration.ofSeconds(2)).block());
            assertThrows(NodeRpcException.class,
                () -> session.termination().block(Duration.ofSeconds(2)));
            assertEquals(0, disableRequests.get());
        }
    }

    @Test
    void exposesStructuredJsonRpcFailuresWithoutRawMutableData(@TempDir Path work)
        throws Exception {
        var script = work.resolve("sidecar.mjs");
        Files.writeString(script, structuredErrorScript());
        try (var session = NodeSidecar.start(script,
            NodeRuntimeOptions.defaults(node(), work.resolve("sessions")), () -> { }).block()) {
            var failure = assertThrows(NodeRpcException.class,
                () -> session.request("fail", Map.of(), Duration.ofSeconds(2)).block());

            assertEquals(NodeRpcPhase.REQUEST, failure.phase());
            var remote = failure.remoteFailure().orElseThrow();
            assertEquals(-32001, remote.code());
            assertEquals("missing file", remote.message());
            assertEquals(LiteralValue.of(Map.of("kind", "fibra.tool.failure",
                "schemaVersion", 2, "code", "NOT_FOUND")), remote.data());
        }
    }

    @Test
    void rejectsMalformedJsonRpcErrorObjects(@TempDir Path work) throws Exception {
        for (var error : new String[] {
            "{code: 1.5, message: 'fraction'}",
            "{code: 2147483648, message: 'overflow'}",
            "{code: -32001}",
            "{code: -32001, message: 'extra', extra: true}"
        }) {
            var script = work.resolve("error-" + error.hashCode() + ".mjs");
            Files.writeString(script, malformedErrorScript(error));
            try (var session = NodeSidecar.start(script,
                NodeRuntimeOptions.defaults(node(), work.resolve("sessions")), () -> { }).block()) {
                var failure = assertThrows(NodeRpcException.class,
                    () -> session.request("fail", Map.of(), Duration.ofSeconds(2)).block());
                assertEquals(NodeRpcPhase.PROTOCOL, failure.phase());
                assertTrue(failure.remoteFailure().isEmpty());
            }
        }
    }

    @Test
    void rejectsAmbiguousAndDuplicateJsonRpcResponseMembers(@TempDir Path work)
        throws Exception {
        for (var response : new String[] {
            "JSON.stringify({jsonrpc:'2.0',id,result:{ok:true},error:{code:-32001,message:'missing',"
                + "data:{kind:'fibra.tool.failure',schemaVersion:2,code:'NOT_FOUND'}}})",
            "'{\"jsonrpc\":\"2.0\",\"id\":' + id + ',\"result\":{\"one\":1},"
                + "\"result\":{\"two\":2}}'",
            "JSON.stringify({jsonrpc:'2.0',id,result:{ok:true}}) + JSON.stringify({extra:true})"
        }) {
            var script = work.resolve("response-" + response.hashCode() + ".mjs");
            Files.writeString(script, malformedResponseScript(response));
            try (var session = NodeSidecar.start(script,
                NodeRuntimeOptions.defaults(node(), work.resolve("sessions")), () -> { }).block()) {
                var failure = assertThrows(NodeRpcException.class,
                    () -> session.request("fail", Map.of(), Duration.ofSeconds(2)).block());
                assertEquals(NodeRpcPhase.PROTOCOL, failure.phase());
                assertTrue(failure.remoteFailure().isEmpty());
            }
        }
    }

    @Test
    void rejectsFractionalErrorCodesWithoutJavascriptNumberRounding(@TempDir Path work)
        throws Exception {
        var script = work.resolve("sidecar.mjs");
        Files.writeString(script, rawFractionalErrorScript());
        try (var session = NodeSidecar.start(script,
            NodeRuntimeOptions.defaults(node(), work.resolve("sessions")), () -> { }).block()) {
            var failure = assertThrows(NodeRpcException.class,
                () -> session.request("fail", Map.of(), Duration.ofSeconds(2)).block());
            assertEquals(NodeRpcPhase.PROTOCOL, failure.phase());
            assertTrue(failure.remoteFailure().isEmpty());
        }
    }

    @Test
    void rejectsDisableNotificationBeforeHandshakeCompletes(@TempDir Path work)
        throws Exception {
        var script = work.resolve("sidecar.mjs");
        Files.writeString(script, disableBeforeHandshakeScript());
        var disableRequests = new AtomicInteger();
        var options = NodeRuntimeOptions.builder(node(), work.resolve("sessions"))
            .handshakeTimeout(Duration.ofSeconds(2)).build();

        assertThrows(NodeRpcException.class, () -> NodeSidecar.start(script, options,
            disableRequests::incrementAndGet).block());
        assertEquals(0, disableRequests.get());
    }

    private static Path node() {
        return Path.of(System.getProperty("fibra.test.node", "node"));
    }

    private static String responsiveScript() {
        return baseScript("""
              if (method === 'echo') reply(id, message.params);
            """);
    }

    private static String blockingScript() {
        return """
            import readline from 'node:readline';
            import { spawn } from 'node:child_process';
            const child = spawn(process.execPath, ['-e', "process.on('SIGTERM', () => {}); setInterval(() => {}, 1000)"]);
            let cancelled = false;
            let started = false;
            const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              const {id, method} = message;
              if (method === 'fibra.handshake') reply(id, {protocol:1});
              else if (method === 'fibra.ping') reply(id, {ok:true});
              else if (method === 'childPid') reply(id, child.pid);
              else if (method === 'never') started = true;
              else if (method === 'started') reply(id, started);
              else if (method === '$/cancelRequest') cancelled = true;
              else if (method === 'cancelled') reply(id, cancelled);
            });
            """;
    }

    private static String cooperativeCancellationScript() {
        return """
            import readline from 'node:readline';
            let waitingRequest;
            let started = false;
            let cancelled = false;
            const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              const {id, method} = message;
              if (method === 'fibra.handshake') reply(id, {protocol:1});
              else if (method === 'fibra.ping') reply(id, {ok:true});
              else if (method === 'wait') {
                waitingRequest = id;
                started = true;
              }
              else if (method === 'started') reply(id, started);
              else if (method === 'cancelled') reply(id, cancelled);
              else if (method === 'delayed') setTimeout(() => reply(id, 'sibling'), 150);
              else if (method === '$/cancelRequest') {
                cancelled = true;
                const target = message.params.id;
                if (target === waitingRequest) {
                  setTimeout(() => reply(target, 'settled'), 25);
                }
              }
            });
            """;
    }

    private static String oversizedScript() {
        return baseScript("""
              if (method === 'oversized') process.stdout.write('x'.repeat(1024) + '\\n');
            """);
    }

    private static String handshakeOnlyScript() {
        return """
            import readline from 'node:readline';
            const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              if (message.method === 'fibra.handshake') reply(message.id, {protocol:1});
            });
            """;
    }

    private static String disableThenRespondScript() {
        return baseScript("""
              if (method === 'trigger-disable') {
                process.stdout.write(JSON.stringify({jsonrpc:'2.0',method:'fibra.disable',params:{}}) + '\\n');
                process.stdout.write(JSON.stringify({jsonrpc:'2.0',method:'fibra.disable',params:{}}) + '\\n');
                reply(id, 'ok');
              }
              else if (method === 'echo') reply(id, 'still-running');
            """);
    }

    private static String disableBeforeHandshakeScript() {
        return """
            import readline from 'node:readline';
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              if (message.method === 'fibra.handshake') {
                process.stdout.write(JSON.stringify({jsonrpc:'2.0',method:'fibra.disable',params:{}}) + '\\n');
              }
            });
            """;
    }

    private static String exitsAfterHandshakeScript() {
        return """
            import readline from 'node:readline';
            const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              if (message.method === 'fibra.handshake') {
                reply(message.id, {protocol:1});
                setTimeout(() => process.exit(17), 50);
              }
            });
            """;
    }

    private static String malformedDisableScript(String notification) {
        return baseScript("""
              if (method === 'trigger-disable') {
                process.stdout.write(JSON.stringify(%s) + '\\n');
                reply(id, {ok:true});
              }
            """.formatted(notification));
    }

    private static String structuredErrorScript() {
        return baseScript("""
              if (method === 'fail') process.stdout.write(JSON.stringify({jsonrpc:'2.0', id,
                error:{code:-32001,message:'missing file',data:{kind:'fibra.tool.failure',schemaVersion:2,code:'NOT_FOUND'}}}) + '\\n');
            """);
    }

    private static String malformedErrorScript(String error) {
        return baseScript("""
              if (method === 'fail') process.stdout.write(JSON.stringify({jsonrpc:'2.0', id,
                error:%s}) + '\\n');
            """.formatted(error));
    }

    private static String rawFractionalErrorScript() {
        return """
            import readline from 'node:readline';
            const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              if (message.method === 'fibra.handshake') reply(message.id, {protocol:1});
              else if (message.method === 'fibra.ping') reply(message.id, {ok:true});
              else if (message.method === 'fail') process.stdout.write('{"jsonrpc":"2.0","id":' + message.id + ',"error":{"code":1.0000000000000001,"message":"fraction"}}\\n');
            });
            """;
    }

    private static String malformedResponseScript(String response) {
        return baseScript("""
              if (method === 'fail') process.stdout.write(%s + '\\n');
            """.formatted(response));
    }

    private static String baseScript(String body) {
        return """
            import readline from 'node:readline';
            const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              const {id, method} = message;
              if (method === 'fibra.handshake') reply(id, {protocol:1});
              else if (method === 'fibra.ping') reply(id, {ok:true});
            %s
            });
            """.formatted(body);
    }

    private static void awaitTrue(java.util.concurrent.Callable<Boolean> check) throws Exception {
        for (var attempt = 0; attempt < 20; attempt++) {
            if (check.call()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("condition did not become true");
    }
}
