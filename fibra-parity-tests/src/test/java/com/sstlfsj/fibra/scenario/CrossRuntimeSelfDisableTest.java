package com.sstlfsj.fibra.scenario;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.artifact.PluginPackageRecord;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.bridge.ContributionCodec;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionKindRegistry;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.engine.ApplyDeployment;
import com.sstlfsj.fibra.engine.CandidatePhase;
import com.sstlfsj.fibra.engine.CurrentPhase;
import com.sstlfsj.fibra.engine.DeploymentTarget;
import com.sstlfsj.fibra.engine.DeploymentTargetStore;
import com.sstlfsj.fibra.engine.DurableTargetToken;
import com.sstlfsj.fibra.engine.ExecutionObservation;
import com.sstlfsj.fibra.engine.ExecutionUnitKey;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.FileDeploymentTargetStore;
import com.sstlfsj.fibra.engine.HostServiceRegistry;
import com.sstlfsj.fibra.engine.PluginSelection;
import com.sstlfsj.fibra.engine.PublishedView;
import com.sstlfsj.fibra.engine.TargetConvergence;
import com.sstlfsj.fibra.runtime.java.JavaRuntimeProvider;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeOptions;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeProvider;
import com.sstlfsj.fibra.value.LiteralValue;
import fixture.DisableJavaEntrypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

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
        ContributionKind.remote("control", String.class, String.class,
            String.class, new ControlCodec());

    @Test
    void selfDisablePersistsCompleteTargetAndPreservesUnrelatedRuntimeAndInflightCall(
        @TempDir Path work) throws Exception {
        var starts = new CopyOnWriteArrayList<JavaLifecycle>();
        var cleanups = new CopyOnWriteArrayList<JavaLifecycle>();
        var services = hostServices(starts, cleanups);
        var selfPidFile = work.resolve("self.pid");
        var stablePidFile = work.resolve("stable.pid");
        var holdEntered = work.resolve("hold-entered");
        var raw = rawTarget(selfPidFile, stablePidFile, holdEntered);
        var packageRoot = work.resolve("packages");
        var packages = new PluginPackageStore(packageRoot);
        var selections = List.of(
            selection(install(packages, javaPackage(work, "self-java",
                "fixture.DisableJavaEntrypoint"))),
            selection(install(packages, javaPackage(work, "stable-java",
                "fixture.DisableJavaEntrypoint$Stable"))),
            selection(install(packages, nodePackage(work, "self-node"))),
            selection(install(packages, nodePackage(work, "stable-node"))));
        var observations = new CopyOnWriteArrayList<SaveObservation>();
        var stateRoot = work.resolve("state");
        var store = new RecordingTargetStore(
            new FileDeploymentTargetStore(stateRoot));
        DeploymentTarget disabledTarget;

        try (var engine = engine(work, packages, services, store)) {
            engine.startAsync().block(TIMEOUT);
            var deployed = engine.submit(ApplyDeployment.builder(raw)
                .expectedRevision(0).selections(selections)
                .configContext(ConfigContextSnapshot.empty()).build())
                .block(TIMEOUT).view();
            assertEquals(raw.plugins().keySet(), currentUnits(deployed).keySet()
                .stream().map(ExecutionUnitKey::value)
                .collect(java.util.stream.Collectors.toSet()));
            currentUnits(deployed).values().forEach(unit ->
                assertEquals(ExecutionObservation.State.ACTIVE,
                    unit.aggregateState()));
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
            var initialTarget = target(store);
            store.afterSave = target -> observations.add(new SaveObservation(target,
                cleanups.size(), alive(selfPid), alive(stablePid)));

            try (var watcher = FileSystems.getDefault().newWatchService()) {
                work.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
                var stableControl = controlId(deployed, "stable-node");
                var held = engine.published().invoke(deployed.viewRevision(),
                    identity(deployed, stableControl), CONTROL,
                    stableControl, "hold").toFuture();
                try {
                    awaitFile(watcher, holdEntered);
                    invokeControl(engine, "self-java", "disable");
                    var javaDisabled = awaitDisabled(engine, "self-java");
                    assertTarget(initialTarget, raw.withEnabled("self-java", false),
                        store, javaDisabled);
                    assertEquals(1, observations.size());
                    assertEquals(0, observations.getFirst().javaCleanups());
                    assertTrue(observations.getFirst().selfNodeAlive());
                    assertTrue(observations.getFirst().stableNodeAlive());
                    assertEquals(target(store), observations.getFirst().target());
                    assertEquals(List.of(new JavaLifecycle("self-java", selfLoader)),
                        cleanups);
                    assertStable(deployed, javaDisabled, starts, cleanups,
                        stableLoader, stablePidFile, stablePid);
                    assertHealthy(javaDisabled);
                    assertFalse(held.isDone());

                    store.failNextSave();
                    var nodeDisableFailure = engine.published().views().filter(view ->
                        view.engine().candidate().map(candidate ->
                            candidate.phase() == CandidatePhase.FAILED).orElse(false)
                            && view.engineDiagnostics().failure().isPresent()
                            && view.engine().target().orElseThrow().desiredGraph()
                                .plugins().get("self-node").enabled()
                            && currentUnits(view).containsKey(
                                new ExecutionUnitKey("self-node")))
                        .next().toFuture();
                    invokeControl(engine, "self-node", "disable");
                    var failedNodeDisable = Mono.fromFuture(nodeDisableFailure)
                        .block(TIMEOUT);
                    assertEquals(1, observations.size());
                    assertEquals(raw.withEnabled("self-java", false),
                        target(store).desiredGraph());
                    assertTrue(alive(selfPid));
                    assertStable(deployed, failedNodeDisable, starts, cleanups,
                        stableLoader, stablePidFile, stablePid);
                    assertTrue(failedNodeDisable.engineDiagnostics().mutationGateOpen());
                    assertFalse(held.isDone());

                    invokeControl(engine, "self-node", "disable");
                    var bothDisabled = awaitDisabled(engine, "self-node");
                    var expectedRaw = raw.withEnabled("self-java", false)
                        .withEnabled("self-node", false);
                    assertTarget(initialTarget, expectedRaw, store, bothDisabled);
                    assertEquals(2, observations.size());
                    assertEquals(1, observations.get(1).javaCleanups());
                    assertTrue(observations.get(1).selfNodeAlive());
                    assertTrue(observations.get(1).stableNodeAlive());
                    assertEquals(target(store), observations.get(1).target());
                    assertFalse(alive(selfPid),
                        "停用完成时 self Node 必须已经退出");
                    assertFalse(currentUnits(bothDisabled).containsKey(
                        new ExecutionUnitKey("self-java")));
                    assertStable(deployed, bothDisabled, starts, cleanups,
                        stableLoader, stablePidFile, stablePid);
                    assertHealthy(bothDisabled);
                    assertFalse(held.isDone());

                    var current = engine.published().current();
                    var currentStableControl = controlId(current, "stable-node");
                    assertEquals("released", engine.published().invoke(
                        current.viewRevision(), identity(current, currentStableControl),
                        CONTROL, currentStableControl, "release").block(TIMEOUT));
                    assertEquals("held", held.get(TIMEOUT.toMillis(),
                        TimeUnit.MILLISECONDS));
                    disabledTarget = target(store);
                } finally {
                    held.cancel(true);
                }
            }
        }
        assertEquals(starts, cleanups);
        assertRecordedPidsStopped(selfPidFile, stablePidFile);

        var reopenedPackages = new PluginPackageStore(packageRoot);
        var reopenedStore = new FileDeploymentTargetStore(stateRoot);
        assertEquals(disabledTarget, reopenedStore.load().orElseThrow().target());
        try (var reopened = engine(work, reopenedPackages, services,
            reopenedStore)) {
            var restored = reopened.startAsync().block(TIMEOUT);
            assertEquals(disabledTarget,
                restored.engine().target().orElseThrow());
            assertEquals(Set.of(new ExecutionUnitKey("stable-java"),
                new ExecutionUnitKey("stable-node")),
                currentUnits(restored).keySet());
            currentUnits(restored).values().forEach(unit ->
                assertEquals(ExecutionObservation.State.ACTIVE,
                    unit.aggregateState()));
            assertHealthy(restored);
            assertEquals(1, starts.stream()
                .filter(event -> event.id().equals("self-java")).count());
            assertEquals(2, starts.stream()
                .filter(event -> event.id().equals("stable-java")).count());
            assertEquals(1, recordedPids(selfPidFile).size());
            var stablePids = recordedPids(stablePidFile);
            assertEquals(2, stablePids.size());
            assertNotEquals(stablePids.getFirst(), stablePids.getLast());
            assertTrue(alive(stablePids.getLast()));
            assertNotSame(loader(starts, "stable-java"), starts.getLast().loader());
            var stableControl = controlId(restored, "stable-node");
            assertEquals("released", reopened.published().invoke(
                restored.viewRevision(), identity(restored, stableControl),
                CONTROL, stableControl, "release").block(TIMEOUT));
        }
        assertEquals(starts, cleanups);
        assertRecordedPidsStopped(selfPidFile, stablePidFile);
    }

    private static FibraEngine engine(Path work, PluginPackageStore packages,
                                      HostServiceRegistry services,
                                      DeploymentTargetStore store) {
        return FibraEngine.builder(packages, store)
            .hostServices(services)
            .runtimeProvider(new JavaRuntimeProvider(List.of()))
            .runtimeProvider(new NodeRuntimeProvider(NodeRuntimeOptions.defaults(
                Path.of(System.getProperty("fibra.test.node", "node")),
                work.resolve("node-sessions"))))
            .contributionKinds(ContributionKindRegistry.of(CONTROL))
            .hostTerminationPort(ignored -> { }).build();
    }

    private static HostServiceRegistry hostServices(List<JavaLifecycle> starts,
                                                    List<JavaLifecycle> cleanups) {
        var services = new HostServiceRegistry();
        services.register(ServiceKey.of("disable-control-kind", ContributionKind.class),
            CONTROL);
        services.register(ServiceKey.of("disable-java-start", BiConsumer.class),
            (BiConsumer<String, ClassLoader>) (id, loader) ->
                starts.add(new JavaLifecycle(id, loader)));
        services.register(ServiceKey.of("disable-java-cleanup", BiConsumer.class),
            (BiConsumer<String, ClassLoader>) (id, loader) ->
                cleanups.add(new JavaLifecycle(id, loader)));
        return services;
    }

    private static ClassLoader loader(List<JavaLifecycle> events, String id) {
        return events.stream().filter(event -> event.id().equals(id))
            .findFirst().orElseThrow().loader();
    }

    private static void assertStable(PublishedView before, PublishedView after,
                                     List<JavaLifecycle> starts,
                                     List<JavaLifecycle> cleanups,
                                     ClassLoader stableLoader,
                                     Path stablePidFile, long stablePid)
        throws Exception {
        for (var id : List.of("stable-java", "stable-node")) {
            assertSameExecution(detail(before, id), detail(after, id));
            assertEquals(ExecutionObservation.State.ACTIVE,
                currentUnits(after).get(new ExecutionUnitKey(id))
                    .aggregateState());
        }
        assertEquals(2, starts.size());
        assertSame(stableLoader, loader(starts, "stable-java"));
        assertEquals(1, cleanups.size());
        assertTrue(cleanups.stream().noneMatch(event ->
            event.id().equals("stable-java")));
        assertEquals(List.of(stablePid), recordedPids(stablePidFile));
        assertTrue(alive(stablePid));
    }

    private static void assertSameExecution(ExecutionObservation.Detail before,
                                            ExecutionObservation.Detail after) {
        assertEquals(before.unitTargetRevision(), after.unitTargetRevision());
        assertEquals(before.runtimeInstanceId(), after.runtimeInstanceId());
        assertEquals(before.lifecycleOperationId(), after.lifecycleOperationId());
    }

    private static void assertTarget(DeploymentTarget initial,
                                     DesiredInputGraph expected,
                                     RecordingTargetStore store,
                                     PublishedView view) {
        var persisted = target(store);
        assertEquals(initial.selections(), persisted.selections());
        assertEquals(4, persisted.selections().size());
        assertEquals(expected, persisted.desiredGraph());
        assertEquals(expected, view.engine().target().orElseThrow().desiredGraph());
        assertEquals(persisted.targetRevision(),
            view.engine().target().orElseThrow().targetRevision());
    }

    private static void assertHealthy(PublishedView view) {
        assertEquals(TargetConvergence.SATISFIED, view.engine().targetConvergence());
        assertTrue(view.engineDiagnostics().mutationGateOpen());
    }

    private static void invokeControl(FibraEngine engine, String unit,
                                      String command) {
        var current = engine.published().current();
        var id = controlId(current, unit);
        assertEquals("requested", engine.published().invoke(
            current.viewRevision(), identity(current, id), CONTROL, id,
            command).block(TIMEOUT));
    }

    private static ContributionId controlId(PublishedView view, String unit) {
        return view.contributions().entries().stream()
            .filter(entry -> entry.kind().equals(CONTROL.name()))
            .map(entry -> entry.id())
            .filter(id -> id.providerInstanceId().equals(unit))
            .findFirst().orElseThrow();
    }

    private static long identity(PublishedView view, ContributionId id) {
        return view.contributions().entries().stream()
            .filter(entry -> entry.kind().equals(CONTROL.name())
                && entry.id().equals(id))
            .map(entry -> entry.registrationIdentity()).findFirst().orElseThrow();
    }

    private static PublishedView awaitDisabled(FibraEngine engine, String id) {
        return reactor.core.publisher.Flux.merge(engine.published().views(),
            Mono.fromSupplier(engine.published()::current)).filter(view ->
                !view.engine().target().orElseThrow().desiredGraph()
                    .plugins().get(id).enabled()
                    && !currentUnits(view).containsKey(new ExecutionUnitKey(id))
                    && !retirementUnits(view).containsKey(new ExecutionUnitKey(id))
                    && view.engine().current().map(current ->
                        current.phase() == CurrentPhase.SETTLED).orElse(false)
                    && view.engine().targetConvergence() == TargetConvergence.SATISFIED
                    && view.engineDiagnostics().mutationGateOpen())
            .next().block(TIMEOUT);
    }

    private static ExecutionObservation.Detail detail(PublishedView view,
                                                       String id) {
        return currentUnits(view).get(new ExecutionUnitKey(id))
            .executions().getFirst();
    }

    private static Map<ExecutionUnitKey, ExecutionObservation> currentUnits(PublishedView view) {
        return view.engine().current().map(current -> current.observations()).orElse(Map.of());
    }

    private static Map<ExecutionUnitKey, ExecutionObservation> retirementUnits(PublishedView view) {
        return view.engine().retirementBatch().map(batch -> batch.observations()).orElse(Map.of());
    }

    private static DesiredInputGraph rawTarget(Path selfPid, Path stablePid,
                                               Path holdEntered) {
        return new DesiredInputGraph(List.of(
            entry("self-java", "self-java").build(),
            entry("stable-java", "stable-java").build(),
            entry("self-node", "self-node")
                .config(nodeConfig(selfPid, holdEntered)).build(),
            entry("stable-node", "stable-node")
                .config(nodeConfig(stablePid, holdEntered)).build()));
    }

    private static DesiredInputEntry.Builder entry(String id, String pluginId) {
        return DesiredInputEntry.builder(id,
            new PluginDefinitionRef(pluginId, "main", pluginId));
    }

    private static LiteralValue nodeConfig(Path probe, Path holdEntered) {
        return LiteralValue.of(Map.of("probe", probe.toString(),
            "holdEntered", holdEntered.toString()));
    }

    private static PluginPackageRecord install(PluginPackageStore store,
                                                Path source) {
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
        Files.writeString(root.resolve("fibra-package.yaml"),
            packageManifest(id, "java", "main.jar"));
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/fibra/plugin.yaml"));
            output.write(("entrypoint: " + entrypoint + '\n')
                .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            for (var name : List.of("fixture/DisableJavaEntrypoint.class",
                "fixture/DisableJavaEntrypoint$Stable.class")) {
                output.putNextEntry(new JarEntry(name));
                try (var input = CrossRuntimeSelfDisableTest.class
                    .getResourceAsStream('/' + name)) {
                    if (input == null) throw new IllegalStateException(
                        "missing test fixture " + name);
                    output.write(input.readAllBytes());
                }
                output.closeEntry();
            }
        }
        return root;
    }

    private static Path nodePackage(Path work, String id) throws Exception {
        var root = Files.createDirectory(work.resolve(id));
        var payload = Files.createDirectory(root.resolve("payload"));
        Files.writeString(root.resolve("fibra-package.yaml"),
            packageManifest(id, "node", "payload"));
        Files.writeString(payload.resolve("fibra-plugin.yaml"), """
            protocol: 1
            definitionId: %s
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

    private static String packageManifest(String id, String runtime,
                                          String payload) {
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

    private static void awaitFile(WatchService watcher, Path expected)
        throws Exception {
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!Files.exists(expected)) {
            var remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new AssertionError(
                "等待在途调用进入超时");
            var key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
            if (key == null) throw new AssertionError(
                "等待在途调用进入超时");
            key.pollEvents();
            key.reset();
        }
    }

    private static List<Long> recordedPids(Path probe) throws Exception {
        return Files.exists(probe)
            ? Files.readAllLines(probe).stream().map(Long::parseLong).toList()
            : List.of();
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void assertRecordedPidsStopped(Path... probes)
        throws Exception {
        for (var probe : probes) {
            for (var pid : recordedPids(probe)) {
                assertFalse(alive(pid), "Node 进程仍在运行: " + pid);
            }
        }
    }

    private static DeploymentTarget target(RecordingTargetStore store) {
        return store.load().orElseThrow().target();
    }

    private record JavaLifecycle(String id, ClassLoader loader) { }
    private record SaveObservation(DeploymentTarget target, int javaCleanups,
                                   boolean selfNodeAlive,
                                   boolean stableNodeAlive) { }

    private static final class RecordingTargetStore
        implements DeploymentTargetStore {
        private final DeploymentTargetStore delegate;
        private final AtomicBoolean failNextSave = new AtomicBoolean();
        private Consumer<DeploymentTarget> afterSave = ignored -> { };

        private RecordingTargetStore(DeploymentTargetStore delegate) {
            this.delegate = delegate;
        }
        private void failNextSave() { failNextSave.set(true); }
        @Override public Optional<StoredTarget> load() { return delegate.load(); }
        @Override public DurableTargetToken save(long expectedRevision,
                                                  DeploymentTarget target) {
            if (failNextSave.compareAndSet(true, false)) {
                throw new IllegalStateException("injected save failure");
            }
            var token = delegate.save(expectedRevision, target);
            afterSave.accept(delegate.load().orElseThrow().target());
            return token;
        }
        @Override public void close() { delegate.close(); }
    }

    private static final class ControlCodec
        implements ContributionCodec<String, String, String> {
        @Override public int schemaVersion() { return 1; }
        @Override public String decodeDescriptor(LiteralValue descriptor) {
            return descriptor.toJava().toString();
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
}
