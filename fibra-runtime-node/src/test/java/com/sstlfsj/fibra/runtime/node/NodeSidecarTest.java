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
