package com.sstlfsj.fibra.verification.host;

import com.sstlfsj.fibra.artifact.FacetDependency;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.PluginPackageRecord;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.bridge.ContributionKindRegistry;
import com.sstlfsj.fibra.bridge.ContributionSnapshotEntry;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.config.PublicationRequirement;
import com.sstlfsj.fibra.engine.ApplyDeployment;
import com.sstlfsj.fibra.engine.ExecutionObservation;
import com.sstlfsj.fibra.engine.ExecutionUnitKey;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.FileDeploymentTargetStore;
import com.sstlfsj.fibra.engine.HostTerminationRequest;
import com.sstlfsj.fibra.engine.PluginSelection;
import com.sstlfsj.fibra.engine.PublishedView;
import com.sstlfsj.fibra.engine.RemoteContributionInvoker;
import com.sstlfsj.fibra.engine.TargetConvergence;
import com.sstlfsj.fibra.runtime.java.JavaRuntimeProvider;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeOptions;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeProvider;
import com.sstlfsj.fibra.value.LiteralValue;
import com.sstlfsj.fibra.verification.external.ExternalFixtureRuntimeHarness;
import com.sstlfsj.fibra.verification.external.ExternalFixtureRuntimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import verification.host.fixture.DynamicJavaEntrypoint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeHostVerticalVerificationTest {
    private static final String JAVA_PLUGIN = "vertical-java";
    private static final String NODE_PLUGIN = "vertical-node";
    private static final String EXTERNAL_PLUGIN = "vertical-external";
    private static final String JAVA_ENTRY = "java-entry";
    private static final String NODE_ENTRY = "node-entry";
    private static final String EXTERNAL_A = "external-a";
    private static final String EXTERNAL_B = "external-b";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir Path work;

    @BeforeEach
    void resetSharedFixture() {
        HostVerificationContract.reset();
    }

    @Test
    void realHostPreservesSaveOrderingRemoteCallsOfflineReconcileAndLifecycleFences()
        throws Exception {
        var packageStore = new PluginPackageStore(work.resolve("packages"));
        var targetRoot = work.resolve("target");
        var targetStore = new FileDeploymentTargetStore(targetRoot);
        var durableTarget = targetRoot.resolve("target.json").toAbsolutePath();
        var javaMarker = work.resolve("java-started").toAbsolutePath();
        var nodeMarker = work.resolve("node-started");
        var javaPackage = install(packageStore,
            javaPackage(work.resolve("java-source")));
        var nodePackage = install(packageStore,
            nodePackage(work.resolve("node-source"), nodeMarker));
        var externalPackage = install(packageStore,
            externalPackage(work.resolve("external-source")));
        var external = new ExternalFixtureRuntimeHarness();
        var kinds = ContributionKindRegistry.of(
            HostVerificationContract.ECHO_KIND,
            ExternalFixtureRuntimeProvider.REMOTE_KIND);
        var terminationRequests =
            new java.util.concurrent.CopyOnWriteArrayList<HostTerminationRequest>();

        try (var engine = FibraEngine.builder(packageStore, targetStore)
            .runtimeProvider(new JavaRuntimeProvider(List.of()))
            .runtimeProvider(new NodeRuntimeProvider(nodeOptions(work)))
            .runtimeProvider(external.provider())
            .contributionKinds(kinds)
            .hostTerminationPort(terminationRequests::add)
            .lifecycleTimeout(TIMEOUT)
            .build()) {
            engine.startAsync().block(TIMEOUT);
            var controller = external.controller();
            var invoker = new RemoteContributionInvoker(kinds, engine.published());

            assertEquals(0, HostVerificationContract.javaStarts());
            assertFalse(Files.exists(javaMarker));
            assertFalse(Files.exists(nodeMarker));
            assertEquals(0, controller.snapshot().counters().activations());
            assertFalse(Files.exists(durableTarget));

            var initial = graph(durableTarget, javaMarker, "one", "two");
            var first = engine.submit(ApplyDeployment.builder(initial)
                .expectedRevision(0)
                .selections(selections(javaPackage, nodePackage, externalPackage))
                .configContext(ConfigContextSnapshot.empty()).build()).block(TIMEOUT).view();

            assertTrue(Files.isRegularFile(durableTarget),
                "every real runtime start must happen after the durable target save");
            assertEquals(1, HostVerificationContract.javaStarts());
            assertTrue(Files.isRegularFile(javaMarker));
            assertTrue(Files.isRegularFile(nodeMarker));
            assertUnit(first, JAVA_ENTRY, ExecutionObservation.State.ACTIVE, 1);
            assertUnit(first, NODE_ENTRY, ExecutionObservation.State.ACTIVE, 1);
            assertUnit(first, EXTERNAL_A, ExecutionObservation.State.PENDING, 1);
            assertUnit(first, EXTERNAL_B, ExecutionObservation.State.PENDING, 1);
            assertEquals(0, controller.snapshot().counters().activations());
            assertEquals("java-v1:request", invoke(invoker, engine.published().current(),
                HostVerificationContract.KIND_NAME, "java-echo", null, "request"));
            assertEquals("node-v1:request", invoke(invoker, engine.published().current(),
                HostVerificationContract.KIND_NAME, "node-echo", null, "request"));

            controller.goOnline();
            await(() -> active(engine.published().current(), EXTERNAL_A)
                && active(engine.published().current(), EXTERNAL_B)
                && contributionCount(engine.published().current()) == 4);
            var online = engine.published().current();
            assertEquals(JAVA_ENTRY, contribution(online, "java-echo", null)
                .id().providerInstanceId());
            assertEquals(NODE_ENTRY, contribution(online, "node-echo", null)
                .id().providerInstanceId());
            assertEquals(EXTERNAL_A, contribution(online, "echo",
                "{\"mode\":\"one\"}").id().providerInstanceId());
            assertEquals(EXTERNAL_B, contribution(online, "echo",
                "{\"mode\":\"two\"}").id().providerInstanceId());
            assertEquals("{\"mode\":\"one\"}:request", invoke(invoker, online,
                ExternalFixtureRuntimeProvider.REMOTE_KIND_NAME, "echo",
                "{\"mode\":\"one\"}", "request"));
            assertEquals("{\"mode\":\"two\"}:request", invoke(invoker,
                engine.published().current(),
                ExternalFixtureRuntimeProvider.REMOTE_KIND_NAME, "echo",
                "{\"mode\":\"two\"}", "request"));

            var disconnectedA = detail(online, EXTERNAL_A);
            var disconnectedB = detail(online, EXTERNAL_B);
            controller.goOffline();
            await(() -> pending(engine.published().current(), EXTERNAL_A)
                && pending(engine.published().current(), EXTERNAL_B)
                && contributionCount(engine.published().current()) == 2
                && engine.snapshot().retirementBatch().isEmpty());
            var offline = engine.published().current();
            assertNotEquals(disconnectedA.runtimeInstanceId(),
                detail(offline, EXTERNAL_A).runtimeInstanceId());
            assertNotEquals(disconnectedB.runtimeInstanceId(),
                detail(offline, EXTERNAL_B).runtimeInstanceId());
            assertEquals(2, controller.snapshot().counters().drains());
            assertEquals(2, controller.snapshot().counters().stops());

            var replacementA = detail(offline, EXTERNAL_A);
            var replacementB = detail(offline, EXTERNAL_B);
            controller.goOnline();
            await(() -> active(engine.published().current(), EXTERNAL_A)
                && active(engine.published().current(), EXTERNAL_B)
                && contributionCount(engine.published().current()) == 4);
            online = engine.published().current();
            assertSameRuntimeInstance(replacementA, detail(online, EXTERNAL_A));
            assertSameRuntimeInstance(replacementB, detail(online, EXTERNAL_B));

            var retainedJava = detail(online, JAVA_ENTRY);
            var retainedNode = detail(online, NODE_ENTRY);
            var retainedExternal = detail(online, EXTERNAL_B);
            var replacedExternal = detail(online, EXTERNAL_A);
            var second = engine.submit(ApplyDeployment.builder(
                    graph(durableTarget, javaMarker, "updated", "two"))
                .expectedRevision(1)
                .selections(selections(javaPackage, nodePackage, externalPackage))
                .configContext(ConfigContextSnapshot.empty()).build()).block(TIMEOUT).view();

            assertRetained(retainedJava, detail(second, JAVA_ENTRY));
            assertRetained(retainedNode, detail(second, NODE_ENTRY));
            assertRetained(retainedExternal, detail(second, EXTERNAL_B));
            assertEquals(2, detail(second, EXTERNAL_A).unitTargetRevision());
            assertNotEquals(replacedExternal.runtimeInstanceId(),
                detail(second, EXTERNAL_A).runtimeInstanceId());
            assertEquals("{\"mode\":\"updated\"}:request", invoke(invoker,
                engine.published().current(),
                ExternalFixtureRuntimeProvider.REMOTE_KIND_NAME, "echo",
                "{\"mode\":\"updated\"}", "request"));
            assertEquals(3, controller.snapshot().counters().drains());
            assertEquals(3, controller.snapshot().counters().stops());

            HostVerificationContract.armHeldInvocation();
            var beforeRemoval = engine.published().current();
            var held = invokeAsync(invoker, beforeRemoval,
                HostVerificationContract.KIND_NAME, "java-echo", null, "hold");
            assertTrue(HostVerificationContract.awaitHeldInvocation(
                TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            var removal = engine.submit(ApplyDeployment.builder(
                    new DesiredInputGraph(List.of()))
                .expectedRevision(2).selections(List.of())
                .configContext(ConfigContextSnapshot.empty()).build()).toFuture();

            await(() -> contributionCount(engine.published().current()) == 0);
            assertFalse(removal.isDone(),
                "drain must wait for the invocation lease after routes close synchronously");
            assertEquals(3, controller.snapshot().counters().stops(),
                "stop must not overtake the blocked dependency-ordered drain");

            HostVerificationContract.releaseHeldInvocation();
            assertEquals("java-v1:hold", string(held.get(
                TIMEOUT.toSeconds(), TimeUnit.SECONDS)));
            removal.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            var empty = engine.published().current();
            assertTrue(currentUnits(empty).isEmpty());
            assertEquals(TargetConvergence.SATISFIED, empty.engine().targetConvergence());
            assertEquals(0, contributionCount(empty));
            assertEquals(0, controller.snapshot().counters().resourceGenerations());
            assertEquals(0, controller.snapshot().counters().resourceLeases());
            assertEquals(5, controller.snapshot().counters().drains());
            assertEquals(5, controller.snapshot().counters().stops());
            assertTrue(terminationRequests.isEmpty());
        }
    }

    private static void assertRetained(ExecutionObservation.Detail before,
                                       ExecutionObservation.Detail after) {
        assertEquals(1, after.unitTargetRevision());
        assertEquals(before.runtimeInstanceId(), after.runtimeInstanceId());
        assertEquals(before.lifecycleOperationId(), after.lifecycleOperationId());
    }

    private static void assertSameRuntimeInstance(
        ExecutionObservation.Detail before, ExecutionObservation.Detail after) {
        assertEquals(before.unitTargetRevision(), after.unitTargetRevision());
        assertEquals(before.runtimeInstanceId(), after.runtimeInstanceId());
    }

    private static void assertUnit(PublishedView view, String entry,
                                   ExecutionObservation.State state,
                                   long revision) {
        var observation = currentUnits(view).get(new ExecutionUnitKey(entry));
        assertEquals(state, observation.aggregateState());
        assertEquals(revision, observation.executions().getFirst().unitTargetRevision());
    }

    private static boolean active(PublishedView view, String entry) {
        var observation = currentUnits(view).get(new ExecutionUnitKey(entry));
        return observation != null
            && observation.aggregateState() == ExecutionObservation.State.ACTIVE;
    }

    private static boolean pending(PublishedView view, String entry) {
        var observation = currentUnits(view).get(new ExecutionUnitKey(entry));
        return observation != null
            && observation.aggregateState() == ExecutionObservation.State.PENDING;
    }

    private static ExecutionObservation.Detail detail(PublishedView view,
                                                       String entry) {
        return currentUnits(view).get(new ExecutionUnitKey(entry))
            .executions().getFirst();
    }

    private static Map<ExecutionUnitKey, ExecutionObservation> currentUnits(PublishedView view) {
        return view.engine().current().map(current -> current.observations()).orElse(Map.of());
    }

    private static int contributionCount(PublishedView view) {
        return view.contributions().entries().size();
    }

    private static String invoke(RemoteContributionInvoker invoker,
                                 PublishedView view, String kind,
                                 String localName, String descriptor,
                                 String input) {
        return string(invoker.invoke(kind,
            contribution(view, localName, descriptor).id(), view.viewRevision(),
            contribution(view, localName, descriptor).registrationIdentity(),
            LiteralValue.of(input)).block(TIMEOUT));
    }

    private static CompletableFuture<LiteralValue> invokeAsync(
        RemoteContributionInvoker invoker, PublishedView view, String kind,
        String localName, String descriptor, String input) {
        var contribution = contribution(view, localName, descriptor);
        return invoker.invoke(kind, contribution.id(), view.viewRevision(),
            contribution.registrationIdentity(), LiteralValue.of(input)).toFuture();
    }

    private static ContributionSnapshotEntry contribution(PublishedView view,
                                                            String localName,
                                                            String descriptor) {
        var matches = view.contributions().entries().stream()
            .filter(entry -> entry.id().localName().equals(localName))
            .filter(entry -> descriptor == null
                || descriptor.equals(entry.descriptor())).toList();
        if (matches.size() != 1) {
            throw new AssertionError("expected exactly one contribution "
                + localName + '/' + descriptor + ", got " + matches);
        }
        return matches.getFirst();
    }

    private static String string(LiteralValue value) {
        if (!(value instanceof LiteralValue.StringValue text)) {
            throw new AssertionError("expected a string literal, got " + value);
        }
        return text.value();
    }

    private static DesiredInputGraph graph(Path durableTarget, Path javaMarker,
                                           String externalA, String externalB) {
        return new DesiredInputGraph(List.of(
            DesiredInputEntry.builder(JAVA_ENTRY,
                    new PluginDefinitionRef(JAVA_PLUGIN, "main", "java"))
                .config(LiteralValue.of(Map.of("prefix", "java-v1",
                    "startMarker", javaMarker.toString(),
                    "targetPath", durableTarget.toString()))).build(),
            DesiredInputEntry.builder(NODE_ENTRY,
                    new PluginDefinitionRef(NODE_PLUGIN, "main", "node"))
                .config(LiteralValue.of(Map.of("prefix", "node-v1",
                    "javaMarker", javaMarker.toString(),
                    "targetPath", durableTarget.toString()))).build(),
            externalEntry(EXTERNAL_A, externalA),
            externalEntry(EXTERNAL_B, externalB)));
    }

    private static DesiredInputEntry externalEntry(String id, String mode) {
        return DesiredInputEntry.builder(id, new PluginDefinitionRef(
                EXTERNAL_PLUGIN, "main",
                ExternalFixtureRuntimeProvider.DEFINITION_ID))
            .publicationRequirement(PublicationRequirement.PENDING_ALLOWED)
            .config(LiteralValue.of(Map.of("mode", mode))).build();
    }

    private static List<PluginSelection> selections(
        PluginPackageRecord javaPackage, PluginPackageRecord nodePackage,
        PluginPackageRecord externalPackage) {
        return List.of(selection(javaPackage), selection(nodePackage),
            selection(externalPackage));
    }

    private static PluginSelection selection(PluginPackageRecord value) {
        return new PluginSelection(value.pluginId(), value.packageRevision(), true);
    }

    private static PluginPackageRecord install(PluginPackageStore store,
                                               Path source) {
        try (var transaction = store.prepareInstall(source)) {
            return transaction.save();
        }
    }

    private static Path javaPackage(Path root) throws IOException {
        Files.createDirectories(root);
        var jar = root.resolve("plugin.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            jarEntry(output, "META-INF/fibra/plugin.yaml",
                "entrypoint: " + DynamicJavaEntrypoint.class.getName() + '\n');
            var className = DynamicJavaEntrypoint.class.getName()
                .replace('.', '/') + ".class";
            output.putNextEntry(new JarEntry(className));
            try (InputStream input = DynamicJavaEntrypoint.class
                .getResourceAsStream('/' + className)) {
                output.write(java.util.Objects.requireNonNull(input,
                    className).readAllBytes());
            }
            output.closeEntry();
        }
        Files.writeString(root.resolve("fibra-package.yaml"), packageManifest(
            JAVA_PLUGIN, "java", "plugin.jar", List.of()));
        return root;
    }

    private static Path nodePackage(Path root, Path marker) throws IOException {
        var payload = Files.createDirectories(root.resolve("node"));
        Files.writeString(payload.resolve("fibra-plugin.yaml"), """
            protocol: 1
            definitionId: node
            entrypoint: index.mjs
            contributions:
              - name: node-echo
                kind: fibra.verification.host.echo
                schemaVersion: 1
                method: host.echo
                descriptor: node-descriptor
            """);
        Files.writeString(payload.resolve("index.mjs"), nodeScript(marker));
        Files.writeString(root.resolve("fibra-package.yaml"), packageManifest(
            NODE_PLUGIN, "node", "node", List.of(
                new FacetDependency(new PluginId(JAVA_PLUGIN), new FacetId("main")))));
        return root;
    }

    private static Path externalPackage(Path root) throws IOException {
        var payload = Files.createDirectories(root.resolve("external"));
        Files.writeString(payload.resolve("fixture.txt"), "external fixture");
        Files.writeString(root.resolve("fibra-package.yaml"), packageManifest(
            EXTERNAL_PLUGIN, ExternalFixtureRuntimeProvider.RUNTIME_ID.value(),
            "external", List.of(new FacetDependency(
                new PluginId(NODE_PLUGIN), new FacetId("main")))));
        return root;
    }

    private static String packageManifest(String pluginId, String runtime,
                                          String payload,
                                          List<FacetDependency> dependencies) {
        var dependencyYaml = dependencies.isEmpty() ? "dependencies: []\n"
            : "dependencies:\n" + dependencies.stream().map(dependency ->
                "      - pluginId: " + dependency.pluginId().value() + "\n"
                    + "        facetId: " + dependency.facetId().value() + "\n")
                .collect(java.util.stream.Collectors.joining());
        return "format: 1\n"
            + "id: " + pluginId + "\n"
            + "version: 1.0.0\n"
            + "facets:\n"
            + "  - id: main\n"
            + "    role: host\n"
            + "    runtime: " + runtime + "\n"
            + "    target: host\n"
            + "    payload: " + payload + "\n"
            + "    " + dependencyYaml
            + "    capabilities: []\n";
    }

    private static String nodeScript(Path marker) {
        return """
            import fs from 'node:fs';
            import readline from 'node:readline';
            let config;
            const reply = (id, result) => process.stdout.write(
              JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              const {id, method, params} = message;
              if (method === 'fibra.handshake') reply(id, {protocol:1});
              else if (method === 'fibra.ping') reply(id, {ok:true});
              else if (method === 'fibra.start') {
                config = params.config;
                if (!fs.statSync(config.targetPath).isFile()) {
                  throw new Error('Node unit started before durable target was saved');
                }
                if (!fs.statSync(config.javaMarker).isFile()) {
                  throw new Error('Node unit started before its Java dependency');
                }
                fs.writeFileSync(%s, 'started');
                reply(id, {ok:true});
              }
              else if (method === 'host.echo') {
                reply(id, `${config.prefix}:${params.input}`);
              }
              else if (method === 'fibra.stop') reply(id, {ok:true});
            });
            """.formatted(quoted(marker.toAbsolutePath().toString()));
    }

    private static NodeRuntimeOptions nodeOptions(Path work) {
        return NodeRuntimeOptions.builder(
                Path.of(System.getProperty("fibra.test.node", "node")),
                work.resolve("node-sessions"))
            .handshakeTimeout(Duration.ofSeconds(3))
            .defaultRequestTimeout(Duration.ofSeconds(3))
            .terminateTimeout(Duration.ofSeconds(2)).build();
    }

    private static void jarEntry(JarOutputStream output, String name,
                                 String value) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\")
            .replace("\"", "\\\"") + '"';
    }

    private static void await(BooleanSupplier condition) throws Exception {
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition did not settle before timeout");
    }
}
