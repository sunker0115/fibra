package com.sstlfsj.fibra.scenario;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.engine.ApplyDeployment;
import com.sstlfsj.fibra.engine.DeploymentArtifact;
import com.sstlfsj.fibra.engine.EngineChangeException;
import com.sstlfsj.fibra.engine.EngineState;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.HostServiceRegistry;
import com.sstlfsj.fibra.engine.InstallArtifact;
import com.sstlfsj.fibra.engine.PublishedView;
import com.sstlfsj.fibra.engine.TargetSaveState;
import com.sstlfsj.fibra.runtime.java.JavaPluginRuntimeAdapter;
import fixture.RetentionJavaEntrypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPublishedViewRetentionTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ArtifactId ARTIFACT = new ArtifactId("retention");

    @Test
    @Timeout(20)
    void correctedStartupReleasesTheOriginalFailureAndDescriptorLoaderWhileEngineStaysLive(@TempDir Path work)
        throws Exception {
        var originalCause = new IllegalStateException("controlled plugin startup failure");
        var services = new HostServiceRegistry();
        services.register(RetentionJavaEntrypoint.STARTUP_FAILURE, originalCause);
        var initial = List.of(artifact(work, 0), artifact(work, 0, new ArtifactId("retention-failure"),
            "fixture.RetentionJavaEntrypoint$Failing"));
        var graph = new DesiredInputGraph(List.of(
            DesiredInputEntry.builder("retention", "retention").build(),
            DesiredInputEntry.builder("failing", "retention-failure").build()));
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .artifactStore(new ArtifactStore(work.resolve("artifacts"))).hostServices(services)
            .runtimeAdapter(new JavaPluginRuntimeAdapter()).initialArtifacts(() -> initial).build()) {
            var retired = failAndCorrectStartup(engine, work, originalCause);
            var active = new WeakReference<>(descriptorLoader(engine.published().current()));
            assertAll(() -> awaitCollected(retired),
                () -> assertNotNull(active.get(), "corrected descriptor loader is the live control"),
                () -> assertEquals(EngineState.RUNNING, engine.published().current().engine().state()));
            Reference.reachabilityFence(engine);
        }
    }

    private static List<WeakReference<?>> failAndCorrectStartup(FibraEngine engine, Path work,
                                                                RuntimeException originalCause) throws Exception {
        var failure = assertThrows(EngineChangeException.class, () -> engine.start().block(TIMEOUT));
        assertEquals(TargetSaveState.SAVED, failure.targetSaveState());
        assertEquals(1, failure.getCause().getSuppressed().length);
        assertSame(originalCause, failure.getCause().getSuppressed()[0],
            "first startup subscriber must receive the original plugin failure");
        assertEquals(EngineState.FAILED, failure.view().engine().state());
        assertTrue(failure.view().engineDiagnostics().mutationGateOpen());
        var oldLoader = descriptorLoader(failure.view());
        var current = engine.published().current();
        var corrected = engine.submit(ApplyDeployment.builder(new DesiredInputGraph(List.of(
                DesiredInputEntry.builder("retention", "retention").build())))
            .expectedRevision(current.viewRevision())
            .expectedDesiredRevision(current.engine().desiredSource().revision())
            .artifacts(List.of(artifact(work, 1))).build()).block(TIMEOUT).view();
        assertTrue(corrected.engineDiagnostics().targetSatisfied());
        assertEquals(EngineState.RUNNING, corrected.engine().state());
        assertEquals(1, corrected.engine().runtimes().get(JavaPluginRuntimeAdapter.RUNTIME_ID).resources().size());
        assertNotSame(oldLoader, descriptorLoader(corrected));
        return List.of(new WeakReference<>(failure), new WeakReference<>(oldLoader));
    }

    @Test
    @Timeout(20)
    void liveEngineDoesNotRetainTheFirstPublishedDescriptorLoaderAfterRepeatedReplacements(@TempDir Path work)
        throws Exception {
        try (var engine = engine(work)) {
            var retired = startAndReplace(engine, work);
            var active = new WeakReference<>(descriptorLoader(engine.published().current()));
            assertAll(() -> awaitCollected(retired),
                () -> assertNotNull(active.get(), "active descriptor loader is the live control"));
            Reference.reachabilityFence(engine);
        }
    }

    @Test
    @Timeout(15)
    void callerRetainingAnOldPublishedViewKeepsItsPluginDefinedDescriptorType(@TempDir Path work)
        throws Exception {
        try (var engine = engine(work)) {
            var retained = engine.start().block(TIMEOUT);
            var old = new WeakReference<>(descriptorLoader(retained));
            replace(engine, work, 1);
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

    private static List<WeakReference<ClassLoader>> startAndReplace(FibraEngine engine, Path work)
        throws Exception {
        var retired = new ArrayList<WeakReference<ClassLoader>>();
        retired.add(new WeakReference<>(descriptorLoader(engine.start().block(TIMEOUT))));
        for (var round = 1; round <= 3; round++) {
            var before = engine.published().current().engine().runtimes()
                .get(JavaPluginRuntimeAdapter.RUNTIME_ID).resources().getFirst().identity();
            replace(engine, work, round);
            var current = engine.published().current();
            assertNotEquals(before, current.engine().runtimes()
                .get(JavaPluginRuntimeAdapter.RUNTIME_ID).resources().getFirst().identity());
            if (round < 3) retired.add(new WeakReference<>(descriptorLoader(current)));
        }
        return retired;
    }

    private static ClassLoader descriptorLoader(PublishedView view) {
        var descriptor = view.contributions().entries().getFirst().descriptor();
        assertEquals("fixture.RetentionJavaEntrypoint$Descriptor", descriptor.getClass().getName());
        var loader = descriptor.getClass().getClassLoader();
        assertNotSame(RetentionJavaEntrypoint.class.getClassLoader(), loader);
        return loader;
    }

    private static FibraEngine engine(Path work) throws Exception {
        var initial = artifact(work, 0);
        var graph = new DesiredInputGraph(List.of(DesiredInputEntry.builder("retention", "retention").build()));
        return FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .artifactStore(new ArtifactStore(work.resolve("artifacts")))
            .runtimeAdapter(new JavaPluginRuntimeAdapter()).initialArtifacts(() -> List.of(initial)).build();
    }

    private static void replace(FibraEngine engine, Path work, int round) throws Exception {
        var next = artifact(work, round);
        engine.submit(InstallArtifact.builder().expectedRevision(engine.published().current().viewRevision())
            .artifactId(ARTIFACT).runtimeId(JavaPluginRuntimeAdapter.RUNTIME_ID)
            .version(next.version()).source(next.source()).build()).block(TIMEOUT);
    }

    private static DeploymentArtifact artifact(Path work, int round) throws Exception {
        return artifact(work, round, ARTIFACT, "fixture.RetentionJavaEntrypoint");
    }

    private static DeploymentArtifact artifact(Path work, int round, ArtifactId id, String entrypoint)
        throws Exception {
        var version = "1.0." + round;
        var root = Files.createDirectories(work.resolve(id.value() + "-source-" + round));
        Files.writeString(root.resolve("plugin.properties"),
            "formatVersion=1\nruntime=java\npayload=plugin.jar\n");
        try (var jar = new JarOutputStream(Files.newOutputStream(root.resolve("plugin.jar")))) {
            jar.putNextEntry(new JarEntry("META-INF/fibra/plugin.yaml"));
            jar.write(("id: " + id.value() + "\nversion: " + version
                + "\nentrypoint: " + entrypoint + "\nrequires: []\n")
                .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            for (var name : List.of("fixture/RetentionJavaEntrypoint.class",
                "fixture/RetentionJavaEntrypoint$Descriptor.class", "fixture/RetentionJavaEntrypoint$Failing.class")) {
                jar.putNextEntry(new JarEntry(name));
                try (var input = RetentionJavaEntrypoint.class.getResourceAsStream('/' + name)) {
                    assertNotNull(input);
                    jar.write(input.readAllBytes());
                }
                jar.closeEntry();
            }
        }
        return DeploymentArtifact.builder().artifactId(id).runtimeId(JavaPluginRuntimeAdapter.RUNTIME_ID)
            .version(version).source(root).build();
    }

    private static void awaitCollected(List<? extends WeakReference<?>> references) throws InterruptedException {
        // 只对无外部副作用的受控 fixture 进行有界回收探测。
        for (var attempt = 0; attempt < 60; attempt++) {
            System.gc();
            if (references.stream().allMatch(reference -> reference.get() == null)) return;
            Thread.sleep(25);
        }
        assertEquals(0, references.stream().filter(reference -> reference.get() != null).count(),
            () -> "live Engine retains retired fixture references at indexes "
                + java.util.stream.IntStream.range(0, references.size())
                    .filter(index -> references.get(index).get() != null).boxed().toList());
    }
}
