package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactState;
import com.sstlfsj.fibra.engine.RuntimeResourceOwner;
import com.sstlfsj.fibra.engine.RuntimeResourceSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPluginRuntimeAdapterTest {
    @Test
    void updateRebuildsChangedArtifactAndDependentsButRetainsUnrelatedDefinitions(@TempDir Path work)
        throws Exception {
        var adapter = new JavaPluginRuntimeAdapter();
        var owner = adapter.create();
        var initial = List.of(artifact(work, "a", "1.0.0", List.of()),
            artifact(work, "b", "1.0.0", List.of("a")), artifact(work, "c", "1.0.0", List.of()));
        install(owner, initial);
        var beforeB = owner.catalog().plugins().find("b_____").orElseThrow().definition();
        var beforeC = owner.catalog().plugins().find("c_____").orElseThrow().definition();
        var retainedLoader = beforeC.factory().getClass().getClassLoader();

        var target = List.of(artifact(work, "a", "2.0.0", List.of()),
            initial.get(1), initial.get(2));
        var update = owner.createUpdate(target);
        update.prepareAsync().block();

        assertEquals(List.of("a", "b"), update.affectedArtifacts().stream()
            .map(ArtifactId::value).sorted().toList());
        assertNotSame(beforeB, update.catalog().plugins().find("b_____").orElseThrow().definition());
        assertSame(beforeC, update.catalog().plugins().find("c_____").orElseThrow().definition());
        update.adopt();
        update.closeAsync().block();
        assertSame(retainedLoader, retainedLoader.loadClass("fixture.LateLoaded").getClassLoader());
        assertPayload(retainedLoader, "c", "1.0.0");
        owner.closeAsync().block();
    }

    @Test
    void partialEntrypointFailureKeepsEveryCreatedLoaderOwnedUntilCleanup(@TempDir Path work)
        throws Exception {
        var closed = new ArrayList<PluginClassLoader>();
        var owner = adapter(loader -> {
            closed.add(loader);
            loader.close();
        }).create();
        var update = owner.createUpdate(List.of(artifact(work, "a", "1.0.0", List.of()),
            artifact(work, "b", "1.0.0", List.of("a"), "fixture.AbsentEntrypoint"),
            artifact(work, "c", "1.0.0", List.of())));
        assertThrows(JavaRuntimeException.class, () -> update.prepareAsync().block());
        assertEquals(3, update.snapshot().resources().size());
        assertEquals(3, owner.snapshot().resources().size());
        assertTrue(owner.catalog().plugins().entries().isEmpty());
        update.closeAsync().block();
        assertEquals(3, closed.size());
        assertTrue(owner.snapshot().resources().isEmpty());
        owner.closeAsync().block();
    }

    @Test
    void failedRetirementRetainsItsPrerequisiteButClosesIndependentSiblings(@TempDir Path work)
        throws Exception {
        var attempted = new ArrayList<String>();
        var failingLoader = new AtomicReference<PluginClassLoader>();
        var owner = adapter(loader -> {
            var file = Path.of(loader.getURLs()[0].getPath()).getFileName().toString();
            attempted.add(file);
            if (loader == failingLoader.get()) throw new IOException("cannot close old b");
            loader.close();
        }).create();
        var initial = List.of(artifact(work, "a", "1.0.0", List.of()),
            artifact(work, "b", "1.0.0", List.of("a")),
            artifact(work, "c", "1.0.0", List.of()), artifact(work, "d", "1.0.0", List.of()));
        install(owner, initial);
        var originalLoaders = loaders(owner);
        failingLoader.set(originalLoaders.get("b_____"));
        var update = owner.createUpdate(List.of(artifact(work, "a", "2.0.0", List.of()), initial.get(1),
            initial.get(2), artifact(work, "d", "2.0.0", List.of())));
        update.prepareAsync().block();
        update.adopt();
        var activeLoaders = loaders(owner);
        try {
            var failure = assertThrows(JavaRuntimeException.class, () -> update.closeAsync().block());
            assertSame(failure, assertThrows(JavaRuntimeException.class, () -> update.closeAsync().block()));
            assertEquals(1, attempted.stream().filter("b-1.0.0.jar"::equals).count());
            assertTrue(attempted.contains("d-1.0.0.jar"));
            assertTrue(attempted.stream().noneMatch("a-1.0.0.jar"::equals));
            var old = update.snapshot().resources().stream().collect(java.util.stream.Collectors.toMap(
                resource -> resource.artifact().id().value(), resource -> resource));
            assertEquals(RuntimeResourceSnapshot.State.CLOSE_FAILED, old.get("b").state());
            assertNotNull(old.get("b").failure());
            assertEquals(RuntimeResourceSnapshot.State.RETIRED, old.get("a").state());
            assertEquals(RuntimeResourceSnapshot.State.CLOSED, old.get("d").state());
            assertPayload(originalLoaders.get("a_____"), "a", "1.0.0");
            assertPayload(originalLoaders.get("c_____"), "c", "1.0.0");
            assertThrows(JavaRuntimeException.class, () -> owner.closeAsync().block());
            assertTrue(attempted.contains("c-1.0.0.jar"));
            assertTrue(attempted.contains("a-2.0.0.jar"));
            assertTrue(attempted.stream().noneMatch("a-1.0.0.jar"::equals));
        } finally {
            for (var loader : originalLoaders.values()) loader.close();
            for (var loader : activeLoaders.values()) loader.close();
        }
    }

    @Test
    void failedPreparedLoaderProtectsItsBorrowedActivePrerequisite(@TempDir Path work) throws Exception {
        var attempted = new ArrayList<String>();
        var failedLoader = new AtomicReference<PluginClassLoader>();
        var owner = adapter(loader -> {
            var file = Path.of(loader.getURLs()[0].getPath()).getFileName().toString();
            attempted.add(file);
            if (file.equals("b-1.0.0.jar")) {
                failedLoader.set(loader);
                throw new IOException("cannot close prepared b");
            }
            loader.close();
        }).create();
        var a = artifact(work, "a", "1.0.0", List.of());
        var c = artifact(work, "c", "1.0.0", List.of());
        install(owner, List.of(a, c));
        var originals = loaders(owner);
        var update = owner.createUpdate(List.of(a, c,
            artifact(work, "b", "1.0.0", List.of("a"), "fixture.AbsentEntrypoint")));
        try {
            assertThrows(JavaRuntimeException.class, () -> update.prepareAsync().block());
            assertThrows(JavaRuntimeException.class, () -> update.closeAsync().block());
            assertThrows(JavaRuntimeException.class, () -> owner.closeAsync().block());
            assertEquals(List.of("b-1.0.0.jar", "c-1.0.0.jar"), attempted);
            assertPayload(originals.get("a_____"), "a", "1.0.0");
            assertEquals(RuntimeResourceSnapshot.State.CLOSE_FAILED,
                update.snapshot().resources().getFirst().state());
        } finally {
            for (var loader : originals.values()) loader.close();
            if (failedLoader.get() != null) failedLoader.get().close();
        }
    }

    @Test
    void closingDuringEntrypointPreparationJoinsItWithoutLosingTheLoader(@TempDir Path work) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closed = new ArrayList<PluginClassLoader>();
        fixture.PreparationObserver.callback = loader -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("preparation timed out");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        };
        var owner = new JavaPluginRuntimeAdapter(getClass().getClassLoader(),
            List.of("java.", "com.sstlfsj.fibra.", "reactor.", "org.reactivestreams.",
                "fixture.PreparationObserver"), loader -> {
                    closed.add(loader);
                    loader.close();
                }).create();
        var update = owner.createUpdate(List.of(artifact(work, "a", "1.0.0", List.of(),
            fixture.PreparationEntrypoint.class.getName())));
        try (var worker = Executors.newSingleThreadExecutor()) {
            var preparation = worker.submit(() -> update.prepareAsync().block());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var closing = owner.closeAsync().toFuture();
            assertTrue(!closing.isDone());
            assertEquals(1, owner.snapshot().resources().size());
            release.countDown();
            preparation.get(5, TimeUnit.SECONDS);
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(1, closed.size());
            assertThrows(IllegalStateException.class, update::adopt);
        } finally {
            release.countDown();
            fixture.PreparationObserver.callback = null;
            owner.closeAsync().block();
        }
    }

    @Test
    void snapshotsCanOverlapPreparationAndAdoptionWithoutLockInversion(@TempDir Path work) throws Exception {
        var owner = new JavaPluginRuntimeAdapter().create();
        var records = List.of(artifact(work, "a", "1.0.0", List.of()));
        install(owner, records);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var snapshots = workers.submit(() -> {
                for (var index = 0; index < 500; index++) owner.snapshot();
            });
            var updates = workers.submit(() -> {
                for (var index = 0; index < 50; index++) install(owner, records);
            });
            snapshots.get(5, TimeUnit.SECONDS);
            updates.get(5, TimeUnit.SECONDS);
        } finally {
            owner.closeAsync().block();
        }
    }

    @Test
    void invalidTargetDoesNotChangeTheActiveOwnerAndCloseBeforePrepareIsTerminal(@TempDir Path work)
        throws Exception {
        var owner = new JavaPluginRuntimeAdapter().create();
        var initial = List.of(artifact(work, "a", "1.0.0", List.of()));
        install(owner, initial);
        var definition = owner.catalog().plugins().find("a_____").orElseThrow().definition();
        var invalid = owner.createUpdate(List.of(artifact(work, "b", "1.0.0", List.of("absent"))));
        assertThrows(RuntimeException.class, () -> invalid.prepareAsync().block());
        assertSame(definition, owner.catalog().plugins().find("a_____").orElseThrow().definition());
        invalid.closeAsync().block();

        RuntimeResourceOwner empty = new JavaPluginRuntimeAdapter().create();
        var closed = empty.createUpdate(List.of());
        closed.closeAsync().block();
        assertThrows(IllegalStateException.class, () -> closed.prepareAsync().block());
        closed.closeAsync().block();
        owner.closeAsync().block();
        empty.closeAsync().block();
    }

    private static JavaPluginRuntimeAdapter adapter(JavaPluginRuntimeAdapter.LoaderCloser closer) {
        return new JavaPluginRuntimeAdapter(JavaPluginRuntimeAdapterTest.class.getClassLoader(),
            List.of("java.", "com.sstlfsj.fibra.", "reactor.", "org.reactivestreams."), closer);
    }

    private static Map<String, PluginClassLoader> loaders(RuntimeResourceOwner owner) {
        return owner.catalog().plugins().entries().stream().collect(java.util.stream.Collectors.toMap(
            entry -> entry.definition().name(),
            entry -> (PluginClassLoader) entry.definition().factory().getClass().getClassLoader()));
    }

    private static void assertPayload(ClassLoader loader, String id, String version) throws IOException {
        try (var input = loader.getResourceAsStream("payload/" + id + ".txt")) {
            assertNotNull(input);
            assertEquals(version, new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static void install(RuntimeResourceOwner owner, List<ArtifactRecord> target) {
        var update = owner.createUpdate(target);
        update.prepareAsync().block();
        update.adopt();
        update.closeAsync().block();
    }

    private static ArtifactRecord artifact(Path work, String id, String version, List<String> requires)
        throws Exception {
        return artifact(work, id, version, requires, fixture.SampleEntrypoint.class.getName());
    }

    private static ArtifactRecord artifact(Path work, String id, String version, List<String> requires,
                                           String entrypoint) throws Exception {
        var jar = work.resolve(id + '-' + version + ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/fibra/plugin.yaml"));
            var dependencies = requires.isEmpty() ? "[]" : requires.stream().map(value -> "\n  - id: "
                + value + "\n    version: '*'").collect(java.util.stream.Collectors.joining());
            output.write(("id: " + id + "\nversion: " + version + "\nentrypoint: "
                + entrypoint + "\nrequires: " + dependencies + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
            var classFile = fixture.SampleEntrypoint.class.getName().replace('.', '/') + ".class";
            output.putNextEntry(new JarEntry(classFile));
            try (InputStream input = fixture.SampleEntrypoint.class.getResourceAsStream('/' + classFile)) {
                var bytes = input.readAllBytes();
                var source = "sample".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                var replacement = (id + "_____").substring(0, 6).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for (var index = 0; index <= bytes.length - source.length; index++) {
                    if (java.util.Arrays.equals(source, java.util.Arrays.copyOfRange(bytes, index, index + source.length))) {
                        System.arraycopy(replacement, 0, bytes, index, replacement.length);
                    }
                }
                output.write(bytes);
            }
            output.closeEntry();
            if (entrypoint.equals(fixture.PreparationEntrypoint.class.getName())) {
                var preparationClass = "fixture/PreparationEntrypoint.class";
                output.putNextEntry(new JarEntry(preparationClass));
                try (var input = fixture.PreparationEntrypoint.class.getResourceAsStream('/' + preparationClass)) {
                    output.write(input.readAllBytes());
                }
                output.closeEntry();
            }
            var late = "fixture/LateLoaded.class";
            output.putNextEntry(new JarEntry(late));
            try (var input = fixture.LateLoaded.class.getResourceAsStream('/' + late)) {
                output.write(input.readAllBytes());
            }
            output.closeEntry();
            output.putNextEntry(new JarEntry("payload/" + id + ".txt"));
            output.write(version.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return ArtifactRecord.builder().id(new ArtifactId(id)).runtimeId(JavaPluginRuntimeAdapter.RUNTIME_ID)
            .version(version).checksum(id + version).revision(version).location(jar).state(ArtifactState.INSTALLED)
            .updatedAt(Instant.EPOCH).build();
    }
}
