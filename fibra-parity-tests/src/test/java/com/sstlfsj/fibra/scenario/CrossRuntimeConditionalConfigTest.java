package com.sstlfsj.fibra.scenario;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.artifact.PluginPackageRecord;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.bridge.ContributionCodec;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionKindRegistry;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.ConfigException;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.engine.ApplyDeployment;
import com.sstlfsj.fibra.engine.DeploymentTarget;
import com.sstlfsj.fibra.engine.DeploymentTargetStore;
import com.sstlfsj.fibra.engine.DurableTargetToken;
import com.sstlfsj.fibra.engine.EngineChangeException;
import com.sstlfsj.fibra.engine.ExecutionObservation;
import com.sstlfsj.fibra.engine.ExecutionUnitKey;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.HostServiceRegistry;
import com.sstlfsj.fibra.engine.PluginSelection;
import com.sstlfsj.fibra.engine.PublishedView;
import com.sstlfsj.fibra.engine.TargetConvergence;
import com.sstlfsj.fibra.runtime.java.JavaRuntimeProvider;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeOptions;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeProvider;
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
    private static final String CONDITIONAL_JAVA = "conditional-java";
    private static final String STABLE_JAVA = "stable-java";
    private static final String CONDITIONAL_NODE = "conditional-node";
    private static final String DYNAMIC_NODE = "dynamic-node";
    private static final String STABLE_NODE = "stable-node";
    private static final ContributionKind<Descriptor, String, String> CONTROL =
        ContributionKind.remote("control", Descriptor.class, String.class, String.class,
            new ControlCodec());

    @Test
    void replacingTheTargetContextUpdatesOnlyAffectedJavaAndNodeEntries(@TempDir Path work)
        throws Exception {
        var dynamicLoaders = new ArrayList<ClassLoader>();
        var dynamicConfigs = new ArrayList<String>();
        var stableLoaders = new ArrayList<ClassLoader>();
        var stableJavaCleanups = new ArrayList<ClassLoader>();
        var services = hostServices(dynamicLoaders, dynamicConfigs, stableLoaders,
            stableJavaCleanups);
        var targetStore = new RecordingTargetStore();
        var packages = new PluginPackageStore(work.resolve("packages"));
        var installed = List.of(
            install(packages, javaPackage(work, CONDITIONAL_JAVA,
                "fixture.ContextJavaEntrypoint")),
            install(packages, javaPackage(work, STABLE_JAVA,
                "fixture.ContextJavaEntrypoint$Stable")),
            install(packages, nodePackage(work, CONDITIONAL_NODE)),
            install(packages, nodePackage(work, STABLE_NODE)));
        var selections = installed.stream().map(CrossRuntimeConditionalConfigTest::selection)
            .toList();
        var stablePid = work.resolve("stable.pid");
        var conditionalPid = work.resolve("conditional.pid");
        var dynamicNodePid = work.resolve("dynamic-node.pid");
        var holdEntered = work.resolve("hold-entered");
        var raw = rawTarget(stablePid, conditionalPid, dynamicNodePid, holdEntered);
        var initialContext = context("before", false, null,
            nodeConfig(dynamicNodePid, holdEntered, "before").toJava());
        var rejectedContext = ConfigContextSnapshot.of((LiteralValue.ObjectValue)
            LiteralValue.of(Map.of("node", Map.of("enabled", true))));
        var acceptedContext = context("after", true,
            nodeConfig(conditionalPid, holdEntered, "after").toJava(),
            nodeConfig(dynamicNodePid, holdEntered, "after").toJava());
        var node = Path.of(System.getProperty("fibra.test.node", "node"));
        var stableJavaMounted = false;

        var engine = FibraEngine.builder(packages, targetStore)
            .hostServices(services)
            .runtimeProvider(new JavaRuntimeProvider(List.of()))
            .runtimeProvider(new NodeRuntimeProvider(NodeRuntimeOptions.defaults(
                node, work.resolve("node-sessions"))))
            .contributionKinds(ContributionKindRegistry.of(CONTROL))
            .hostTerminationPort(ignored -> { }).build();
        try {
            engine.startAsync().block(TIMEOUT);
            var deployed = engine.submit(ApplyDeployment.builder(raw)
                .expectedRevision(0).selections(selections)
                .configContext(initialContext).build()).block(TIMEOUT).view();

            var savesBefore = targetStore.saves;
            var dynamicJava = detail(deployed, CONDITIONAL_JAVA);
            var stableJava = detail(deployed, STABLE_JAVA);
            var dynamicNode = detail(deployed, DYNAMIC_NODE);
            var stableNode = detail(deployed, STABLE_NODE);
            var stableNodePid = Files.readString(stablePid);
            var initialDynamicNodeStarts = Files.readAllLines(dynamicNodePid);
            var initialDynamicNodePid = processId(initialDynamicNodeStarts.getFirst());
            stableJavaMounted = true;

            assertEquals(1, dynamicLoaders.size());
            assertEquals(List.of("before"), dynamicConfigs);
            assertEquals(1, stableLoaders.size());
            assertNotSame(ContextJavaEntrypoint.class.getClassLoader(),
                dynamicLoaders.getFirst());
            assertNotSame(ContextJavaEntrypoint.class.getClassLoader(),
                stableLoaders.getFirst());
            assertNotSame(dynamicLoaders.getFirst(), stableLoaders.getFirst());
            assertEquals(1, initialDynamicNodeStarts.size());
            assertTrue(initialDynamicNodeStarts.getFirst().endsWith(":before"));
            assertEquals(TargetConvergence.SATISFIED, deployed.engine().targetConvergence());
            assertFalse(currentUnits(deployed).containsKey(
                new ExecutionUnitKey(CONDITIONAL_NODE)));

            var rejected = assertThrows(EngineChangeException.class, () -> engine.submit(
                ApplyDeployment.builder(raw).expectedRevision(1)
                    .selections(selections).configContext(rejectedContext).build())
                .block(TIMEOUT));
            assertTrue(rejected.getCause() instanceof ConfigException);

            var lastGood = engine.published().current();
            assertEquals(savesBefore, targetStore.saves);
            assertEquals(1, lastGood.engine().target().orElseThrow().targetRevision());
            assertSameExecution(dynamicJava, detail(lastGood, CONDITIONAL_JAVA));
            assertSameExecution(stableJava, detail(lastGood, STABLE_JAVA));
            assertSameExecution(dynamicNode, detail(lastGood, DYNAMIC_NODE));
            assertSameExecution(stableNode, detail(lastGood, STABLE_NODE));
            assertEquals(stableNodePid, Files.readString(stablePid));
            assertEquals(1, dynamicLoaders.size());
            assertEquals(List.of("before"), dynamicConfigs);
            assertEquals(1, stableLoaders.size());
            assertTrue(stableJavaCleanups.isEmpty());

            try (var watcher = FileSystems.getDefault().newWatchService()) {
                work.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
                var stableControl = controlId(lastGood, STABLE_NODE);
                var held = engine.published().invoke(lastGood.viewRevision(),
                    identity(lastGood, CONTROL, stableControl), CONTROL,
                    stableControl, "hold").toFuture();
                try {
                    awaitFile(watcher, holdEntered);
                    var changed = engine.submit(ApplyDeployment.builder(raw)
                        .expectedRevision(1).selections(selections)
                        .configContext(acceptedContext).build()).block(TIMEOUT).view();

                    assertEquals(savesBefore + 1, targetStore.saves);
                    assertEquals(raw, changed.engine().target().orElseThrow().desiredGraph());
                    assertEquals(2, changed.engine().target().orElseThrow().targetRevision());
                    assertNotEquals(dynamicJava.runtimeInstanceId(),
                        detail(changed, CONDITIONAL_JAVA).runtimeInstanceId());
                    assertSameExecution(stableJava, detail(changed, STABLE_JAVA));
                    assertNotEquals(dynamicNode.runtimeInstanceId(),
                        detail(changed, DYNAMIC_NODE).runtimeInstanceId());
                    assertSameExecution(stableNode, detail(changed, STABLE_NODE));
                    assertEquals(ExecutionObservation.State.ACTIVE,
                        currentUnits(changed).get(new ExecutionUnitKey(CONDITIONAL_NODE))
                            .aggregateState());
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
                    assertEquals(TargetConvergence.SATISFIED,
                        changed.engine().targetConvergence());
                    assertTrue(changed.engineDiagnostics().mutationGateOpen());
                    assertFalse(held.isDone());

                    var currentControl = controlId(changed, STABLE_NODE);
                    assertEquals("released", engine.published().invoke(
                        changed.viewRevision(), identity(changed, CONTROL, currentControl),
                        CONTROL, currentControl, "release").block(TIMEOUT));
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
                        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive)
                            .orElse(false), "Node 进程仍在运行: " + pid));
                }
            }
        }
    }

    private static HostServiceRegistry hostServices(List<ClassLoader> dynamicLoaders,
                                                     List<String> dynamicConfigs,
                                                     List<ClassLoader> stableLoaders,
                                                     List<ClassLoader> stableCleanups) {
        var services = new HostServiceRegistry();
        services.register(ServiceKey.of("conditional-java-config", BiConsumer.class),
            (BiConsumer<ClassLoader, String>) (loader, config) -> {
                dynamicLoaders.add(loader);
                dynamicConfigs.add(config);
            });
        services.register(ServiceKey.of("stable-java-loader", Consumer.class),
            (Consumer<ClassLoader>) stableLoaders::add);
        services.register(ServiceKey.of("stable-java-cleanup", Consumer.class),
            (Consumer<ClassLoader>) stableCleanups::add);
        return services;
    }

    private static DesiredInputGraph rawTarget(Path stablePid, Path conditionalPid,
                                               Path dynamicNodePid, Path holdEntered) {
        return new DesiredInputGraph(List.of(
            entry(CONDITIONAL_JAVA, CONDITIONAL_JAVA)
                .config(reference("/java/value")).build(),
            entry(STABLE_JAVA, STABLE_JAVA).build(),
            entry(CONDITIONAL_NODE, CONDITIONAL_NODE)
                .when(reference("/node/enabled"))
                .config(reference("/node/config")).build(),
            entry(DYNAMIC_NODE, CONDITIONAL_NODE)
                .config(reference("/node/dynamicConfig")).build(),
            entry(STABLE_NODE, STABLE_NODE)
                .config(nodeConfig(stablePid, holdEntered, "stable")).build()));
    }

    private static DesiredInputEntry.Builder entry(String id, String pluginId) {
        return DesiredInputEntry.builder(id,
            new PluginDefinitionRef(pluginId, "main", pluginId));
    }

    private static ConfigContextSnapshot context(String javaValue, boolean nodeEnabled,
                                                 Object nodeConfig,
                                                 Object dynamicNodeConfig) {
        var node = nodeConfig == null
            ? Map.of("enabled", nodeEnabled, "dynamicConfig", dynamicNodeConfig)
            : Map.of("enabled", nodeEnabled, "config", nodeConfig,
                "dynamicConfig", dynamicNodeConfig);
        return ConfigContextSnapshot.of((LiteralValue.ObjectValue) LiteralValue.of(
            Map.of("java", Map.of("value", javaValue), "node", node)));
    }

    private static LiteralValue reference(String pointer) {
        return LiteralValue.of(Map.of("$ref", pointer));
    }

    private static LiteralValue nodeConfig(Path probe, Path holdEntered, String value) {
        return LiteralValue.of(Map.of("probe", probe.toString(),
            "holdEntered", holdEntered.toString(), "value", value));
    }

    private static PluginPackageRecord install(PluginPackageStore store, Path source) {
        try (var transaction = store.prepareInstall(source)) {
            return transaction.save();
        }
    }

    private static PluginSelection selection(PluginPackageRecord value) {
        return new PluginSelection(value.pluginId(), value.packageRevision(), true);
    }

    private static Path javaPackage(Path work, String id, String entrypoint)
        throws Exception {
        var root = Files.createDirectory(work.resolve(id + "-package"));
        var jar = root.resolve("main.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            jarEntry(output, "META-INF/fibra/plugin.yaml",
                "entrypoint: " + entrypoint + '\n');
            copyClass(output, "fixture/ContextJavaEntrypoint.class");
            copyClass(output, "fixture/ContextJavaEntrypoint$Stable.class");
        }
        Files.writeString(root.resolve("fibra-package.yaml"), packageManifest(
            id, "java", "main.jar"));
        return root;
    }

    private static void copyClass(JarOutputStream output, String name) throws Exception {
        output.putNextEntry(new JarEntry(name));
        try (InputStream input = CrossRuntimeConditionalConfigTest.class
            .getResourceAsStream('/' + name)) {
            if (input == null) throw new IllegalStateException(
                "missing test fixture " + name);
            output.write(input.readAllBytes());
        }
        output.closeEntry();
    }

    private static Path nodePackage(Path work, String id) throws Exception {
        var root = Files.createDirectory(work.resolve(id));
        var payload = Files.createDirectory(root.resolve("payload"));
        Files.writeString(root.resolve("fibra-package.yaml"), packageManifest(
            id, "node", "payload"));
        Files.writeString(payload.resolve("fibra-plugin.yaml"), """
            protocol: 1
            definitionId: %s
            entrypoint: index.mjs
            contributions:
              - name: control
                kind: control
                schemaVersion: 1
                method: control
                descriptor: { title: Control }
            """.formatted(id));
        Files.writeString(payload.resolve("index.mjs"), sidecar());
        return root;
    }

    private static String packageManifest(String id, String runtime, String payload) {
        return """
            format: 1
            id: %s
            version: 1.0.0
            facets:
              - id: main
                role: host
                runtime: %s
                target: host
                payload: %s
                dependencies: []
                capabilities: []
            """.formatted(id, runtime, payload);
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

    private static void jarEntry(JarOutputStream output, String name, String value)
        throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static void awaitFile(WatchService watcher, Path expected)
        throws Exception {
        while (true) {
            WatchKey key = watcher.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (key == null) throw new AssertionError(
                "timed out waiting for " + expected.getFileName());
            var matched = key.pollEvents().stream().anyMatch(event ->
                expected.getFileName().equals(event.context()));
            key.reset();
            if (matched) return;
        }
    }

    private static ExecutionObservation.Detail detail(PublishedView view, String id) {
        return currentUnits(view).get(new ExecutionUnitKey(id))
            .executions().getFirst();
    }

    private static Map<ExecutionUnitKey, ExecutionObservation> currentUnits(PublishedView view) {
        return view.engine().current().map(current -> current.observations()).orElse(Map.of());
    }

    private static void assertSameExecution(ExecutionObservation.Detail expected,
                                            ExecutionObservation.Detail actual) {
        assertEquals(expected.unitTargetRevision(), actual.unitTargetRevision());
        assertEquals(expected.runtimeInstanceId(), actual.runtimeInstanceId());
        assertEquals(expected.lifecycleOperationId(), actual.lifecycleOperationId());
    }

    private static ContributionId controlId(PublishedView view, String unit) {
        return view.contributions().entries().stream()
            .filter(entry -> entry.kind().equals(CONTROL.name()))
            .map(entry -> entry.id())
            .filter(id -> id.providerInstanceId().equals(unit))
            .findFirst().orElseThrow();
    }

    private static long identity(PublishedView view, ContributionKind<?, ?, ?> kind,
                                 ContributionId id) {
        return view.contributions().entries().stream()
            .filter(entry -> entry.kind().equals(kind.name()) && entry.id().equals(id))
            .map(entry -> entry.registrationIdentity()).findFirst().orElseThrow();
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

    private record Descriptor(String title) { }

    private static final class ControlCodec
        implements ContributionCodec<Descriptor, String, String> {
        @Override public int schemaVersion() { return 1; }
        @Override public Descriptor decodeDescriptor(LiteralValue descriptor) {
            return new Descriptor(((Map<?, ?>) descriptor.toJava()).get("title").toString());
        }
        @Override public LiteralValue encodeInput(String input) {
            return LiteralValue.of(input);
        }
        @Override public String decodeInput(LiteralValue input) {
            return input.toJava().toString();
        }
        @Override public LiteralValue encodeOutput(String output) {
            return LiteralValue.of(output);
        }
        @Override public String decodeOutput(LiteralValue output) {
            return output.toJava().toString();
        }
    }

    private static final class RecordingTargetStore
        implements DeploymentTargetStore {
        private final DeploymentTargetStore delegate = DeploymentTargetStore.inMemory();
        private int saves;

        @Override public Optional<StoredTarget> load() { return delegate.load(); }
        @Override public DurableTargetToken save(long expectedRevision,
                                                  DeploymentTarget target) {
            var token = delegate.save(expectedRevision, target);
            saves++;
            return token;
        }
        @Override public void close() { delegate.close(); }
    }
}
