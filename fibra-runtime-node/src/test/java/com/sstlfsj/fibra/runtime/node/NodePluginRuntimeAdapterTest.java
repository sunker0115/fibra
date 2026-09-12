package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.CancellationSource;
import com.sstlfsj.fibra.CancellationToken;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactPackage;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.artifact.ArtifactState;
import com.sstlfsj.fibra.bridge.ContributionDirectory;
import com.sstlfsj.fibra.bridge.ContributionCodec;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ManagedPluginControl;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolException;
import com.sstlfsj.fibra.plugins.tool.ToolFailureCode;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodePluginRuntimeAdapterTest {
    @Test
    void creatingAndClosingANewHandleDoesNotReadTheArtifact(@TempDir Path work) {
        var missing = ArtifactRecord.builder().id(new ArtifactId("missing"))
            .runtimeId(NodePluginRuntimeAdapter.RUNTIME_ID).version("1").checksum("digest")
            .revision("1").location(work.resolve("absent")).state(ArtifactState.STAGED)
            .updatedAt(Instant.EPOCH).build();
        var adapter = new NodePluginRuntimeAdapter(name -> Optional.empty(),
            NodeRuntimeOptions.defaults(node(), work.resolve("sessions")));
        var owner = adapter.create();
        var update = owner.createUpdate(List.of(missing));
        update.closeAsync().block();
        assertThrows(IllegalStateException.class, () -> update.prepareAsync().block());
        owner.closeAsync().block();
        assertTrue(Files.notExists(work.resolve("sessions")));
    }

    private static final ContributionKind<EchoDescriptor, String, String> ECHO =
        ContributionKind.remote("echo", EchoDescriptor.class, String.class,
            String.class, new EchoCodec());

    @Test
    void probeReturnsTheCompleteNodePackageRoot(@TempDir Path work) throws Exception {
        var root = work.resolve("echo-package");
        var payload = nodePackage(root);
        Files.writeString(payload.resolve("fibra-plugin.yaml"), manifest("echo-node"));
        Files.writeString(payload.resolve("index.mjs"), script());
        var adapter = adapter(work);

        var deployment = adapter.probe(ArtifactPackage.read(root)).block();

        assertEquals(new ArtifactId("echo-node"), deployment.artifactId());
        assertEquals(NodePluginRuntimeAdapter.RUNTIME_ID, deployment.runtimeId());
        assertEquals("1.0.0", deployment.version());
        assertEquals(root.toRealPath(), deployment.source());
    }

    @Test
    void inspectsAndStartsOnlyFromTheManagedPackagePayload(@TempDir Path work) throws Exception {
        var source = work.resolve("source-package");
        var payload = nodePackage(source);
        Files.writeString(payload.resolve("fibra-plugin.yaml"), manifest("echo-node"));
        Files.writeString(payload.resolve("index.mjs"), script());
        ArtifactRecord installed;
        try (var store = new ArtifactStore(work.resolve("store"));
             var transaction = store.prepareInstall(new ArtifactId("echo-node"),
                 NodePluginRuntimeAdapter.RUNTIME_ID, "1.0.0", source)) {
            installed = transaction.save();
        }
        Files.delete(payload.resolve("index.mjs"));
        var adapter = adapter(work);
        var owner = adapter.create();
        var update = owner.createUpdate(List.of(installed));

        assertEquals("index.mjs", adapter.inspect(installed).block().metadata().get("entrypoint"));
        update.prepareAsync().block();
        update.adopt();
        update.closeAsync().block();
        try (var runtime = FibraRuntime.create()) {
            var directory = new ContributionDirectory();
            runtime.rootScope().context().services().provide(
                ContributionServices.REGISTRAR, directory);
            @SuppressWarnings("unchecked")
            var definition = (com.sstlfsj.fibra.PluginDefinition<Object>) owner.catalog()
                .plugins().find("echo-node").orElseThrow().definition();
            var instance = runtime.rootScope().context().plugins()
                .mount("echo-instance", definition.prepare(Map.of()));
            instance.settled().block(Duration.ofSeconds(3));
            assertEquals("managed", directory.current().routes().invoke(
                runtime.rootScope().context(), ECHO,
                new ContributionId("echo-instance", "say"), "managed")
                .block(Duration.ofSeconds(2)));
            instance.dispose().block(Duration.ofSeconds(3));
        }
        owner.closeAsync().block();
    }

    @Test
    void rejectsPayloadFilesAndBareLegacyDirectories(@TempDir Path work) throws Exception {
        var filePayload = work.resolve("file-payload");
        Files.createDirectories(filePayload);
        Files.writeString(filePayload.resolve("plugin.properties"), """
            formatVersion=1
            runtime=node
            payload=index.mjs
            """);
        Files.writeString(filePayload.resolve("index.mjs"), script());
        var bare = work.resolve("bare");
        Files.createDirectories(bare);
        Files.writeString(bare.resolve("fibra-plugin.yaml"), manifest("echo-node"));
        var adapter = adapter(work);

        assertThrows(NodeRuntimeException.class,
            () -> adapter.probe(ArtifactPackage.read(filePayload)).block());
        assertThrows(com.sstlfsj.fibra.artifact.ArtifactException.class,
            () -> ArtifactPackage.read(bare));
    }

    @Test
    void rejectsPackageRuntimeAndIdentityMismatches(@TempDir Path work) throws Exception {
        var wrongRuntime = work.resolve("wrong-runtime");
        var payload = nodePackage(wrongRuntime);
        Files.writeString(wrongRuntime.resolve("plugin.properties"), """
            formatVersion=1
            runtime=java
            payload=payload
            """);
        Files.writeString(payload.resolve("fibra-plugin.yaml"), manifest("echo-node"));
        var root = work.resolve("identity-mismatch");
        var identityPayload = nodePackage(root);
        Files.writeString(identityPayload.resolve("fibra-plugin.yaml"), manifest("other"));
        Files.writeString(identityPayload.resolve("index.mjs"), script());
        var adapter = adapter(work);
        var artifact = artifact(root, "echo-node");

        assertThrows(IllegalArgumentException.class,
            () -> adapter.probe(ArtifactPackage.read(wrongRuntime)).block());
        assertThrows(NodeRuntimeException.class, () -> adapter.inspect(artifact).block());
    }

    @Test
    void rejectsAnAbsoluteEntrypointEvenWhenItNamesThePayloadFile(@TempDir Path work)
        throws Exception {
        var root = work.resolve("absolute-node");
        var payload = nodePackage(root);
        var entrypoint = payload.resolve("index.mjs");
        Files.writeString(entrypoint, script());
        Files.writeString(payload.resolve("fibra-plugin.yaml"), """
            id: absolute-node
            version: 1.0.0
            protocol: 1
            entrypoint: %s
            contributions:
              - name: say
                kind: echo
                schemaVersion: 1
                method: echo
                descriptor: { title: Absolute }
            """.formatted(entrypoint));
        var adapter = adapter(work);

        assertThrows(NodeRuntimeException.class,
            () -> adapter.probe(ArtifactPackage.read(root)).block());
    }

    @Test
    void mountsRemoteContributionsThroughTheDomainDirectoryAndDrainsBeforeStop(
        @TempDir Path work) throws Exception {
        var artifactRoot = work.resolve("echo-node");
        var payload = nodePackage(artifactRoot);
        Files.writeString(payload.resolve("fibra-plugin.yaml"), """
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
        Files.writeString(payload.resolve("index.mjs"), script());
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
        var directory = new ContributionDirectory();
        var options = NodeRuntimeOptions.defaults(node(), work.resolve("sessions"));
        var adapter = new NodePluginRuntimeAdapter(
            name -> "echo".equals(name) ? Optional.of(ECHO) : Optional.empty(), options);
        var owner = adapter.create();
        var update = owner.createUpdate(List.of(artifact));
        update.prepareAsync().block();
        update.adopt();
        update.closeAsync().block();

        try (var runtime = FibraRuntime.create()) {
            runtime.rootScope().context().services().provide(
                ContributionServices.REGISTRAR, directory);
            var entry = owner.catalog().plugins().find("echo-node").orElseThrow();
            @SuppressWarnings("unchecked")
            var definition = (com.sstlfsj.fibra.PluginDefinition<Object>)
                entry.definition();
            var instance = runtime.rootScope().context().plugins()
                .mount("echo-instance", definition.prepare(Map.of()));
            instance.settled().block(Duration.ofSeconds(3));

            assertEquals("Echo", ((EchoDescriptor) directory.current().snapshot().entries()
                .getFirst().descriptor()).title());
            assertEquals("hello", directory.current().routes().invoke(
                runtime.rootScope().context(), ECHO,
                new ContributionId("echo-instance", "say"), "hello")
                .block(Duration.ofSeconds(2)));

            instance.dispose().block(Duration.ofSeconds(3));
            assertTrue(directory.current().snapshot().entries().isEmpty());
        }
        owner.closeAsync().block();
    }

    @Test
    void rejectsLegacyToolCodecBeforeStartingNode(@TempDir Path work) throws Exception {
        var root = work.resolve("legacy-tool");
        var payload = nodePackage(root);
        Files.writeString(payload.resolve("fibra-plugin.yaml"), toolManifest().replace("schemaVersion: 2", "schemaVersion: 1"));
        Files.writeString(payload.resolve("index.mjs"), "throw new Error('must not start');");
        var sessions = work.resolve("sessions");
        var adapter = new NodePluginRuntimeAdapter(name -> "fibra.tool".equals(name)
            ? Optional.of(ToolContributions.KIND) : Optional.empty(),
            NodeRuntimeOptions.defaults(work.resolve("unavailable-node"), sessions));

        var failure = assertThrows(NodeRuntimeException.class,
            () -> adapter.probe(com.sstlfsj.fibra.artifact.ArtifactPackage.read(root)).block());

        assertTrue(failure.getMessage().contains("schema version"));
        assertFalse(Files.exists(sessions));
    }

    @Test
    void preservesToolFailuresAndCancellationAcrossTheNodeBoundary(@TempDir Path work)
        throws Exception {
        var artifactRoot = work.resolve("tool-node");
        var payload = nodePackage(artifactRoot);
        Files.writeString(payload.resolve("fibra-plugin.yaml"), toolManifest());
        Files.writeString(payload.resolve("index.mjs"), toolScript());
        var adapter = new NodePluginRuntimeAdapter(name -> "fibra.tool".equals(name)
            ? Optional.of(ToolContributions.KIND) : Optional.empty(),
            NodeRuntimeOptions.defaults(node(), work.resolve("sessions")));
        var owner = adapter.create();
        var update = owner.createUpdate(List.of(artifact(artifactRoot, "tool-node")));
        update.prepareAsync().block();
        update.adopt();
        update.closeAsync().block();

        try (var runtime = FibraRuntime.create()) {
            var directory = new ContributionDirectory();
            runtime.rootScope().context().services().provide(
                ContributionServices.REGISTRAR, directory);
            @SuppressWarnings("unchecked")
            var definition = (com.sstlfsj.fibra.PluginDefinition<Object>) owner.catalog()
                .plugins().find("tool-node").orElseThrow().definition();
            var instance = runtime.rootScope().context().plugins()
                .mount("tool-instance", definition.prepare(Map.of()));
            instance.settled().block(Duration.ofSeconds(3));

            var preCancelled = new CancellationSource();
            preCancelled.cancel();
            var beforeStart = assertThrows(ToolException.class, () -> directory.current().routes()
                .invoke(runtime.rootScope().context(), ToolContributions.KIND,
                    ToolContributions.id("tool-instance", "run"),
                    ToolRequest.of(Map.of("command", "wait"), preCancelled.token()))
                .block(Duration.ofSeconds(2)));
            assertEquals(ToolFailureCode.ABORTED, beforeStart.failure().code());
            assertEquals(0, integer(status(runtime, directory, "runCalls")));

            var cancellationChecks = new AtomicInteger();
            var cancelledBetweenChecks = new CancellationToken() {
                @Override
                public boolean isCancelled() {
                    return cancellationChecks.incrementAndGet() > 1;
                }

                @Override
                public Mono<Void> cancelled() {
                    return Mono.never();
                }
            };
            var beforeSend = assertThrows(ToolException.class, () -> directory.current().routes()
                .invoke(runtime.rootScope().context(), ToolContributions.KIND,
                    ToolContributions.id("tool-instance", "run"),
                    ToolRequest.of(Map.of("command", "wait"), cancelledBetweenChecks))
                .block(Duration.ofSeconds(2)));
            assertEquals(ToolFailureCode.ABORTED, beforeSend.failure().code());
            assertEquals(0, integer(status(runtime, directory, "runCalls")));

            var closedCaller = runtime.rootScope().openChild("closed-caller");
            closedCaller.closeAsync().block(Duration.ofSeconds(2));
            assertThrows(com.sstlfsj.fibra.FibraException.class,
                () -> directory.current().routes().invoke(closedCaller.context(),
                    ToolContributions.KIND, ToolContributions.id("tool-instance", "run"),
                    ToolRequest.of(Map.of("command", "done"))).block(Duration.ofSeconds(2)));
            assertEquals(0, integer(status(runtime, directory, "runCalls")));

            var businessFailure = assertThrows(ToolException.class, () -> directory.current().routes()
                .invoke(runtime.rootScope().context(), ToolContributions.KIND,
                    ToolContributions.id("tool-instance", "run"), ToolRequest.of(Map.of("command", "fail")))
                .block(Duration.ofSeconds(2)));
            assertEquals(ToolFailureCode.NOT_FOUND, businessFailure.failure().code());

            var nullOutput = assertThrows(IllegalArgumentException.class,
                () -> directory.current().routes().invoke(runtime.rootScope().context(),
                    ToolContributions.KIND, ToolContributions.id("tool-instance", "run"),
                    ToolRequest.of(Map.of("command", "null"))).block(Duration.ofSeconds(2)));
            assertEquals("invalid tool output fields", nullOutput.getMessage());

            var cancellation = new CancellationSource();
            var release = work.resolve("release");
            var pending = directory.current().routes().invoke(runtime.rootScope().context(),
                ToolContributions.KIND, ToolContributions.id("tool-instance", "run"),
                ToolRequest.of(Map.of("command", "wait", "releasePath", release.toString()),
                    cancellation.token())).toFuture();
            awaitStatus(runtime, directory, "waiting", true);
            cancellation.cancel();
            awaitStatus(runtime, directory, "cancelled", true);
            awaitStatus(runtime, directory, "waiting", false);
            assertFalse(pending.isDone());
            Files.writeString(release, "release");
            var completed = assertThrows(ExecutionException.class,
                () -> pending.get(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(ToolFailureCode.ABORTED,
                assertInstanceOf(ToolException.class, completed.getCause()).failure().code());

            var cancelledFailure = new CancellationSource();
            var failureRelease = work.resolve("failure-release");
            var failing = directory.current().routes().invoke(runtime.rootScope().context(),
                ToolContributions.KIND, ToolContributions.id("tool-instance", "run"),
                ToolRequest.of(Map.of("command", "fail-after-cancel",
                    "releasePath", failureRelease.toString()), cancelledFailure.token()))
                .toFuture();
            awaitStatus(runtime, directory, "waiting", true);
            cancelledFailure.cancel();
            awaitStatus(runtime, directory, "cancelled", true);
            Files.writeString(failureRelease, "release");
            var remoteFailure = assertThrows(ExecutionException.class,
                () -> failing.get(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(ToolFailureCode.NOT_FOUND,
                assertInstanceOf(ToolException.class, remoteFailure.getCause()).failure().code());

            var nullCancellation = new CancellationSource();
            var nullRelease = work.resolve("null-release");
            var nullAfterCancel = directory.current().routes().invoke(runtime.rootScope().context(),
                ToolContributions.KIND, ToolContributions.id("tool-instance", "run"),
                ToolRequest.of(Map.of("command", "null-after-cancel",
                    "releasePath", nullRelease.toString()), nullCancellation.token()))
                .toFuture();
            awaitStatus(runtime, directory, "waiting", true);
            nullCancellation.cancel();
            awaitStatus(runtime, directory, "cancelled", true);
            Files.writeString(nullRelease, "release");
            var nullCancellationFailure = assertThrows(ExecutionException.class,
                () -> nullAfterCancel.get(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(ToolFailureCode.ABORTED,
                assertInstanceOf(ToolException.class, nullCancellationFailure.getCause()).failure().code());

            instance.dispose().block(Duration.ofSeconds(3));
        }
        owner.closeAsync().block();
    }

    @Test
    void unexpectedSidecarExitFailsThePluginAndRevokesItsContributions(
        @TempDir Path work) throws Exception {
        var artifactRoot = work.resolve("failing-node");
        var payload = nodePackage(artifactRoot);
        Files.writeString(payload.resolve("fibra-plugin.yaml"), """
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
        Files.writeString(payload.resolve("index.mjs"), exitingScript());
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
        var directory = new ContributionDirectory();
        var adapter = new NodePluginRuntimeAdapter(
            name -> "echo".equals(name) ? Optional.of(ECHO) : Optional.empty(),
            NodeRuntimeOptions.defaults(node(), work.resolve("sessions")));
        var owner = adapter.create();
        var update = owner.createUpdate(List.of(artifact));
        update.prepareAsync().block();
        update.adopt();
        update.closeAsync().block();

        try (var runtime = FibraRuntime.create()) {
            runtime.rootScope().context().services().provide(
                ContributionServices.REGISTRAR, directory);
            @SuppressWarnings("unchecked")
            var definition = (com.sstlfsj.fibra.PluginDefinition<Object>)
                owner.catalog().plugins().find("failing-node").orElseThrow().definition();
            var instance = runtime.rootScope().context().plugins()
                .mount("failing-instance", definition.prepare(Map.of()));
            instance.settled().block(Duration.ofSeconds(3));

            Thread.sleep(300);
            instance.settled().onErrorResume(error -> Mono.just(instance))
                .block(Duration.ofSeconds(3));

            assertEquals(PluginInstanceState.FAILED, instance.state());
            assertTrue(instance.failure().orElseThrow().getMessage()
                .contains("exited unexpectedly"));
            assertTrue(directory.current().snapshot().entries().isEmpty());
        }
        owner.closeAsync().block();
    }

    @Test
    void forwardsDisableNotificationToTheCurrentWrapperContext(@TempDir Path work)
        throws Exception {
        var artifactRoot = work.resolve("disable-node");
        var payload = nodePackage(artifactRoot);
        Files.writeString(payload.resolve("fibra-plugin.yaml"), manifest("disable-node"));
        Files.writeString(payload.resolve("index.mjs"), disablingScript());
        var artifact = artifact(artifactRoot, "disable-node");
        var adapter = new NodePluginRuntimeAdapter(name -> "echo".equals(name)
            ? Optional.of(ECHO) : Optional.empty(),
            NodeRuntimeOptions.defaults(node(), work.resolve("sessions")));
        var owner = adapter.create();
        var update = owner.createUpdate(List.of(artifact));
        update.prepareAsync().block();
        update.adopt();
        update.closeAsync().block();
        var disabled = new AtomicReference<String>();

        try (var runtime = FibraRuntime.create()) {
            runtime.rootScope().context().services().provide(
                ContributionServices.REGISTRAR, new ContributionDirectory());
            runtime.rootScope().context().services().provide(ManagedPluginControl.KEY,
                instance -> disabled.set(instance.id()));
            @SuppressWarnings("unchecked")
            var definition = (com.sstlfsj.fibra.PluginDefinition<Object>) owner.catalog()
                .plugins().find("disable-node").orElseThrow().definition();
            var instance = runtime.rootScope().context().plugins()
                .mount("disable-instance", definition.prepare(Map.of()));
            instance.settled().block(Duration.ofSeconds(3));

            assertEquals("disable-instance", disabled.get());
        }
        owner.closeAsync().block();
    }

    @Test
    void rejectsAnEntrypointThatEscapesTheArtifact(@TempDir Path work) throws Exception {
        var artifactRoot = work.resolve("escaped-node");
        var payload = nodePackage(artifactRoot);
        Files.writeString(work.resolve("outside.mjs"), script());
        Files.writeString(payload.resolve("fibra-plugin.yaml"), """
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
        var adapter = new NodePluginRuntimeAdapter(name -> Optional.of(ECHO),
            NodeRuntimeOptions.defaults(node(), work.resolve("sessions")));

        assertThrows(NodeRuntimeException.class, () -> adapter.inspect(artifact).block());
    }

    private static Path node() {
        return Path.of(System.getProperty("fibra.test.node", "node"));
    }

    private static Object status(FibraRuntime runtime, ContributionDirectory directory, String key) {
        var result = directory.current().routes().invoke(runtime.rootScope().context(),
            ToolContributions.KIND, ToolContributions.id("tool-instance", "status"),
            ToolRequest.of(Map.of())).block(Duration.ofSeconds(2));
        return ((Map<?, ?>) result.structuredContent().orElseThrow().toJava()).get(key);
    }

    private static void awaitStatus(FibraRuntime runtime, ContributionDirectory directory,
                                    String key, Object expected) throws InterruptedException {
        for (var attempt = 0; attempt < 20; attempt++) {
            if (expected.equals(status(runtime, directory, key))) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("did not observe " + key + '=' + expected);
    }

    private static int integer(Object value) {
        return ((java.math.BigDecimal) value).intValueExact();
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

    private static String toolManifest() {
        return """
            id: tool-node
            version: 1.0.0
            protocol: 1
            entrypoint: index.mjs
            contributions:
              - name: run
                kind: fibra.tool
                schemaVersion: 2
                method: tool.run
                descriptor:
                  displayName: Node tool
                  description: Tool hosted by Node
                  inputSchema: { type: object }
                  outputSchema: { type: object }
              - name: status
                kind: fibra.tool
                schemaVersion: 2
                method: tool.status
                descriptor:
                  displayName: Node tool status
                  description: Tool status hosted by Node
                  inputSchema: { type: object }
                  outputSchema: { type: object }
            """;
    }

    private static String toolScript() {
        return """
            import { existsSync } from 'node:fs';
            import readline from 'node:readline';
            let runCalls = 0;
            let waiting = false;
            let cancelled = false;
            let waitingRequest;
            let releasePath;
            let failAfterCancel = false;
            let nullAfterCancel = false;
            const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            const fail = (id, error) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, error}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              const {id, method} = message;
              if (method === 'fibra.handshake') reply(id, {protocol:1});
              else if (method === 'fibra.ping' || method === 'fibra.start' || method === 'fibra.stop') reply(id, {ok:true});
              else if (method === 'tool.run') {
                runCalls++;
                const command = message.params.input.arguments.command;
                if (command === 'fail') fail(id, {code:-32001, message:'missing', data:{kind:'fibra.tool.failure', schemaVersion:2, code:'NOT_FOUND'}});
                else if (command === 'null') reply(id, null);
                else if (command === 'wait') {
                  waiting = true;
                  cancelled = false;
                  waitingRequest = id;
                  releasePath = message.params.input.arguments.releasePath;
                }
                else if (command === 'fail-after-cancel') {
                  waiting = true;
                  cancelled = false;
                  waitingRequest = id;
                  releasePath = message.params.input.arguments.releasePath;
                  failAfterCancel = true;
                }
                else if (command === 'null-after-cancel') {
                  waiting = true;
                  cancelled = false;
                  waitingRequest = id;
                  releasePath = message.params.input.arguments.releasePath;
                  nullAfterCancel = true;
                }
                else reply(id, {content:[{type:'text', text:'done'}], structuredContent:{command}});
              }
              else if (method === 'tool.status') reply(id, {content:[{type:'text', text:'status'}], structuredContent:{runCalls, waiting, cancelled}});
              else if (method === '$/cancelRequest') {
                waiting = false;
                cancelled = true;
                const release = setInterval(() => {
                  if (releasePath !== undefined && existsSync(releasePath)) {
                    clearInterval(release);
                    const settledRequest = waitingRequest;
                    waitingRequest = undefined;
                    if (failAfterCancel) {
                      failAfterCancel = false;
                      fail(settledRequest, {code:-32001, message:'missing', data:{kind:'fibra.tool.failure', schemaVersion:2, code:'NOT_FOUND'}});
                    } else if (nullAfterCancel) {
                      nullAfterCancel = false;
                      reply(settledRequest, null);
                    } else reply(settledRequest, {content:[{type:'text', text:'cancelled'}], structuredContent:null});
                  }
                }, 10);
              }
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

    private static ArtifactRecord artifact(Path artifactRoot, String id) {
        return ArtifactRecord.builder().id(new ArtifactId(id))
            .runtimeId(NodePluginRuntimeAdapter.RUNTIME_ID).version("1.0.0")
            .checksum("checksum").revision("revision").location(artifactRoot)
            .state(ArtifactState.INSTALLED).updatedAt(Instant.now()).build();
    }

    private static NodePluginRuntimeAdapter adapter(Path work) {
        return new NodePluginRuntimeAdapter(name -> "echo".equals(name) ? Optional.of(ECHO)
            : Optional.empty(), NodeRuntimeOptions.defaults(node(), work.resolve("sessions")));
    }

    private static Path nodePackage(Path root) throws Exception {
        var payload = root.resolve("payload");
        Files.createDirectories(payload);
        Files.writeString(root.resolve("plugin.properties"), """
            formatVersion=1
            runtime=node
            payload=payload
            """);
        return payload;
    }

    private static String manifest(String id) {
        return """
            id: %s
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
            """.formatted(id);
    }

    private static String disablingScript() {
        return """
            import readline from 'node:readline';
            const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              if (message.method === 'fibra.handshake') reply(message.id, {protocol:1});
              else if (message.method === 'fibra.ping') reply(message.id, {ok:true});
              else if (message.method === 'fibra.start') {
                process.stdout.write(JSON.stringify({jsonrpc:'2.0',method:'fibra.disable',params:{}}) + '\\n');
                reply(message.id, {ok:true});
              }
              else if (message.method === 'fibra.stop') reply(message.id, {ok:true});
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
