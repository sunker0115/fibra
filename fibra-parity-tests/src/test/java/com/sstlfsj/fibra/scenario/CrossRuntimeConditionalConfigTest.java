package com.sstlfsj.fibra.scenario;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.bridge.ContributionCodec;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.ConfigException;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.engine.ApplyDeployment;
import com.sstlfsj.fibra.engine.DeploymentArtifact;
import com.sstlfsj.fibra.engine.DeploymentManifest;
import com.sstlfsj.fibra.engine.EngineStateStore;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.HostServiceRegistry;
import com.sstlfsj.fibra.engine.PublishedView;
import com.sstlfsj.fibra.engine.ReplaceConfigContext;
import com.sstlfsj.fibra.engine.RuntimeResourceSnapshot;
import com.sstlfsj.fibra.runtime.java.JavaPluginRuntimeAdapter;
import com.sstlfsj.fibra.runtime.node.NodePluginRuntimeAdapter;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeOptions;
import com.sstlfsj.fibra.value.LiteralValue;
import fixture.ContextJavaEntrypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossRuntimeConditionalConfigTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ArtifactId CONDITIONAL_JAVA = new ArtifactId("conditional-java");
    private static final ArtifactId STABLE_JAVA = new ArtifactId("stable-java");
    private static final ArtifactId CONDITIONAL_NODE = new ArtifactId("conditional-node");
    private static final ArtifactId STABLE_NODE = new ArtifactId("stable-node");
    private static final ContributionKind<Descriptor, String, String> CONTROL =
        ContributionKind.remote("control", Descriptor.class, String.class, String.class,
            new ControlCodec());
    private static final ContributionId STABLE_CONTROL = new ContributionId("stable-node", "control");

    @Test
    void replaceConfigContextUpdatesOnlyAffectedJavaAndNodeEntries(@TempDir Path work)
        throws Exception {
        var dynamicLoaders = new ArrayList<ClassLoader>();
        var dynamicConfigs = new ArrayList<String>();
        var stableLoaders = new ArrayList<ClassLoader>();
        var stableJavaCleanups = new ArrayList<ClassLoader>();
        var services = hostServices(dynamicLoaders, dynamicConfigs, stableLoaders, stableJavaCleanups);
        var store = new RecordingStateStore();
        var stablePid = work.resolve("stable.pid");
        var conditionalPid = work.resolve("conditional.pid");
        var dynamicNodePid = work.resolve("dynamic-node.pid");
        var holdEntered = work.resolve("hold-entered");
        var javaDynamic = javaArtifact(work, CONDITIONAL_JAVA,
            "fixture.ContextJavaEntrypoint");
        var javaStable = javaArtifact(work, STABLE_JAVA,
            "fixture.ContextJavaEntrypoint$Stable");
        var nodeConditional = nodeArtifact(work, CONDITIONAL_NODE);
        var nodeStable = nodeArtifact(work, STABLE_NODE);
        var raw = rawTarget(stablePid, conditionalPid, dynamicNodePid, holdEntered);
        var initialContext = context("before", false, null,
            nodeConfig(dynamicNodePid, holdEntered, "before").toJava());
        var rejectedContext = ConfigContextSnapshot.of(Map.of("node", Map.of("enabled", true)));
        var acceptedContext = context("after", true,
            nodeConfig(conditionalPid, holdEntered, "after").toJava(),
            nodeConfig(dynamicNodePid, holdEntered, "after").toJava());
        var node = Path.of(System.getProperty("fibra.test.node", "node"));
        var stableJavaMounted = false;

        var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(new ArtifactStore(work.resolve("artifacts"))).stateStore(store)
            .hostServices(services).configContext(initialContext)
            .runtimeAdapter(new JavaPluginRuntimeAdapter())
            .runtimeAdapter(new NodePluginRuntimeAdapter(name -> "control".equals(name)
                ? Optional.of(CONTROL) : Optional.empty(),
                NodeRuntimeOptions.defaults(node, work.resolve("node-sessions"))))
            .build();
        try {
            var empty = engine.start().block(TIMEOUT);
            var deployed = engine.submit(ApplyDeployment.builder(raw)
                .expectedRevision(empty.viewRevision())
                .expectedDesiredRevision(empty.engine().desiredSource().revision())
                .artifacts(List.of(artifact(CONDITIONAL_JAVA, javaDynamic, JavaPluginRuntimeAdapter.RUNTIME_ID),
                    artifact(STABLE_JAVA, javaStable, JavaPluginRuntimeAdapter.RUNTIME_ID),
                    artifact(CONDITIONAL_NODE, nodeConditional, NodePluginRuntimeAdapter.RUNTIME_ID),
                    artifact(STABLE_NODE, nodeStable, NodePluginRuntimeAdapter.RUNTIME_ID)))
                .build()).block(TIMEOUT).view();

            var savesBefore = store.saves;
            var dynamicIdentity = deployed.engine().instances().get("conditional-java").identity();
            var stableJavaIdentity = deployed.engine().instances().get("stable-java").identity();
            var dynamicNodeIdentity = deployed.engine().instances().get("dynamic-node").identity();
            var stableNodeIdentity = deployed.engine().instances().get("stable-node").identity();
            var dynamicJavaResource = resource(deployed, JavaPluginRuntimeAdapter.RUNTIME_ID,
                CONDITIONAL_JAVA);
            var stableJavaResource = resource(deployed, JavaPluginRuntimeAdapter.RUNTIME_ID,
                STABLE_JAVA);
            var stableNodeResource = resource(deployed, NodePluginRuntimeAdapter.RUNTIME_ID,
                STABLE_NODE);
            var conditionalNodeResource = resource(deployed, NodePluginRuntimeAdapter.RUNTIME_ID,
                CONDITIONAL_NODE);
            var stableNodePid = Files.readString(stablePid);
            var initialDynamicNodeStarts = Files.readAllLines(dynamicNodePid);
            var initialDynamicNodePid = processId(initialDynamicNodeStarts.getFirst());
            stableJavaMounted = true;

            assertEquals(1, dynamicLoaders.size());
            assertEquals(List.of("before"), dynamicConfigs);
            assertEquals(1, stableLoaders.size());
            assertNotSame(ContextJavaEntrypoint.class.getClassLoader(), dynamicLoaders.getFirst());
            assertNotSame(ContextJavaEntrypoint.class.getClassLoader(), stableLoaders.getFirst());
            assertNotSame(dynamicLoaders.getFirst(), stableLoaders.getFirst());
            assertEquals(1, initialDynamicNodeStarts.size());
            assertTrue(initialDynamicNodeStarts.getFirst().endsWith(":before"));
            assertTrue(deployed.engineDiagnostics().targetSatisfied());
            assertTrue(deployed.engineDiagnostics().mutationGateOpen());
            assertFalse(deployed.engine().instances().containsKey("conditional-node"));

            assertThrows(ConfigException.class, () -> engine.submit(new ReplaceConfigContext(
                deployed.viewRevision(), initialContext.revision(), rejectedContext)).block(TIMEOUT));

            var lastGood = engine.published().current();
            assertEquals(deployed, lastGood);
            assertEquals(savesBefore, store.saves);
            assertEquals(initialContext.revision(), lastGood.engineDiagnostics().contextRevision());
            assertEquals(deployed.engine().desiredSource().revision(),
                lastGood.engine().desiredSource().revision());
            assertEquals(deployed.engineDiagnostics().targetRevision(),
                lastGood.engineDiagnostics().targetRevision());
            assertEquals(dynamicIdentity, lastGood.engine().instances().get("conditional-java").identity());
            assertEquals(stableJavaIdentity, lastGood.engine().instances().get("stable-java").identity());
            assertEquals(dynamicNodeIdentity, lastGood.engine().instances().get("dynamic-node").identity());
            assertEquals(stableNodeIdentity, lastGood.engine().instances().get("stable-node").identity());
            assertEquals(stableNodePid, Files.readString(stablePid));
            assertEquals(1, dynamicLoaders.size());
            assertEquals(List.of("before"), dynamicConfigs);
            assertEquals(1, stableLoaders.size());
            assertTrue(stableJavaCleanups.isEmpty());
            assertTrue(lastGood.engineDiagnostics().targetSatisfied());
            assertTrue(lastGood.engineDiagnostics().mutationGateOpen());

            try (var watcher = FileSystems.getDefault().newWatchService()) {
                work.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
                var held = engine.published().invoke(lastGood.viewRevision(), CONTROL,
                    STABLE_CONTROL, "hold").toFuture();
                try {
                    awaitFile(watcher, holdEntered);

                    var changed = engine.submit(new ReplaceConfigContext(lastGood.viewRevision(),
                        initialContext.revision(), acceptedContext)).block(TIMEOUT).view();

                    assertEquals(savesBefore, store.saves);
                    assertEquals(raw, changed.engine().desiredGraph());
                    assertEquals(deployed.engineDiagnostics().targetRevision(),
                        changed.engineDiagnostics().targetRevision());
                    assertEquals(acceptedContext.revision(), changed.engineDiagnostics().contextRevision());
                    assertEquals(deployed.engine().desiredSource().revision(),
                        changed.engine().desiredSource().revision());
                    assertFalse(lastGood.viewRevision().equals(changed.viewRevision()));
                    assertEquals(dynamicIdentity,
                        changed.engine().instances().get("conditional-java").identity());
                    assertEquals(stableJavaIdentity,
                        changed.engine().instances().get("stable-java").identity());
                    assertEquals(dynamicNodeIdentity,
                        changed.engine().instances().get("dynamic-node").identity());
                    assertEquals(stableNodeIdentity,
                        changed.engine().instances().get("stable-node").identity());
                    assertTrue(changed.engine().instances().containsKey("conditional-node"));
                    assertEquals(LiteralValue.of("after"),
                        changed.engine().instances().get("conditional-java").config());
                    assertEquals(2, dynamicLoaders.size());
                    assertSame(dynamicLoaders.getFirst(), dynamicLoaders.get(1));
                    assertEquals(List.of("before", "after"), dynamicConfigs);
                    assertEquals(1, stableLoaders.size());
                    assertTrue(stableJavaCleanups.isEmpty());
                    assertEquals(stableNodePid, Files.readString(stablePid));
                    assertEquals(1, Files.readAllLines(stablePid).size());
                    assertEquals(1, Files.readAllLines(conditionalPid).size());
                    assertTrue(Files.readString(conditionalPid).endsWith(":after\n"));
                    var dynamicNodeStarts = Files.readAllLines(dynamicNodePid);
                    assertEquals(2, dynamicNodeStarts.size());
                    assertTrue(dynamicNodeStarts.getFirst().endsWith(":before"));
                    assertTrue(dynamicNodeStarts.get(1).endsWith(":after"));
                    var updatedDynamicNodePid = processId(dynamicNodeStarts.get(1));
                    assertNotEquals(initialDynamicNodePid, updatedDynamicNodePid);
                    assertFalse(ProcessHandle.of(initialDynamicNodePid)
                        .map(ProcessHandle::isAlive).orElse(false));
                    assertTrue(ProcessHandle.of(updatedDynamicNodePid)
                        .map(ProcessHandle::isAlive).orElse(false));
                    assertSameResource(dynamicJavaResource,
                        resource(changed, JavaPluginRuntimeAdapter.RUNTIME_ID, CONDITIONAL_JAVA));
                    assertSameResource(stableJavaResource,
                        resource(changed, JavaPluginRuntimeAdapter.RUNTIME_ID, STABLE_JAVA));
                    assertSameResource(stableNodeResource,
                        resource(changed, NodePluginRuntimeAdapter.RUNTIME_ID, STABLE_NODE));
                    assertSameResource(conditionalNodeResource,
                        resource(changed, NodePluginRuntimeAdapter.RUNTIME_ID, CONDITIONAL_NODE));
                    assertTrue(changed.engineDiagnostics().targetSatisfied());
                    assertTrue(changed.engineDiagnostics().mutationGateOpen());
                    assertFalse(held.isDone());

                    assertEquals("released", engine.published().invoke(changed.viewRevision(), CONTROL,
                        STABLE_CONTROL, "release").block(TIMEOUT));
                    assertEquals("held", held.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                } finally {
                    held.cancel(true);
                }
            }
        } finally {
            try {
                engine.close();
            } finally {
                if (stableJavaMounted) {
                    assertEquals(1, stableJavaCleanups.size());
                    assertSame(stableLoaders.getFirst(), stableJavaCleanups.getFirst());
                    recordedPids(stablePid, conditionalPid, dynamicNodePid).forEach(pid ->
                        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                            "Node 进程仍在运行: " + pid));
                }
            }
        }
    }

    private static HostServiceRegistry hostServices(List<ClassLoader> dynamicLoaders,
                                                    List<String> dynamicConfigs,
                                                    List<ClassLoader> stableLoaders,
                                                    List<ClassLoader> stableJavaCleanups) {
        var services = new HostServiceRegistry();
        services.register(ServiceKey.of("conditional-java-config", BiConsumer.class),
            (BiConsumer<ClassLoader, String>) (loader, config) -> {
                dynamicLoaders.add(loader);
                dynamicConfigs.add(config);
            });
        services.register(ServiceKey.of("stable-java-loader", Consumer.class),
            (Consumer<ClassLoader>) stableLoaders::add);
        services.register(ServiceKey.of("stable-java-cleanup", Consumer.class),
            (Consumer<ClassLoader>) stableJavaCleanups::add);
        return services;
    }

    private static DesiredInputGraph rawTarget(Path stablePid, Path conditionalPid,
                                               Path dynamicNodePid, Path holdEntered) {
        return new DesiredInputGraph(List.of(
            DesiredInputEntry.builder("conditional-java", CONDITIONAL_JAVA.value())
                .config(reference("/java/value")).build(),
            DesiredInputEntry.builder("stable-java", STABLE_JAVA.value()).build(),
            DesiredInputEntry.builder("conditional-node", CONDITIONAL_NODE.value())
                .when(reference("/node/enabled"))
                .config(reference("/node/config")).build(),
            DesiredInputEntry.builder("dynamic-node", CONDITIONAL_NODE.value())
                .config(reference("/node/dynamicConfig")).build(),
            DesiredInputEntry.builder("stable-node", STABLE_NODE.value())
                .config(nodeConfig(stablePid, holdEntered, "stable")).build()));
    }

    private static ConfigContextSnapshot context(String javaValue, boolean nodeEnabled,
                                                 Object nodeConfig, Object dynamicNodeConfig) {
        var node = nodeConfig == null
            ? Map.of("enabled", nodeEnabled, "dynamicConfig", dynamicNodeConfig)
            : Map.of("enabled", nodeEnabled, "config", nodeConfig,
                "dynamicConfig", dynamicNodeConfig);
        return ConfigContextSnapshot.of(Map.of("java", Map.of("value", javaValue), "node", node));
    }

    private static LiteralValue reference(String pointer) {
        return LiteralValue.of(Map.of("$ref", pointer));
    }

    private static LiteralValue nodeConfig(Path probe, Path holdEntered, String value) {
        return LiteralValue.of(Map.of("probe", probe.toString(),
            "holdEntered", holdEntered.toString(), "value", value));
    }

    private static long processId(String probe) {
        return Long.parseLong(probe.substring(0, probe.indexOf(':')));
    }

    private static List<Long> recordedPids(Path... probes) throws Exception {
        var result = new ArrayList<Long>();
        for (var probe : probes) {
            if (!Files.exists(probe)) continue;
            Files.readAllLines(probe).forEach(line -> result.add(processId(line)));
        }
        return List.copyOf(result);
    }

    private static DeploymentArtifact artifact(ArtifactId id, Path source,
                                                com.sstlfsj.fibra.artifact.RuntimeId runtime) {
        return DeploymentArtifact.builder().artifactId(id).runtimeId(runtime)
            .version("1.0.0").source(source).build();
    }

    private static Path javaArtifact(Path work, ArtifactId id, String entrypoint) throws Exception {
        var jar = work.resolve(id.value() + ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/fibra/plugin.yaml"));
            output.write(("id: " + id.value() + "\nversion: 1.0.0\nentrypoint: " + entrypoint
                + "\nrequires: []\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            copyClass(output, "fixture/ContextJavaEntrypoint.class");
            copyClass(output, "fixture/ContextJavaEntrypoint$Stable.class");
        }
        return jar;
    }

    private static void copyClass(JarOutputStream output, String name) throws Exception {
        output.putNextEntry(new JarEntry(name));
        try (InputStream input = CrossRuntimeConditionalConfigTest.class
            .getResourceAsStream('/' + name)) {
            if (input == null) throw new IllegalStateException("missing test fixture " + name);
            output.write(input.readAllBytes());
        }
        output.closeEntry();
    }

    private static Path nodeArtifact(Path work, ArtifactId id) throws Exception {
        var root = Files.createDirectory(work.resolve(id.value()));
        Files.writeString(root.resolve("fibra-plugin.yaml"), """
            id: %s
            version: 1.0.0
            protocol: 1
            entrypoint: index.mjs
            contributions:
              - name: control
                kind: control
                schemaVersion: 1
                method: control
                descriptor: { title: Control }
            """.formatted(id.value()));
        Files.writeString(root.resolve("index.mjs"), sidecar());
        return root;
    }

    private static String sidecar() {
        return """
            import fs from 'node:fs';
            import readline from 'node:readline';
            let config;
            let held;
            const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              const {id, method} = message;
              if (method === 'fibra.handshake') reply(id, {protocol:1});
              else if (method === 'fibra.ping') reply(id, {ok:true});
              else if (method === 'fibra.start') {
                config = message.params.config;
                fs.appendFileSync(config.probe, process.pid + ':' + config.value + '\\n');
                reply(id, {ok:true});
              } else if (method === 'fibra.stop') reply(id, {ok:true});
              else if (method === 'control' && message.params.input === 'hold') {
                held = id;
                fs.writeFileSync(config.holdEntered, 'entered');
              } else if (method === 'control' && message.params.input === 'release') {
                if (held !== undefined) {
                  reply(held, 'held');
                  held = undefined;
                }
                reply(id, 'released');
              }
            });
            """;
    }

    private static void awaitFile(WatchService watcher, Path expected) throws Exception {
        while (true) {
            WatchKey key = watcher.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (key == null) throw new AssertionError("timed out waiting for " + expected.getFileName());
            var matched = key.pollEvents().stream().anyMatch(event ->
                expected.getFileName().equals(event.context()));
            key.reset();
            if (matched) return;
        }
    }

    private static RuntimeResourceSnapshot.Resource resource(PublishedView view,
                                                               com.sstlfsj.fibra.artifact.RuntimeId runtime,
                                                               ArtifactId artifact) {
        return view.engine().runtimes().get(runtime).resources().stream()
            .filter(resource -> resource.artifact().id().equals(artifact)).findFirst().orElseThrow();
    }

    private static void assertSameResource(RuntimeResourceSnapshot.Resource expected,
                                           RuntimeResourceSnapshot.Resource actual) {
        assertEquals(expected.identity(), actual.identity());
        assertEquals(RuntimeResourceSnapshot.State.ACTIVE, expected.state());
        assertEquals(RuntimeResourceSnapshot.State.ACTIVE, actual.state());
    }

    private record Descriptor(String title) {
    }

    private static final class ControlCodec implements ContributionCodec<Descriptor, String, String> {
        @Override public int schemaVersion() { return 1; }
        @Override public Descriptor decodeDescriptor(Object descriptor) {
            return new Descriptor(((Map<?, ?>) descriptor).get("title").toString());
        }
        @Override public Object encodeInput(String input) { return input; }
        @Override public String decodeInput(Object input) { return input.toString(); }
        @Override public Object encodeOutput(String output) { return output; }
        @Override public String decodeOutput(Object output) { return output.toString(); }
    }

    private static final class RecordingStateStore implements EngineStateStore {
        private DeploymentManifest target;
        private int saves;

        @Override public Optional<DeploymentManifest> load() { return Optional.ofNullable(target); }
        @Override public void save(DeploymentManifest manifest) { target = manifest; saves++; }
    }
}
