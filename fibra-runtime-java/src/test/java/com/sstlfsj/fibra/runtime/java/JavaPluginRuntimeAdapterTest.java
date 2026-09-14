package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactState;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.engine.RuntimeResourceOwner;
import com.sstlfsj.fibra.engine.RuntimeResourceSnapshot;
import com.sstlfsj.fibra.engine.RuntimeResourceUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPluginRuntimeAdapterTest {
    @Test
    void ownerCloseRetainsTheCatalogUntilCleanupIsSubscribed(@TempDir Path work) throws Exception {
        var owner = new JavaPluginRuntimeAdapter().create();
        install(owner, List.of(artifact(work, "a", "1.0.0", List.of())));
        var catalog = owner.catalog();
        var close = owner.closeAsync();
        try {
            assertSame(catalog, owner.catalog(), "requesting close must not release the active catalog");
        } finally {
            close.block(Duration.ofSeconds(5));
        }
        assertTrue(owner.catalog().plugins().entries().isEmpty());
    }

    @Test
    void updateClosePreservesTheCompletedSnapshotWhenOwnerAlreadyCleanedIt(@TempDir Path work) throws Exception {
        var owner = new JavaPluginRuntimeAdapter().create();
        var update = owner.createUpdate(List.of(artifact(work, "a", "1.0.0", List.of())));
        try {
            update.prepareAsync().block(Duration.ofSeconds(5));
            owner.closeAsync().block(Duration.ofSeconds(5));
            var completed = update.snapshot();
            assertEquals(1, completed.resources().size());
            assertEquals(RuntimeResourceSnapshot.State.CLOSED, completed.resources().getFirst().state());
            update.closeAsync().block(Duration.ofSeconds(5));
            assertEquals(completed, update.snapshot(), "later update cleanup must preserve completed metadata");
        } finally {
            owner.closeAsync().block(Duration.ofSeconds(5));
        }
    }

    @Test
    @Timeout(15)
    void failedOwnerCloseReleasesIndependentClosedLoadersButKeepsFailedResources(@TempDir Path work)
        throws Exception {
        var owner = adapter(loader -> {
            if (loader.getURLs()[0].getPath().endsWith("b-1.0.0.jar")) {
                throw new IOException("cannot close b");
            }
            loader.close();
        }).create();
        install(owner, List.of(artifact(work, "a", "1.0.0", List.of()),
            artifact(work, "b", "1.0.0", List.of("a")), artifact(work, "d", "1.0.0", List.of())));
        var original = weakLoaders(owner);
        try {
            assertThrows(JavaRuntimeException.class, () -> owner.closeAsync().block(Duration.ofSeconds(5)));
            assertNotNull(original.get("b_____").get());
            assertPayload(original.get("a_____").get(), "a", "1.0.0");
            assertCollected(List.of(original.get("d_____")), "failed Owner retains independent closed loader");
        } finally {
            for (var reference : original.values()) {
                var loader = reference.get();
                if (loader != null) loader.close();
            }
            Reference.reachabilityFence(owner);
        }
    }

    @Test
    @Timeout(15)
    void cleanedFailedPreparationDoesNotRetainThePluginExceptionOrItsLoader(@TempDir Path work)
        throws Exception {
        var closed = new ArrayList<WeakReference<?>>();
        var owner = adapter(loader -> {
            loader.close();
            closed.add(new WeakReference<>(loader));
        }).create();
        var update = owner.createUpdate(List.of(artifact(work, "a", "1.0.0", List.of(),
            "fixture.ThrowingEntrypoint")));
        var error = failedPreparation(update);
        try {
            update.closeAsync().block(Duration.ofSeconds(5));
            assertEquals(1, closed.size());
            assertTrue(owner.snapshot().resources().isEmpty());
            var references = new ArrayList<>(closed);
            references.add(error);
            assertCollected(references, "cleaned Update retains plugin preparation exception or loader");
        } finally {
            Reference.reachabilityFence(update);
            owner.closeAsync().block(Duration.ofSeconds(5));
        }
    }

    private static WeakReference<?> failedPreparation(RuntimeResourceUpdate update) {
        var failure = assertThrows(RuntimeException.class, () -> update.prepareAsync().block(Duration.ofSeconds(5)));
        assertEquals("fixture.ThrowingEntrypoint$PreparationFailure", failure.getClass().getName());
        return new WeakReference<>(failure);
    }

    @Test
    @Timeout(15)
    void completedUpdatesDoNotRetainRetiredLoadersOrEntriesAcrossRealJarReplacements(@TempDir Path work)
        throws Exception {
        var owner = new JavaPluginRuntimeAdapter().create();
        var completed = new ArrayList<RuntimeResourceUpdate>();
        var retired = new ArrayList<WeakReference<?>>();
        var identities = new java.util.HashSet<String>();
        try {
            for (var round = 0; round < 4; round++) {
                retired.addAll(weakCatalog(owner));
                var update = owner.createUpdate(List.of(artifact(work, "a", "1.0." + round, List.of())));
                update.prepareAsync().block(Duration.ofSeconds(5));
                update.adopt();
                update.closeAsync().block(Duration.ofSeconds(5));
                completed.add(update);
                assertTrue(identities.add(owner.snapshot().resources().getFirst().identity()));
            }
            var active = weakCatalog(owner);
            assertAll(
                () -> assertCollected(retired, "completed Update retains retired loader or entry"),
                () -> active.forEach(reference -> assertNotNull(reference.get(), "active loader/entry control")));
        } finally {
            Reference.reachabilityFence(completed);
            owner.closeAsync().block(Duration.ofSeconds(5));
        }
    }

    @Test
    @Timeout(15)
    void failedRetirementReleasesSuccessfulSiblingWhileKeepingTheActualFailedDependencyChain(@TempDir Path work)
        throws Exception {
        var owner = adapter(loader -> {
            if (loader.getURLs()[0].getPath().endsWith("b-1.0.0.jar")) {
                throw new IOException("cannot close old b");
            }
            loader.close();
        }).create();
        var initial = List.of(artifact(work, "a", "1.0.0", List.of()),
            artifact(work, "b", "1.0.0", List.of("a")), artifact(work, "d", "1.0.0", List.of()));
        install(owner, initial);
        var original = weakLoaders(owner);
        var update = owner.createUpdate(List.of(artifact(work, "a", "2.0.0", List.of()),
            initial.get(1), artifact(work, "d", "2.0.0", List.of())));
        update.prepareAsync().block(Duration.ofSeconds(5));
        update.adopt();
        try {
            assertThrows(JavaRuntimeException.class, () -> update.closeAsync().block(Duration.ofSeconds(5)));
            assertNotNull(original.get("b_____").get(), "failed resource must stay owned");
            assertNotNull(original.get("a_____").get(), "actual old prerequisite must stay owned");
            assertPayload(original.get("b_____").get(), "a", "1.0.0");
            assertCollected(List.of(original.get("d_____")), "successful retired sibling remains strongly reachable");
        } finally {
            assertThrows(JavaRuntimeException.class, () -> owner.closeAsync().block(Duration.ofSeconds(5)));
            for (var reference : original.values()) {
                var loader = reference.get();
                if (loader != null) loader.close();
            }
            Reference.reachabilityFence(update);
        }
    }

    private static List<WeakReference<?>> weakCatalog(RuntimeResourceOwner owner) {
        var references = new ArrayList<WeakReference<?>>();
        owner.catalog().plugins().entries().forEach(entry -> {
            references.add(new WeakReference<>(entry));
            references.add(new WeakReference<>(entry.definition().factory().getClass().getClassLoader()));
        });
        return references;
    }

    private static Map<String, WeakReference<PluginClassLoader>> weakLoaders(RuntimeResourceOwner owner) {
        return loaders(owner).entrySet().stream().collect(java.util.stream.Collectors.toMap(
            Map.Entry::getKey, entry -> new WeakReference<>(entry.getValue())));
    }

    private static void assertCollected(List<WeakReference<?>> references, String message) throws InterruptedException {
        // 仅受控 JAR fixture 使用有界 GC 探测；close 成功与 collect 是不同断言。
        for (var attempt = 0; attempt < 60; attempt++) {
            System.gc();
            if (references.stream().allMatch(reference -> reference.get() == null)) return;
            Thread.sleep(25);
        }
        assertEquals(0, references.stream().filter(reference -> reference.get() != null).count(), message);
    }

    @Test
    @Timeout(30)
    void sameVersionContentChangesRebuildOnlyTheDependencyClosureAcrossRepeatedUpdates(@TempDir Path work)
        throws Exception {
        var firstSource = artifact(work.resolve("first"), "a", "1.0.0", List.of(),
            fixture.SampleEntrypoint.class.getName(), "first");
        var secondSource = artifact(work.resolve("second"), "a", "1.0.0", List.of(),
            fixture.SampleEntrypoint.class.getName(), "second");
        var dependentSource = artifact(work, "b", "1.0.0", List.of("a"));
        var unrelatedSource = artifact(work, "c", "1.0.0", List.of());
        var created = 0;
        var closed = new AtomicInteger();
        var owner = adapter(loader -> {
            loader.close();
            closed.incrementAndGet();
        }).create();
        var samples = new StringBuilder("round,created,closed,activeLoaders,threads,openFd,heapUsedBytes,updateNanos\n");
        try (var store = new ArtifactStore(work.resolve("store"))) {
            try {
                var first = saved(store, firstSource);
                var second = saved(store, secondSource);
                assertEquals(first.version(), second.version());
                assertNotEquals(first.revision(), second.revision());
                var dependent = saved(store, dependentSource);
                var unrelated = saved(store, unrelatedSource);
                install(owner, List.of(first, dependent, unrelated));
                var previous = loaders(owner);
                created = previous.size();
                var unrelatedDefinition = owner.catalog().plugins().find("c_____").orElseThrow().definition();
                stabilitySample(samples, 0, created, closed.get(), owner, 0);

                for (var round = 1; round <= 24; round++) {
                    var source = round % 2 == 1 ? secondSource : firstSource;
                    var selected = saved(store, source);
                    assertEquals(round % 2 == 1 ? second.revision() : first.revision(), selected.revision());
                    var started = System.nanoTime();
                    var update = owner.createUpdate(List.of(selected, dependent, unrelated));
                    update.prepareAsync().block(Duration.ofSeconds(5));
                    assertEquals(List.of("a", "b"), update.affectedArtifacts().stream()
                        .map(ArtifactId::value).sorted().toList());
                    update.adopt();
                    update.closeAsync().block(Duration.ofSeconds(5));
                    var updateNanos = System.nanoTime() - started;
                    var current = loaders(owner);
                    for (var name : List.of("a_____", "b_____")) {
                        assertNotSame(previous.get(name), current.get(name));
                        created++;
                    }
                    assertSame(previous.get("c_____"), current.get("c_____"));
                    assertSame(unrelatedDefinition,
                        owner.catalog().plugins().find("c_____").orElseThrow().definition());
                    assertPayload(current.get("a_____"), "a", round % 2 == 1 ? "second" : "first");
                    assertPayload(current.get("b_____"), "a", round % 2 == 1 ? "second" : "first");
                    assertEquals(3, owner.snapshot().resources().size());
                    assertEquals(3, created - closed.get());

                    var repeated = saved(store, source);
                    assertEquals(selected.revision(), repeated.revision());
                    var definitions = owner.catalog().plugins().entries().stream()
                        .map(entry -> entry.definition()).toList();
                    var beforeClose = closed.get();
                    var unchanged = owner.createUpdate(List.of(repeated, dependent, unrelated));
                    unchanged.prepareAsync().block(Duration.ofSeconds(5));
                    assertTrue(unchanged.affectedArtifacts().isEmpty());
                    unchanged.adopt();
                    unchanged.closeAsync().block(Duration.ofSeconds(5));
                    assertEquals(beforeClose, closed.get());
                    var retained = loaders(owner);
                    current.forEach((name, loader) -> assertSame(loader, retained.get(name)));
                    var retainedDefinitions = owner.catalog().plugins().entries().stream()
                        .map(entry -> entry.definition()).toList();
                    for (var index = 0; index < definitions.size(); index++) {
                        assertSame(definitions.get(index), retainedDefinitions.get(index));
                    }
                    previous = current;
                    stabilitySample(samples, round, created, closed.get(), owner, updateNanos);
                }
            } finally {
                owner.closeAsync().block(Duration.ofSeconds(5));
            }
            assertEquals(created, closed.get());
            assertTrue(owner.snapshot().resources().isEmpty());
            stabilitySample(samples, 25, created, closed.get(), owner, 0);
        } finally {
            Files.writeString(Path.of("target", "java-runtime-stability.csv"), samples);
        }
    }

    private static ArtifactRecord saved(ArtifactStore store, ArtifactRecord source) {
        try (var transaction = store.prepareInstall(source.id(), source.runtimeId(), source.version(), source.location())) {
            return transaction.save();
        }
    }

    private static void stabilitySample(StringBuilder samples, int round, int created, int closed,
                                        RuntimeResourceOwner owner, long updateNanos) {
        var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        var descriptors = operatingSystem instanceof com.sun.management.UnixOperatingSystemMXBean unix
            ? Long.toString(unix.getOpenFileDescriptorCount()) : "unavailable";
        samples.append(round).append(',').append(created).append(',').append(closed).append(',')
            .append(owner.snapshot().resources().size()).append(',')
            .append(ManagementFactory.getThreadMXBean().getThreadCount()).append(',')
            .append(descriptors).append(',').append(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed())
            .append(',').append(updateNanos).append('\n');
    }

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
        return artifact(work, id, version, requires, entrypoint, version);
    }

    private static ArtifactRecord artifact(Path work, String id, String version, List<String> requires,
                                           String entrypoint, String payload) throws Exception {
        var root = Files.createDirectories(work.resolve(id + '-' + version));
        var jar = Files.createDirectories(root.resolve("lib")).resolve(id + '-' + version + ".jar");
        Files.writeString(root.resolve("plugin.properties"), "formatVersion=1\nruntime=java\npayload=lib/"
            + jar.getFileName() + "\n");
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
            if (entrypoint.equals("fixture.ThrowingEntrypoint")) {
                for (var name : List.of("fixture/ThrowingEntrypoint.class",
                    "fixture/ThrowingEntrypoint$PreparationFailure.class")) {
                    output.putNextEntry(new JarEntry(name));
                    try (var input = fixture.ThrowingEntrypoint.class.getResourceAsStream('/' + name)) {
                        assertNotNull(input);
                        output.write(input.readAllBytes());
                    }
                    output.closeEntry();
                }
            }
            var late = "fixture/LateLoaded.class";
            output.putNextEntry(new JarEntry(late));
            try (var input = fixture.LateLoaded.class.getResourceAsStream('/' + late)) {
                output.write(input.readAllBytes());
            }
            output.closeEntry();
            output.putNextEntry(new JarEntry("payload/" + id + ".txt"));
            output.write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return ArtifactRecord.builder().id(new ArtifactId(id)).runtimeId(JavaPluginRuntimeAdapter.RUNTIME_ID)
            .version(version).checksum(id + version).revision(version).location(root).state(ArtifactState.INSTALLED)
            .updatedAt(Instant.EPOCH).build();
    }
}
