package com.sstlfsj.fibra.runtime.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeSidecarTest {
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
    void timesOutCancelsAndTerminatesTheCompleteProcessTree(@TempDir Path work)
        throws Exception {
        var script = work.resolve("sidecar.mjs");
        Files.writeString(script, blockingScript());
        var options = NodeRuntimeOptions.builder(node(), work.resolve("sessions"))
            .handshakeTimeout(Duration.ofSeconds(2))
            .defaultRequestTimeout(Duration.ofMillis(100))
            .heartbeatInterval(Duration.ofSeconds(5))
            .heartbeatTimeout(Duration.ofSeconds(2))
            .maxMessageBytes(64 * 1024)
            .terminateTimeout(Duration.ofSeconds(2)).build();
        var session = NodeSidecar.start(script, options, () -> { }).block();
        var childPid = Long.parseLong(session.request("childPid", Map.of(),
            Duration.ofSeconds(2)).block().toString());

        assertThrows(NodeRpcException.class,
            () -> session.request("never", Map.of(), Duration.ofMillis(100)).block());
        assertEquals(true, session.request("cancelled", Map.of(),
            Duration.ofSeconds(2)).block());
        session.close();

        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
        try (var sessions = Files.list(work.resolve("sessions"))) {
            assertTrue(sessions.findAny().isEmpty());
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
            const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              const {id, method} = message;
              if (method === 'fibra.handshake') reply(id, {protocol:1});
              else if (method === 'fibra.ping') reply(id, {ok:true});
              else if (method === 'childPid') reply(id, child.pid);
              else if (method === '$/cancelRequest') cancelled = true;
              else if (method === 'cancelled') reply(id, cancelled);
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
}
