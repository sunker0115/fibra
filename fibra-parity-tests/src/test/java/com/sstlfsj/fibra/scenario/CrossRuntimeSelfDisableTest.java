package com.sstlfsj.fibra.scenario;

import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.bridge.ContributionCodec;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.engine.ApplyDeployment;
import com.sstlfsj.fibra.engine.DeploymentArtifact;
import com.sstlfsj.fibra.engine.DeploymentManifest;
import com.sstlfsj.fibra.engine.EngineStateStore;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.FileEngineStateStore;
import com.sstlfsj.fibra.engine.HostServiceRegistry;
import com.sstlfsj.fibra.engine.PublishedView;
import com.sstlfsj.fibra.engine.RuntimeResourceSnapshot;
import com.sstlfsj.fibra.runtime.java.JavaPluginRuntimeAdapter;
import com.sstlfsj.fibra.runtime.node.NodePluginRuntimeAdapter;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeOptions;
import com.sstlfsj.fibra.value.LiteralValue;
import fixture.DisableJavaEntrypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossRuntimeSelfDisableTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ContributionKind<String, String, String> CONTROL =
        ContributionKind.remote("control", String.class, String.class, String.class, new ControlCodec());
    private static final ContributionId STABLE_CONTROL = new ContributionId("stable-node", "control");

    @Test
    void selfDisablePersistsCompleteTargetAndPreservesUnrelatedRuntimeAndInflightCall(@TempDir Path work)
        throws Exception {
        var starts = new CopyOnWriteArrayList<JavaLifecycle>();
        var cleanups = new CopyOnWriteArrayList<JavaLifecycle>();
        var services = hostServices(starts, cleanups);
        var selfPidFile = work.resolve("self.pid");
        var stablePidFile = work.resolve("stable.pid");
        var holdEntered = work.resolve("hold-entered");
        var raw = rawTarget(selfPidFile, stablePidFile, holdEntered);
        var artifacts = List.of(
            artifact("self-java", javaArtifact(work, "self-java", "fixture.DisableJavaEntrypoint"),
                JavaPluginRuntimeAdapter.RUNTIME_ID),
            artifact("stable-java", javaArtifact(work, "stable-java", "fixture.DisableJavaEntrypoint$Stable"),
                JavaPluginRuntimeAdapter.RUNTIME_ID),
            artifact("self-node", nodeArtifact(work, "self-node"), NodePluginRuntimeAdapter.RUNTIME_ID),
            artifact("stable-node", nodeArtifact(work, "stable-node"), NodePluginRuntimeAdapter.RUNTIME_ID));
        var observations = new CopyOnWriteArrayList<SaveObservation>();
        var stateRoot = work.resolve("state");
        var store = new RecordingStateStore(new FileEngineStateStore(stateRoot));
        DeploymentManifest disabledTarget;
        try {
            try (var engine = engine(work, services, store)) {
                var empty = engine.start().block(TIMEOUT);
                var deployed = engine.submit(ApplyDeployment.builder(raw)
                    .expectedRevision(empty.viewRevision())
                    .expectedDesiredRevision(empty.engine().desiredSource().revision())
                    .artifacts(artifacts).build()).block(TIMEOUT).view();
                assertEquals(raw.plugins().keySet(), deployed.engine().instances().keySet());
                deployed.engine().instances().values().forEach(instance ->
                    assertEquals(PluginInstanceState.ACTIVE, instance.state()));
                assertHealthy(deployed);
                var selfPid = recordedPids(selfPidFile).getFirst();
                var stablePid = recordedPids(stablePidFile).getFirst();
                assertTrue(alive(selfPid));
                assertTrue(alive(stablePid));
                assertEquals(2, starts.size());
                var selfLoader = loader(starts, "self-java");
                var stableLoader = loader(starts, "stable-java");
                assertNotSame(DisableJavaEntrypoint.class.getClassLoader(), selfLoader);
                assertNotSame(DisableJavaEntrypoint.class.getClassLoader(), stableLoader);
                assertNotSame(selfLoader, stableLoader);
                var initialTarget = store.load().orElseThrow();
                store.afterSave = target -> observations.add(new SaveObservation(target,
                    cleanups.size(), alive(selfPid), alive(stablePid)));

                try (var watcher = FileSystems.getDefault().newWatchService()) {
                    work.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                    var held = engine.published().invoke(deployed.viewRevision(), identity(deployed, CONTROL,
                        STABLE_CONTROL), CONTROL,
                        STABLE_CONTROL, "hold").toFuture();
                    try {
                        awaitFile(watcher, holdEntered);
                        var current = engine.published().current();
                        assertEquals("requested", engine.published().invoke(current.viewRevision(),
                            identity(current, CONTROL, new ContributionId("self-java", "control")), CONTROL,
                            new ContributionId("self-java", "control"), "disable").block(TIMEOUT));
                        var javaDisabled = awaitDisabled(engine, "self-java");
                        assertTarget(initialTarget, raw.withEnabled("self-java", false), store, javaDisabled);
                        assertEquals(1, observations.size());
                        assertEquals(0, observations.getFirst().javaCleanups());
                        assertTrue(observations.getFirst().selfNodeAlive());
                        assertTrue(observations.getFirst().stableNodeAlive());
                        assertEquals(store.load().orElseThrow(), observations.getFirst().target());
                        assertEquals(List.of(new JavaLifecycle("self-java", selfLoader)), cleanups);
                        assertStable(deployed, javaDisabled, starts, cleanups, stableLoader,
                            stablePidFile, stablePid);
                        assertHealthy(javaDisabled);
                        assertFalse(held.isDone());

                        store.failNextSave();
                        current = engine.published().current();
                        assertEquals("requested", engine.published().invoke(current.viewRevision(),
                            identity(current, CONTROL, new ContributionId("self-node", "control")), CONTROL,
                            new ContributionId("self-node", "control"), "disable").block(TIMEOUT));
                        var failedNodeDisable = engine.published().views().filter(view ->
                            view.engineDiagnostics().failure() != null
                                && view.engine().desiredGraph().plugins().get("self-node").enabled()
                                && view.engine().instances().containsKey("self-node"))
                            .next().block(TIMEOUT);
                        assertEquals(1, observations.size());
                        assertEquals(raw.withEnabled("self-java", false), store.load().orElseThrow().desiredGraph());
                        assertTrue(alive(selfPid));
                        assertStable(deployed, failedNodeDisable, starts, cleanups, stableLoader,
                            stablePidFile, stablePid);
                        assertFalse(failedNodeDisable.engineDiagnostics().targetSatisfied());
                        assertTrue(failedNodeDisable.engineDiagnostics().mutationGateOpen());
                        assertFalse(held.isDone());

                        current = engine.published().current();
                        assertEquals("requested", engine.published().invoke(current.viewRevision(),
                            identity(current, CONTROL, new ContributionId("self-node", "control")), CONTROL,
                            new ContributionId("self-node", "control"), "disable").block(TIMEOUT));
                        var bothDisabled = awaitDisabled(engine, "self-node");
                        var expectedRaw = raw.withEnabled("self-java", false).withEnabled("self-node", false);
                        assertTarget(initialTarget, expectedRaw, store, bothDisabled);
                        assertEquals(2, observations.size());
                        assertEquals(1, observations.get(1).javaCleanups());
                        assertTrue(observations.get(1).selfNodeAlive());
                        assertTrue(observations.get(1).stableNodeAlive());
                        assertEquals(store.load().orElseThrow(), observations.get(1).target());
                        assertFalse(alive(selfPid), "停用完成时 self Node 必须已经退出");
                        assertFalse(bothDisabled.engine().instances().containsKey("self-java"));
                        assertStable(deployed, bothDisabled, starts, cleanups, stableLoader,
                            stablePidFile, stablePid);
                        assertHealthy(bothDisabled);
                        assertFalse(held.isDone());
                        current = engine.published().current();
                        assertEquals("released", engine.published().invoke(current.viewRevision(),
                            identity(current, CONTROL, STABLE_CONTROL), CONTROL, STABLE_CONTROL,
                            "release").block(TIMEOUT));
                        assertEquals("held", held.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                        disabledTarget = store.load().orElseThrow();
                    } finally {
                        held.cancel(true);
                    }
                }
            }
            assertEquals(starts, cleanups);
            assertRecordedPidsStopped(selfPidFile, stablePidFile);

            // 用新的文件仓库对象重开，验证恢复来自磁盘完整目标。
            try (var reopenedStore = new FileEngineStateStore(stateRoot);
                 var reopened = engine(work, services, reopenedStore)) {
                assertEquals(disabledTarget, reopenedStore.load().orElseThrow());
                var restored = reopened.start().block(TIMEOUT);
                assertEquals(disabledTarget.desiredGraph(), restored.engine().desiredGraph());
                assertEquals(disabledTarget.revision(), restored.engineDiagnostics().targetRevision());
                assertEquals(Set.of("stable-java", "stable-node"), restored.engine().instances().keySet());
                restored.engine().instances().values().forEach(instance ->
                    assertEquals(PluginInstanceState.ACTIVE, instance.state()));
                assertHealthy(restored);
                assertEquals(1, starts.stream().filter(event -> event.id().equals("self-java")).count());
                assertEquals(2, starts.stream().filter(event -> event.id().equals("stable-java")).count());
                assertEquals(1, recordedPids(selfPidFile).size());
                var stablePids = recordedPids(stablePidFile);
                assertEquals(2, stablePids.size());
                assertNotEquals(stablePids.getFirst(), stablePids.getLast());
                assertTrue(alive(stablePids.getLast()));
                assertNotSame(loader(starts, "stable-java"), starts.getLast().loader());
                assertEquals("released", reopened.published().invoke(restored.viewRevision(), identity(restored, CONTROL,
                    STABLE_CONTROL), CONTROL,
                    STABLE_CONTROL, "release").block(TIMEOUT));
            }
            assertEquals(starts, cleanups);
        } finally {
            store.close();
            assertRecordedPidsStopped(selfPidFile, stablePidFile);
        }
    }

    private static FibraEngine engine(Path work, HostServiceRegistry services, EngineStateStore store) {
        return FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(new ArtifactStore(work.resolve("artifacts"))).stateStore(store)
            .hostServices(services).runtimeAdapter(new JavaPluginRuntimeAdapter())
            .runtimeAdapter(new NodePluginRuntimeAdapter(name -> "control".equals(name)
                ? Optional.of(CONTROL) : Optional.empty(), NodeRuntimeOptions.defaults(
                    Path.of(System.getProperty("fibra.test.node", "node")), work.resolve("node-sessions"))))
            .build();
    }

    private static HostServiceRegistry hostServices(List<JavaLifecycle> starts, List<JavaLifecycle> cleanups) {
        var services = new HostServiceRegistry();
        services.register(ServiceKey.of("disable-control-kind", ContributionKind.class), CONTROL);
        services.register(ServiceKey.of("disable-java-start", BiConsumer.class),
            (BiConsumer<String, ClassLoader>) (id, loader) -> starts.add(new JavaLifecycle(id, loader)));
        services.register(ServiceKey.of("disable-java-cleanup", BiConsumer.class),
            (BiConsumer<String, ClassLoader>) (id, loader) -> cleanups.add(new JavaLifecycle(id, loader)));
        return services;
    }

    private static ClassLoader loader(List<JavaLifecycle> events, String id) {
        return events.stream().filter(event -> event.id().equals(id)).findFirst().orElseThrow().loader();
    }

    private static void assertStable(PublishedView before, PublishedView after,
                                     List<JavaLifecycle> starts, List<JavaLifecycle> cleanups,
                                     ClassLoader stableLoader, Path stablePidFile, long stablePid) throws Exception {
        for (var id : List.of("stable-java", "stable-node")) {
            assertEquals(before.engine().instances().get(id).identity(),
                after.engine().instances().get(id).identity());
            assertEquals(PluginInstanceState.ACTIVE, after.engine().instances().get(id).state());
        }
        for (var runtime : List.of(JavaPluginRuntimeAdapter.RUNTIME_ID, NodePluginRuntimeAdapter.RUNTIME_ID)) {
            var id = runtime.equals(JavaPluginRuntimeAdapter.RUNTIME_ID) ? "stable-java" : "stable-node";
            var previous = resource(before, runtime, id);
            var current = resource(after, runtime, id);
            assertEquals(previous.identity(), current.identity());
            assertEquals(RuntimeResourceSnapshot.State.ACTIVE, current.state());
        }
        assertEquals(2, starts.size());
        assertSame(stableLoader, loader(starts, "stable-java"));
        assertEquals(1, cleanups.size());
        assertTrue(cleanups.stream().noneMatch(event -> event.id().equals("stable-java")));
        assertEquals(List.of(stablePid), recordedPids(stablePidFile));
        assertTrue(alive(stablePid));
    }

    private static RuntimeResourceSnapshot.Resource resource(PublishedView view, RuntimeId runtime, String id) {
        return view.engine().runtimes().get(runtime).resources().stream()
            .filter(resource -> resource.artifact().id().equals(new ArtifactId(id))).findFirst().orElseThrow();
    }

    private static void assertTarget(DeploymentManifest initial, DesiredInputGraph expected,
                                     RecordingStateStore store, PublishedView view) {
        var persisted = store.load().orElseThrow();
        assertEquals(initial.artifacts(), persisted.artifacts());
        assertEquals(4, persisted.artifacts().size());
        assertEquals(expected, persisted.desiredGraph());
        assertEquals(expected, view.engine().desiredGraph());
        assertEquals(persisted.revision(), view.engineDiagnostics().targetRevision());
    }

    private static void assertHealthy(PublishedView view) {
        assertTrue(view.engineDiagnostics().targetSatisfied());
        assertTrue(view.engineDiagnostics().mutationGateOpen());
    }

    private static long identity(PublishedView view, ContributionKind<?, ?, ?> kind, ContributionId id) {
        return view.contributions().entries().stream()
            .filter(entry -> entry.kind().equals(kind.name()) && entry.id().equals(id))
            .map(entry -> entry.registrationIdentity()).findFirst().orElseThrow();
    }

    private static PublishedView awaitDisabled(FibraEngine engine, String id) {
        return engine.published().views().filter(view ->
            !view.engine().desiredGraph().plugins().get(id).enabled()
                && !view.engine().instances().containsKey(id)
                && view.engineDiagnostics().targetSatisfied()
                && view.engineDiagnostics().mutationGateOpen()).next().block(TIMEOUT);
    }

    private static DesiredInputGraph rawTarget(Path selfPid, Path stablePid, Path holdEntered) {
        return new DesiredInputGraph(List.of(
            DesiredInputEntry.builder("self-java", "self-java").build(),
            DesiredInputEntry.builder("stable-java", "stable-java").build(),
            DesiredInputEntry.builder("self-node", "self-node")
                .config(nodeConfig(selfPid, holdEntered)).build(),
            DesiredInputEntry.builder("stable-node", "stable-node")
                .config(nodeConfig(stablePid, holdEntered)).build()));
    }

    private static LiteralValue nodeConfig(Path probe, Path holdEntered) {
        return LiteralValue.of(Map.of("probe", probe.toString(), "holdEntered", holdEntered.toString()));
    }

    private static DeploymentArtifact artifact(String id, Path source, RuntimeId runtime) {
        return DeploymentArtifact.builder().artifactId(new ArtifactId(id)).runtimeId(runtime)
            .version("1.0.0").source(source).build();
    }

    private static Path javaArtifact(Path work, String id, String entrypoint) throws Exception {
        var root = Files.createDirectory(work.resolve(id + "-package"));
        var lib = Files.createDirectory(root.resolve("lib"));
        var jar = lib.resolve("main.jar");
        Files.writeString(root.resolve("plugin.properties"), """
            formatVersion=1
            runtime=java
            payload=lib/main.jar
            """);
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/fibra/plugin.yaml"));
            output.write(("id: " + id + "\nversion: 1.0.0\nentrypoint: " + entrypoint
                + "\nrequires: []\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            for (var name : List.of("fixture/DisableJavaEntrypoint.class",
                "fixture/DisableJavaEntrypoint$Stable.class")) {
                output.putNextEntry(new JarEntry(name));
                try (var input = CrossRuntimeSelfDisableTest.class.getResourceAsStream('/' + name)) {
                    if (input == null) throw new IllegalStateException("missing test fixture " + name);
                    output.write(input.readAllBytes());
                }
                output.closeEntry();
            }
        }
        return root;
    }

    private static Path nodeArtifact(Path work, String id) throws Exception {
        var root = Files.createDirectory(work.resolve(id));
        var payload = Files.createDirectory(root.resolve("payload"));
        Files.writeString(root.resolve("plugin.properties"), """
            formatVersion=1
            runtime=node
            payload=payload
            """);
        Files.writeString(payload.resolve("fibra-plugin.yaml"), """
            id: %s
            version: 1.0.0
            protocol: 1
            entrypoint: index.mjs
            contributions:
              - name: control
                kind: control
                schemaVersion: 1
                method: control
                descriptor: Control
            """.formatted(id));
        Files.writeString(payload.resolve("index.mjs"), """
            import fs from 'node:fs';
            import readline from 'node:readline';
            let config;
            let held;
            const send = message => process.stdout.write(JSON.stringify(message) + '\\n');
            const reply = (id, result) => send({jsonrpc:'2.0', id, result});
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              const {id, method} = message;
              if (method === 'fibra.handshake') reply(id, {protocol:1});
              else if (method === 'fibra.ping') reply(id, {ok:true});
              else if (method === 'fibra.start') {
                config = message.params.config;
                fs.appendFileSync(config.probe, process.pid + '\\n');
                reply(id, {ok:true});
              } else if (method === 'fibra.stop') reply(id, {ok:true});
              else if (method === 'control' && message.params.input === 'disable') {
                send({jsonrpc:'2.0', method:'fibra.disable', params:{}});
                reply(id, 'requested');
              } else if (method === 'control' && message.params.input === 'hold') {
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
            """);
        return root;
    }

    private static void awaitFile(WatchService watcher, Path expected) throws Exception {
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!Files.exists(expected)) {
            var remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new AssertionError("等待在途调用进入超时");
            var key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
            if (key == null) throw new AssertionError("等待在途调用进入超时");
            key.pollEvents();
            key.reset();
        }
    }

    private static List<Long> recordedPids(Path probe) throws Exception {
        return Files.exists(probe) ? Files.readAllLines(probe).stream().map(Long::parseLong).toList() : List.of();
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void assertRecordedPidsStopped(Path... probes) throws Exception {
        for (var probe : probes) {
            for (var pid : recordedPids(probe)) assertFalse(alive(pid), "Node 进程仍在运行: " + pid);
        }
    }

    private record JavaLifecycle(String id, ClassLoader loader) { }
    private record SaveObservation(DeploymentManifest target, int javaCleanups,
                                   boolean selfNodeAlive, boolean stableNodeAlive) { }

    private static final class RecordingStateStore implements EngineStateStore {
        private final FileEngineStateStore delegate;
        private final AtomicBoolean failNextSave = new AtomicBoolean();
        private Consumer<DeploymentManifest> afterSave = ignored -> { };

        private RecordingStateStore(FileEngineStateStore delegate) { this.delegate = delegate; }
        private void failNextSave() { failNextSave.set(true); }
        @Override public Optional<DeploymentManifest> load() { return delegate.load(); }
        @Override public void save(DeploymentManifest target) {
            if (failNextSave.compareAndSet(true, false)) {
                throw new IllegalStateException("injected save failure");
            }
            delegate.save(target);
            afterSave.accept(delegate.load().orElseThrow());
        }
        @Override public void close() { delegate.close(); }
    }

    private static final class ControlCodec implements ContributionCodec<String, String, String> {
        @Override public int schemaVersion() { return 1; }
        @Override public String decodeDescriptor(Object descriptor) { return descriptor.toString(); }
        @Override public Object encodeInput(String input) { return input; }
        @Override public String decodeInput(Object input) { return input.toString(); }
        @Override public Object encodeOutput(String output) { return output; }
        @Override public String decodeOutput(Object output) { return output.toString(); }
    }
}
