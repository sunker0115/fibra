package com.sstlfsj.fibra.scenario;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.engine.DeploymentArtifact;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.InstallArtifact;
import com.sstlfsj.fibra.engine.PublishedView;
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

class JavaPublishedViewRetentionTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ArtifactId ARTIFACT = new ArtifactId("retention");

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
        var version = "1.0." + round;
        var root = Files.createDirectories(work.resolve("source-" + round));
        Files.writeString(root.resolve("plugin.properties"),
            "formatVersion=1\nruntime=java\npayload=plugin.jar\n");
        try (var jar = new JarOutputStream(Files.newOutputStream(root.resolve("plugin.jar")))) {
            jar.putNextEntry(new JarEntry("META-INF/fibra/plugin.yaml"));
            jar.write(("id: retention\nversion: " + version
                + "\nentrypoint: fixture.RetentionJavaEntrypoint\nrequires: []\n")
                .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            for (var name : List.of("fixture/RetentionJavaEntrypoint.class",
                "fixture/RetentionJavaEntrypoint$Descriptor.class")) {
                jar.putNextEntry(new JarEntry(name));
                try (var input = RetentionJavaEntrypoint.class.getResourceAsStream('/' + name)) {
                    assertNotNull(input);
                    jar.write(input.readAllBytes());
                }
                jar.closeEntry();
            }
        }
        return DeploymentArtifact.builder().artifactId(ARTIFACT).runtimeId(JavaPluginRuntimeAdapter.RUNTIME_ID)
            .version(version).source(root).build();
    }

    private static void awaitCollected(List<WeakReference<ClassLoader>> references) throws InterruptedException {
        // 只对无外部副作用的受控 fixture 进行有界回收探测。
        for (var attempt = 0; attempt < 60; attempt++) {
            System.gc();
            if (references.stream().allMatch(reference -> reference.get() == null)) return;
            Thread.sleep(25);
        }
        assertEquals(0, references.stream().filter(reference -> reference.get() != null).count(),
            () -> "live Engine retains retired descriptor loaders from rounds "
                + java.util.stream.IntStream.range(0, references.size())
                    .filter(index -> references.get(index).get() != null).boxed().toList());
    }
}
