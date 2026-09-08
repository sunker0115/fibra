package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactState;
import com.sstlfsj.fibra.bridge.ContributionBridge;
import com.sstlfsj.fibra.bridge.ContributionCodec;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.engine.RuntimeChangeRequest;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodePluginRuntimeAdapterTest {
    private static final ContributionKind<EchoDescriptor, String, String> ECHO =
        ContributionKind.remote("echo", EchoDescriptor.class, String.class,
            String.class, new EchoCodec());

    @Test
    void mountsRemoteContributionsThroughTheSharedBridgeAndDrainsBeforeStop(
        @TempDir Path work) throws Exception {
        var artifactRoot = work.resolve("echo-node");
        Files.createDirectories(artifactRoot);
        Files.writeString(artifactRoot.resolve("fibra-plugin.yaml"), """
            id: echo-node
            version: 1.0.0
            protocol: 1
            entrypoint: index.mjs
            contributions:
              - name: say
                kind: echo
                schemaVersion: 1
                method: echo
                descriptor:
                  title: Echo
            """);
        Files.writeString(artifactRoot.resolve("index.mjs"), script());
        var artifact = ArtifactRecord.builder()
            .id(new ArtifactId("echo-node"))
            .runtimeId(NodePluginRuntimeAdapter.RUNTIME_ID)
            .version("1.0.0")
            .checksum("checksum")
            .revision("revision")
            .location(artifactRoot)
            .state(ArtifactState.INSTALLED)
            .updatedAt(Instant.now())
            .build();
        var bridge = new ContributionBridge();
        var options = NodeRuntimeOptions.defaults(node(), work.resolve("sessions"));
        var adapter = new NodePluginRuntimeAdapter(bridge,
            name -> "echo".equals(name) ? Optional.of(ECHO) : Optional.empty(), options);
        var prepared = adapter.prepare(new RuntimeChangeRequest(
            NodePluginRuntimeAdapter.RUNTIME_ID, List.of(artifact), null)).block();
        prepared.commit().block();

        try (var runtime = FibraRuntime.create()) {
            var entry = prepared.catalog().find("echo-node").orElseThrow();
            @SuppressWarnings("unchecked")
            var definition = (com.sstlfsj.fibra.PluginDefinition<Object>)
                entry.definition();
            var instance = runtime.rootScope().context().plugins()
                .mount("echo-instance", definition, Map.of());
            instance.settled().block(Duration.ofSeconds(3));

            assertEquals("Echo", ((EchoDescriptor) bridge.snapshot().entries()
                .getFirst().descriptor()).title());
            assertEquals("hello", bridge.invoke(runtime.rootScope().context(), ECHO,
                new ContributionId("echo-instance", "say"), "hello")
                .block(Duration.ofSeconds(2)));

            instance.dispose().block(Duration.ofSeconds(3));
            assertTrue(bridge.snapshot().entries().isEmpty());
        }
    }

    @Test
    void unexpectedSidecarExitFailsThePluginAndRevokesItsContributions(
        @TempDir Path work) throws Exception {
        var artifactRoot = work.resolve("failing-node");
        Files.createDirectories(artifactRoot);
        Files.writeString(artifactRoot.resolve("fibra-plugin.yaml"), """
            id: failing-node
            version: 1.0.0
            protocol: 1
            entrypoint: index.mjs
            contributions:
              - name: say
                kind: echo
                schemaVersion: 1
                method: echo
                descriptor:
                  title: Failing
            """);
        Files.writeString(artifactRoot.resolve("index.mjs"), exitingScript());
        var artifact = ArtifactRecord.builder()
            .id(new ArtifactId("failing-node"))
            .runtimeId(NodePluginRuntimeAdapter.RUNTIME_ID)
            .version("1.0.0")
            .checksum("checksum")
            .revision("revision")
            .location(artifactRoot)
            .state(ArtifactState.INSTALLED)
            .updatedAt(Instant.now())
            .build();
        var bridge = new ContributionBridge();
        var adapter = new NodePluginRuntimeAdapter(bridge,
            name -> "echo".equals(name) ? Optional.of(ECHO) : Optional.empty(),
            NodeRuntimeOptions.defaults(node(), work.resolve("sessions")));
        var prepared = adapter.prepare(new RuntimeChangeRequest(
            NodePluginRuntimeAdapter.RUNTIME_ID, List.of(artifact), null)).block();

        try (var runtime = FibraRuntime.create()) {
            @SuppressWarnings("unchecked")
            var definition = (com.sstlfsj.fibra.PluginDefinition<Object>)
                prepared.catalog().find("failing-node").orElseThrow().definition();
            var instance = runtime.rootScope().context().plugins()
                .mount("failing-instance", definition, Map.of());
            instance.settled().block(Duration.ofSeconds(3));

            Thread.sleep(300);
            instance.settled().onErrorResume(error -> Mono.just(instance))
                .block(Duration.ofSeconds(3));

            assertEquals(PluginInstanceState.FAILED, instance.state());
            assertTrue(instance.failure().orElseThrow().getMessage()
                .contains("exited unexpectedly"));
            assertTrue(bridge.snapshot().entries().isEmpty());
        }
    }

    @Test
    void rejectsAnEntrypointThatEscapesTheArtifact(@TempDir Path work) throws Exception {
        var artifactRoot = work.resolve("escaped-node");
        Files.createDirectories(artifactRoot);
        Files.writeString(work.resolve("outside.mjs"), script());
        Files.writeString(artifactRoot.resolve("fibra-plugin.yaml"), """
            id: escaped-node
            version: 1.0.0
            protocol: 1
            entrypoint: ../outside.mjs
            contributions:
              - name: say
                kind: echo
                schemaVersion: 1
                method: echo
                descriptor: { title: Escaped }
            """);
        var artifact = ArtifactRecord.builder().id(new ArtifactId("escaped-node"))
            .runtimeId(NodePluginRuntimeAdapter.RUNTIME_ID).version("1.0.0")
            .checksum("checksum").revision("revision").location(artifactRoot)
            .state(ArtifactState.INSTALLED).updatedAt(Instant.now()).build();
        var adapter = new NodePluginRuntimeAdapter(new ContributionBridge(),
            name -> Optional.of(ECHO),
            NodeRuntimeOptions.defaults(node(), work.resolve("sessions")));

        assertThrows(NodeRuntimeException.class, () -> adapter.inspect(artifact).block());
    }

    private static Path node() {
        return Path.of(System.getProperty("fibra.test.node", "node"));
    }

    private static String script() {
        return """
            import readline from 'node:readline';
            const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              const {id, method} = message;
              if (method === 'fibra.handshake') reply(id, {protocol:1});
              else if (method === 'fibra.ping') reply(id, {ok:true});
              else if (method === 'fibra.start') reply(id, {ok:true});
              else if (method === 'fibra.stop') reply(id, {ok:true});
              else if (method === 'echo') reply(id, message.params.input);
            });
            """;
    }

    private static String exitingScript() {
        return """
            import readline from 'node:readline';
            const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              const {id, method} = message;
              if (method === 'fibra.handshake') reply(id, {protocol:1});
              else if (method === 'fibra.ping') reply(id, {ok:true});
              else if (method === 'fibra.start') {
                reply(id, {ok:true});
                setTimeout(() => process.exit(17), 100);
              }
              else if (method === 'fibra.stop') reply(id, {ok:true});
              else if (method === 'echo') reply(id, message.params.input);
            });
            """;
    }

    private record EchoDescriptor(String title) {
    }

    private static final class EchoCodec
        implements ContributionCodec<EchoDescriptor, String, String> {
        @Override
        public int schemaVersion() {
            return 1;
        }

        @Override
        public EchoDescriptor decodeDescriptor(Object descriptor) {
            return new EchoDescriptor(((Map<?, ?>) descriptor).get("title").toString());
        }

        @Override
        public Object encodeInput(String input) {
            return input;
        }

        @Override
        public String decodeInput(Object input) {
            return input.toString();
        }

        @Override
        public Object encodeOutput(String output) {
            return output;
        }

        @Override
        public String decodeOutput(Object output) {
            return output.toString();
        }
    }
}
