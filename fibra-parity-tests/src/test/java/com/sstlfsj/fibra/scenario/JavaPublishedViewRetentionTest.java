package com.sstlfsj.fibra.scenario;

import com.sstlfsj.fibra.artifact.PluginPackageRecord;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.engine.ApplyDeployment;
import com.sstlfsj.fibra.engine.DeploymentTarget;
import com.sstlfsj.fibra.engine.DeploymentTargetStore;
import com.sstlfsj.fibra.engine.EngineState;
import com.sstlfsj.fibra.engine.ExecutionObservation;
import com.sstlfsj.fibra.engine.ExecutionUnitKey;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.HostServiceRegistry;
import com.sstlfsj.fibra.engine.PluginSelection;
import com.sstlfsj.fibra.engine.PublishedView;
import com.sstlfsj.fibra.engine.TargetConvergence;
import com.sstlfsj.fibra.runtime.java.JavaRuntimeProvider;
import fixture.RetentionJavaEntrypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPublishedViewRetentionTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String PLUGIN = "retention";
    private static final int REPLACEMENT_ROUNDS = 50;

    @Test
    @Timeout(20)
    void liveEngineReleasesTheLastRemovedDescriptorLoader(@TempDir Path work)
        throws Exception {
        try (var fixture = fixture(work.resolve("removed"));
             var controlFixture = fixture(work.resolve("active"))) {
            var engine = fixture.engine();
            var control = controlFixture.engine();
            var active = new WeakReference<>(descriptorLoader(control.startAsync().block(TIMEOUT)));
            var retired = startAndRemove(engine);
            assertAll(() -> awaitCollected(List.of(retired)),
                () -> assertNotNull(active.get(), "active descriptor loader is the live control"),
                () -> assertTrue(engine.published().current().contributions().entries().isEmpty()),
                () -> assertEquals(EngineState.RUNNING, engine.published().current().engine().state()));
            Reference.reachabilityFence(engine);
            Reference.reachabilityFence(control);
        }
    }

    private static WeakReference<ClassLoader> startAndRemove(FibraEngine engine) {
        var retired = new WeakReference<>(descriptorLoader(engine.startAsync().block(TIMEOUT)));
        engine.submit(ApplyDeployment.builder(new DesiredInputGraph(List.of()))
            .expectedRevision(1).selections(List.of())
            .configContext(ConfigContextSnapshot.empty()).build()).block(TIMEOUT);
        return retired;
    }

    @Test
    @Timeout(20)
    void correctedStartupReleasesTheFailedDescriptorLoaderWhileEngineStaysLive(
        @TempDir Path work) throws Exception {
        var originalFailure = new IllegalStateException(
            "controlled plugin startup failure");
        var services = new HostServiceRegistry();
        services.register(RetentionJavaEntrypoint.STARTUP_FAILURE, originalFailure);
        var packages = new PluginPackageStore(work.resolve("packages"));
        var active = install(packages, pluginPackage(work, 0, PLUGIN,
            "fixture.RetentionJavaEntrypoint"));
        var failing = install(packages, pluginPackage(work, 0, "retention-failure",
            "fixture.RetentionJavaEntrypoint$Failing"));
        var graph = new DesiredInputGraph(List.of(entry(PLUGIN, PLUGIN),
            entry("failing", "retention-failure")));
        var targets = DeploymentTargetStore.inMemory();
        targets.save(0, DeploymentTarget.of(1,
            List.of(selection(active), selection(failing)), graph,
            ConfigContextSnapshot.empty()));

        try (var engine = FibraEngine.builder(packages, targets)
            .hostServices(services).runtimeProvider(new JavaRuntimeProvider(List.of()))
            .hostTerminationPort(ignored -> { }).build()) {
            var retired = correctStartup(engine, packages, work);
            awaitCollected(retired);
            Reference.reachabilityFence(engine);
        }
    }

    private static List<WeakReference<ClassLoader>> correctStartup(
        FibraEngine engine, PluginPackageStore packages, Path work)
        throws Exception {
        var failed = engine.startAsync().block(TIMEOUT);
        assertTrue(failed.engine().candidate().isEmpty());
        assertEquals(ExecutionObservation.State.FAILED,
            currentUnits(failed).get(new ExecutionUnitKey("failing"))
                .aggregateState());
        assertTrue(failed.engineDiagnostics().mutationGateOpen());
        assertEquals(TargetConvergence.UNSATISFIED,
            failed.engine().targetConvergence());
        var oldLoader = descriptorLoader(failed);
        var replacement = install(packages, pluginPackage(work, 1, PLUGIN,
            "fixture.RetentionJavaEntrypoint"));
        var corrected = engine.submit(ApplyDeployment.builder(
                new DesiredInputGraph(List.of(entry(PLUGIN, PLUGIN))))
            .expectedRevision(1).selections(List.of(selection(replacement)))
            .configContext(ConfigContextSnapshot.empty()).build()).block(TIMEOUT).view();
        assertEquals(TargetConvergence.SATISFIED, corrected.engine().targetConvergence());
        assertEquals(EngineState.RUNNING, corrected.engine().state());
        assertEquals(1, currentUnits(corrected).size());
        assertNotSame(oldLoader, descriptorLoader(corrected));
        return List.of(new WeakReference<>(oldLoader));
    }

    @Test
    @Timeout(60)
    void liveEngineDoesNotAccumulateDescriptorLoadersAcrossFiftyReplacements(
        @TempDir Path work) throws Exception {
        try (var fixture = fixture(work)) {
            var engine = fixture.engine();
            var retired = startAndReplace(fixture);
            var active = new WeakReference<>(descriptorLoader(engine.published().current()));
            assertAll(() -> awaitCollected(retired),
                () -> assertNotNull(active.get(), "active descriptor loader is the live control"));
            Reference.reachabilityFence(engine);
        }
    }

    @Test
    @Timeout(20)
    void partialReplacementReleasesStoppedUnitWhileItsOriginalGenerationStaysLive(
        @TempDir Path work) throws Exception {
        var packages = new PluginPackageStore(work.resolve("packages"));
        var replaceableV1 = install(packages, pluginPackage(work, 0, PLUGIN,
            "fixture.RetentionJavaEntrypoint"));
        var replaceableV2 = install(packages, pluginPackage(work, 1, PLUGIN,
            "fixture.RetentionJavaEntrypoint"));
        var peer = install(packages, pluginPackage(work, 0, "retention-peer",
            "fixture.RetentionJavaEntrypoint$Peer"));
        var graph = new DesiredInputGraph(List.of(
            entry("replaceable", PLUGIN), entry("retained", "retention-peer")));
        var targets = DeploymentTargetStore.inMemory();
        targets.save(0, DeploymentTarget.of(1,
            List.of(selection(replaceableV1), selection(peer)), graph,
            ConfigContextSnapshot.empty()));
        try (var engine = FibraEngine.builder(packages, targets)
            .runtimeProvider(new JavaRuntimeProvider(List.of()))
            .hostTerminationPort(ignored -> { }).build()) {
            var scenario = new FutureTask<>(() -> startAndPartiallyReplace(
                engine, graph, replaceableV2, peer));
            Thread.ofPlatform().name("fibra-retention-fixture").start(scenario);
            var references = scenario.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertAll(() -> awaitCollected(List.of(references.retiredDescriptor())),
                () -> assertNotNull(references.retainedLoader().get(),
                    "retained unit keeps its own loader while the old generation stays live"));
            Reference.reachabilityFence(engine);
        }
    }

    private static RetentionReferences startAndPartiallyReplace(
        FibraEngine engine, DesiredInputGraph graph,
        PluginPackageRecord replaceableV2, PluginPackageRecord peer) {
        var started = engine.startAsync().block(TIMEOUT);
        var retiredDescriptor = descriptor(started, "replaceable");
        var retiredLoader = descriptorLoader(started, "replaceable");
        var retainedLoader = descriptorLoader(started, "retained");
        assertNotSame(retiredLoader, retainedLoader);
        var retainedInstance = detail(started, "retained").runtimeInstanceId();
        var replaced = engine.submit(ApplyDeployment.builder(graph)
            .expectedRevision(1)
            .selections(List.of(selection(replaceableV2), selection(peer)))
            .configContext(ConfigContextSnapshot.empty()).build()).block(TIMEOUT).view();
        assertNotSame(retiredLoader, descriptorLoader(replaced, "replaceable"));
        assertSame(retainedLoader, descriptorLoader(replaced, "retained"));
        assertEquals(retainedInstance,
            detail(replaced, "retained").runtimeInstanceId());
        return new RetentionReferences(new WeakReference<>(retiredDescriptor),
            new WeakReference<>(retainedLoader));
    }

    @Test
    @Timeout(15)
    void callerRetainingAnOldPublishedViewKeepsItsPluginDefinedDescriptorType(
        @TempDir Path work) throws Exception {
        try (var fixture = fixture(work)) {
            var engine = fixture.engine();
            var retained = engine.startAsync().block(TIMEOUT);
            var old = new WeakReference<>(descriptorLoader(retained));
            replace(engine, fixture.revisions().get(1), 1);
            for (var attempt = 0; attempt < 8; attempt++) {
                System.gc();
                Thread.sleep(25);
            }
            assertNotNull(old.get(), "caller-owned old view must keep its descriptor type");
            assertSame(old.get(), descriptorLoader(retained));
            assertNotSame(old.get(), descriptorLoader(engine.published().current()));
            Reference.reachabilityFence(retained);
        }
    }

    private static List<WeakReference<ClassLoader>> startAndReplace(TestEngine fixture)
        throws Exception {
        var engine = fixture.engine();
        var retired = new ArrayList<WeakReference<ClassLoader>>();
        var started = engine.startAsync().block(TIMEOUT);
        retired.add(new WeakReference<>(descriptorLoader(started)));
        var before = detail(started);
        for (var round = 1; round <= REPLACEMENT_ROUNDS; round++) {
            replace(engine, fixture.revisions().get(round), round);
            var current = engine.published().current();
            assertNotEquals(before.runtimeInstanceId(), detail(current).runtimeInstanceId());
            before = detail(current);
            if (round < REPLACEMENT_ROUNDS) {
                retired.add(new WeakReference<>(descriptorLoader(current)));
            }
        }
        return retired;
    }

    private static ClassLoader descriptorLoader(PublishedView view) {
        var descriptor = view.contributions().entries().getFirst().descriptor();
        assertEquals("fixture.RetentionJavaEntrypoint$Descriptor",
            descriptor.getClass().getName());
        var loader = descriptor.getClass().getClassLoader();
        assertNotSame(RetentionJavaEntrypoint.class.getClassLoader(), loader);
        return loader;
    }

    private static ClassLoader descriptorLoader(PublishedView view,
                                                 String provider) {
        return descriptor(view, provider).getClass().getClassLoader();
    }

    private static Object descriptor(PublishedView view, String provider) {
        var matches = view.contributions().entries().stream()
            .filter(entry -> entry.id().providerInstanceId().equals(provider))
            .toList();
        assertEquals(1, matches.size());
        var descriptor = matches.getFirst().descriptor();
        var loader = descriptor.getClass().getClassLoader();
        assertNotSame(RetentionJavaEntrypoint.class.getClassLoader(), loader);
        return descriptor;
    }

    private static ExecutionObservation.Detail detail(PublishedView view) {
        return currentUnits(view).get(new ExecutionUnitKey(PLUGIN))
            .executions().getFirst();
    }


    private static ExecutionObservation.Detail detail(PublishedView view,
                                                       String entry) {
        return currentUnits(view).get(new ExecutionUnitKey(entry))
            .executions().getFirst();
    }

    private static java.util.Map<ExecutionUnitKey, ExecutionObservation> currentUnits(
        PublishedView view) {
        return view.engine().current().map(current -> current.observations())
            .orElse(java.util.Map.of());
    }

    private static TestEngine fixture(Path work) throws Exception {
        var packages = new PluginPackageStore(work.resolve("packages"));
        var revisions = new ArrayList<PluginPackageRecord>();
        for (var round = 0; round <= REPLACEMENT_ROUNDS; round++) {
            revisions.add(install(packages, pluginPackage(work, round, PLUGIN,
                "fixture.RetentionJavaEntrypoint")));
        }
        var graph = new DesiredInputGraph(List.of(entry(PLUGIN, PLUGIN)));
        var targets = DeploymentTargetStore.inMemory();
        targets.save(0, DeploymentTarget.of(1, List.of(selection(revisions.getFirst())), graph,
            ConfigContextSnapshot.empty()));
        var engine = FibraEngine.builder(packages, targets)
            .runtimeProvider(new JavaRuntimeProvider(List.of()))
            .hostTerminationPort(ignored -> { }).build();
        return new TestEngine(engine, List.copyOf(revisions));
    }

    private static void replace(FibraEngine engine, PluginPackageRecord next,
                                int round) {
        engine.submit(ApplyDeployment.builder(new DesiredInputGraph(List.of(entry(PLUGIN, PLUGIN))))
            .expectedRevision(round).selections(List.of(selection(next)))
            .configContext(ConfigContextSnapshot.empty()).build()).block(TIMEOUT);
    }

    private static DesiredInputEntry entry(String id, String pluginId) {
        return DesiredInputEntry.builder(id,
            new PluginDefinitionRef(pluginId, "main", pluginId)).build();
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

    private static Path pluginPackage(Path work, int round, String pluginId,
                                      String entrypoint) throws Exception {
        var version = "1.0." + round;
        var root = Files.createDirectories(work.resolve(pluginId + "-source-" + round));
        var jar = root.resolve("plugin.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/fibra/plugin.yaml"));
            output.write(("entrypoint: " + entrypoint + "\n")
                .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            for (var name : List.of("fixture/RetentionJavaEntrypoint.class",
                "fixture/RetentionJavaEntrypoint$Descriptor.class",
                "fixture/RetentionJavaEntrypoint$Failing.class",
                "fixture/RetentionJavaEntrypoint$Peer.class")) {
                output.putNextEntry(new JarEntry(name));
                try (InputStream input = RetentionJavaEntrypoint.class
                    .getResourceAsStream('/' + name)) {
                    assertNotNull(input);
                    output.write(input.readAllBytes());
                }
                output.closeEntry();
            }
        }
        Files.writeString(root.resolve("fibra-package.yaml"), """
            format: 1
            id: %s
            version: %s
            facets:
              - id: main
                role: host
                runtime: java
                target: host
                payload: plugin.jar
                dependencies: []
                capabilities: []
            """.formatted(pluginId, version));
        return root;
    }

    private static void awaitCollected(List<? extends WeakReference<?>> references)
        throws InterruptedException {
        for (var attempt = 0; attempt < 60; attempt++) {
            System.gc();
            if (references.stream().allMatch(reference -> reference.refersTo(null))) return;
            Thread.sleep(25);
        }
        assertEquals(0, references.stream().filter(reference -> !reference.refersTo(null)).count(),
            () -> "live Engine retains retired fixture references at indexes "
                + java.util.stream.IntStream.range(0, references.size())
                    .filter(index -> !references.get(index).refersTo(null)).boxed().toList());
    }

    private record TestEngine(FibraEngine engine,
                              List<PluginPackageRecord> revisions)
        implements AutoCloseable {
        @Override public void close() { engine.close(); }
    }

    private record RetentionReferences(WeakReference<Object> retiredDescriptor,
                                       WeakReference<ClassLoader> retainedLoader) { }

}
