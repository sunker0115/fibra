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
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.jar.Attributes;
import javax.tools.ToolProvider;

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
            generatedType("a", "Entrypoint"), "first");
        var secondSource = artifact(work.resolve("second"), "a", "1.0.0", List.of(),
            generatedType("a", "Entrypoint"), "second");
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
        assertSame(retainedLoader, retainedLoader.loadClass(generatedType("c", "LateLoaded")).getClassLoader());
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

    @Test
    void sameVersionLibraryInExecutableAndDependencyUsesEachArtifactsLocalDefinition(@TempDir Path work)
        throws Exception {
        var classes = compileClass(work.resolve("shared"), "shared.Library");
        var dependency = artifact(work, "a", "1.0.0", List.of());
        var executable = artifact(work, "b", "1.0.0", List.of("a"));
        addLibrary(dependency, "shared-a.jar", classes, "shared.Library");
        addLibrary(executable, "shared-b.jar", classes, "shared.Library");
        var owner = new JavaPluginRuntimeAdapter().create();
        try {
            install(owner, List.of(dependency, executable));

            var loaders = loaders(owner);
            assertSame(loaders.get("a_____"), loaders.get("a_____").loadClass("shared.Library").getClassLoader());
            assertSame(loaders.get("b_____"), loaders.get("b_____").loadClass("shared.Library").getClassLoader());
        } finally {
            owner.closeAsync().block(Duration.ofSeconds(5));
        }
    }

    @Test
    void executableLocalClassMayShadowItsContractOnlyDependency(@TempDir Path work) throws Exception {
        var classes = compileClass(work.resolve("shared"), "shared.Contract");
        var contract = contractArtifact(work, "a", "1.0.0", List.of());
        var executable = artifact(work, "b", "1.0.0", List.of("a"));
        var fallback = artifact(work, "c", "1.0.0", List.of("a"));
        addLibrary(contract, "contract.jar", classes, "shared.Contract");
        addLibrary(executable, "shadow.jar", classes, "shared.Contract");
        var owner = new JavaPluginRuntimeAdapter().create();
        try {
            install(owner, List.of(contract, executable, fallback));

            var loaders = loaders(owner);
            assertSame(loaders.get("b_____"), loaders.get("b_____").loadClass("shared.Contract").getClassLoader());
            var contractType = loaders.get("c_____").loadClass("shared.Contract");
            assertTrue(contractType.getClassLoader() instanceof PluginClassLoader);
            assertNotSame(loaders.get("c_____"), contractType.getClassLoader());
        } finally {
            owner.closeAsync().block(Duration.ofSeconds(5));
        }
    }

    @Test
    void rejectsSameClassFromMainAndLibAndFromTwoLibsOfOneArtifact(@TempDir Path work) throws Exception {
        var mainAndLib = artifact(work.resolve("main-lib"), "a", "1.0.0", List.of());
        copyMainClassToLibrary(mainAndLib, generatedType("a", "Entrypoint"), "duplicate.jar");
        var mainFailure = prepareFailure(mainAndLib);
        assertConflict(mainFailure, "a", generatedType("a", "Entrypoint"), "a", "a",
            "a-1.0.0.jar", "duplicate.jar");

        var classes = compileClass(work.resolve("lib-lib/shared"), "shared.Library");
        var libAndLib = artifact(work.resolve("lib-lib"), "b", "1.0.0", List.of());
        addLibrary(libAndLib, "first.jar", classes, "shared.Library");
        addLibrary(libAndLib, "second.jar", classes, "shared.Library");
        var libFailure = prepareFailure(libAndLib);
        assertConflict(libFailure, "b", "shared.Library", "b", "b", "first.jar", "second.jar");
    }

    @Test
    void rootLocalClassMayShadowATransitiveDependency(@TempDir Path work) throws Exception {
        var classes = compileClass(work.resolve("shared"), "shared.Transitive");
        var base = artifact(work, "a", "1.0.0", List.of());
        var middle = artifact(work, "b", "1.0.0", List.of("a"));
        var root = artifact(work, "c", "1.0.0", List.of("b"));
        addLibrary(base, "base-shared.jar", classes, "shared.Transitive");
        addLibrary(root, "root-shared.jar", classes, "shared.Transitive");

        var owner = new JavaPluginRuntimeAdapter().create();
        try {
            install(owner, List.of(base, middle, root));

            var loaders = loaders(owner);
            assertSame(loaders.get("a_____"), loaders.get("b_____").loadClass("shared.Transitive").getClassLoader());
            assertSame(loaders.get("c_____"), loaders.get("c_____").loadClass("shared.Transitive").getClassLoader());
        } finally {
            owner.closeAsync().block(Duration.ofSeconds(5));
        }
    }

    @Test
    void dependencyOrderSelectsTheFirstPathWithoutMergingSameNamedClasses(@TempDir Path work) throws Exception {
        var type = "shared.Versioned";
        var firstClasses = compileClass(work.resolve("first"), type,
            "public static String value() { return \"first\"; }");
        var secondClasses = compileClass(work.resolve("second"), type,
            "public static String value() { return \"second\"; }");
        var first = artifact(work, "b", "1.0.0", List.of());
        var second = artifact(work, "c", "2.0.0", List.of());
        var firstRoot = artifact(work, "a", "1.0.0", List.of("b", "c"));
        var secondRoot = artifact(work, "d", "1.0.0", List.of("c", "b"));
        addLibrary(first, "versioned.jar", firstClasses, type);
        addLibrary(second, "versioned.jar", secondClasses, type);
        var owner = new JavaPluginRuntimeAdapter().create();
        try {
            install(owner, List.of(firstRoot, first, second, secondRoot));

            var loaders = loaders(owner);
            var firstType = loaders.get("b_____").loadClass(type);
            var secondType = loaders.get("c_____").loadClass(type);
            assertEquals("first", invokeValue(firstType));
            assertEquals("second", invokeValue(secondType));
            assertNotSame(firstType, secondType);
            assertSame(loaders.get("b_____"), firstType.getClassLoader());
            assertSame(loaders.get("c_____"), secondType.getClassLoader());
            assertSame(firstType, loaders.get("a_____").loadClass(type));
            assertSame(secondType, loaders.get("d_____").loadClass(type));
            assertEquals("first", invokeValue(loaders.get("a_____").loadClass(type)));
            assertEquals("second", invokeValue(loaders.get("d_____").loadClass(type)));
        } finally {
            owner.closeAsync().block(Duration.ofSeconds(5));
        }
    }

    @Test
    void countsOneDiamondDependencyOwnerOnceAndLoadsItsClassFromThatOwner(@TempDir Path work) throws Exception {
        var classes = compileClass(work.resolve("shared"), "shared.Diamond");
        var base = artifact(work, "a", "1.0.0", List.of());
        var left = artifact(work, "b", "1.0.0", List.of("a"));
        var right = artifact(work, "c", "1.0.0", List.of("a"));
        var root = artifact(work, "d", "1.0.0", List.of("b", "c"));
        addLibrary(base, "diamond.jar", classes, "shared.Diamond");
        var owner = new JavaPluginRuntimeAdapter().create();

        install(owner, List.of(base, left, right, root));

        var loaders = loaders(owner);
        assertSame(loaders.get("a_____"), loaders.get("d_____").loadClass("shared.Diamond").getClassLoader());
        owner.closeAsync().block(Duration.ofSeconds(5));
    }

    @Test
    void allowsUnrelatedArtifactsToLoadPrivateClassesWithTheSameName(@TempDir Path work) throws Exception {
        var classes = compileClass(work.resolve("shared"), "shared.PrivateLibrary");
        var first = artifact(work, "a", "1.0.0", List.of());
        var second = artifact(work, "b", "1.0.0", List.of());
        addLibrary(first, "private-a.jar", classes, "shared.PrivateLibrary");
        addLibrary(second, "private-b.jar", classes, "shared.PrivateLibrary");
        var owner = new JavaPluginRuntimeAdapter().create();

        install(owner, List.of(first, second));

        var loaders = loaders(owner);
        var firstType = loaders.get("a_____").loadClass("shared.PrivateLibrary");
        var secondType = loaders.get("b_____").loadClass("shared.PrivateLibrary");
        assertSame(loaders.get("a_____"), firstType.getClassLoader());
        assertSame(loaders.get("b_____"), secondType.getClassLoader());
        assertNotSame(firstType, secondType);
        owner.closeAsync().block(Duration.ofSeconds(5));
    }

    @Test
    void parentFirstOnlyRemovesAmbiguityWhenTheParentActuallyDefinesTheClass(@TempDir Path work) throws Exception {
        var parentClass = classBytes(JavaPluginRuntimeAdapterTest.class.getClassLoader(),
            "com.sstlfsj.fibra.Context");
        var first = artifact(work.resolve("hit"), "a", "1.0.0", List.of());
        var second = artifact(work.resolve("hit"), "b", "1.0.0", List.of("a"));
        addLibrary(first, "parent-a.jar", Map.of("com/sstlfsj/fibra/Context.class", parentClass));
        addLibrary(second, "parent-b.jar", Map.of("com/sstlfsj/fibra/Context.class", parentClass));
        var owner = new JavaPluginRuntimeAdapter().create();
        try {
            install(owner, List.of(first, second));
            assertSame(com.sstlfsj.fibra.Context.class,
                loaders(owner).get("b_____").loadClass("com.sstlfsj.fibra.Context"));
        } finally {
            owner.closeAsync().block(Duration.ofSeconds(5));
        }

        var missingClasses = compileClass(work.resolve("miss/classes"),
            "com.sstlfsj.fibra.dynamic.Shared");
        var missingFirst = artifact(work.resolve("miss"), "c", "1.0.0", List.of());
        var missingSecond = artifact(work.resolve("miss"), "d", "1.0.0", List.of("c"));
        addLibrary(missingFirst, "missing-c.jar", missingClasses,
            "com.sstlfsj.fibra.dynamic.Shared");
        addLibrary(missingSecond, "missing-d.jar", missingClasses,
            "com.sstlfsj.fibra.dynamic.Shared");
        var missingOwner = new JavaPluginRuntimeAdapter().create();
        try {
            install(missingOwner, List.of(missingFirst, missingSecond));
            var missingLoaders = loaders(missingOwner);
            assertSame(missingLoaders.get("c_____"), missingLoaders.get("c_____")
                .loadClass("com.sstlfsj.fibra.dynamic.Shared").getClassLoader());
            assertSame(missingLoaders.get("d_____"), missingLoaders.get("d_____")
                .loadClass("com.sstlfsj.fibra.dynamic.Shared").getClassLoader());
        } finally {
            missingOwner.closeAsync().block(Duration.ofSeconds(5));
        }
    }

    @Test
    void parentOwnedClassMayAppearInBothMainAndLib(@TempDir Path work) throws Exception {
        var type = "com.sstlfsj.fibra.Context";
        var bytes = classBytes(JavaPluginRuntimeAdapterTest.class.getClassLoader(), type);
        var artifact = artifact(work, "a", "1.0.0", List.of());
        addMainClass(artifact, type, bytes);
        addLibrary(artifact, "duplicate.jar", Map.of(type.replace('.', '/') + ".class", bytes));
        var owner = new JavaPluginRuntimeAdapter().create();

        install(owner, List.of(artifact));

        assertSame(com.sstlfsj.fibra.Context.class,
            loaders(owner).get("a_____").loadClass(type));
        owner.closeAsync().block(Duration.ofSeconds(5));
    }

    @Test
    void parentOwnedClassMayAppearInTwoLibrariesOfOneArtifact(@TempDir Path work) throws Exception {
        var type = "com.sstlfsj.fibra.Context";
        var bytes = classBytes(JavaPluginRuntimeAdapterTest.class.getClassLoader(), type);
        var artifact = artifact(work, "a", "1.0.0", List.of());
        var entry = Map.of(type.replace('.', '/') + ".class", bytes);
        addLibrary(artifact, "first.jar", entry);
        addLibrary(artifact, "second.jar", entry);
        var owner = new JavaPluginRuntimeAdapter().create();

        install(owner, List.of(artifact));

        assertSame(com.sstlfsj.fibra.Context.class,
            loaders(owner).get("a_____").loadClass(type));
        owner.closeAsync().block(Duration.ofSeconds(5));
    }

    @Test
    void parentMissStillRejectsMainAndLibOrTwoLibraryDuplicates(@TempDir Path work) throws Exception {
        var type = "com.sstlfsj.fibra.dynamic.Missing";
        var classes = compileClass(work.resolve("classes"), type);
        var bytes = Files.readAllBytes(classes.resolve(type.replace('.', '/') + ".class"));
        var mainAndLib = artifact(work.resolve("main-lib"), "a", "1.0.0", List.of());
        addMainClass(mainAndLib, type, bytes);
        addLibrary(mainAndLib, "duplicate.jar", Map.of(type.replace('.', '/') + ".class", bytes));
        var mainFailure = prepareFailure(mainAndLib);
        assertConflict(mainFailure, "a", type, "a", "a", "a-1.0.0.jar", "duplicate.jar");

        var libAndLib = artifact(work.resolve("lib-lib"), "b", "1.0.0", List.of());
        var entry = Map.of(type.replace('.', '/') + ".class", bytes);
        addLibrary(libAndLib, "first.jar", entry);
        addLibrary(libAndLib, "second.jar", entry);
        var libFailure = prepareFailure(libAndLib);
        assertConflict(libFailure, "b", type, "b", "b", "first.jar", "second.jar");
    }

    @Test
    void parentClassOutsideParentFirstPrefixesDoesNotReplacePluginLocalDefinitions(@TempDir Path work)
        throws Exception {
        var parentClass = classBytes(JavaPluginRuntimeAdapterTest.class.getClassLoader(), "fixture.LateLoaded");
        var first = artifact(work, "a", "1.0.0", List.of());
        var second = artifact(work, "b", "1.0.0", List.of("a"));
        addLibrary(first, "late-a.jar", Map.of("fixture/LateLoaded.class", parentClass));
        addLibrary(second, "late-b.jar", Map.of("fixture/LateLoaded.class", parentClass));

        var owner = new JavaPluginRuntimeAdapter().create();
        try {
            install(owner, List.of(first, second));

            var loaders = loaders(owner);
            assertSame(loaders.get("a_____"), loaders.get("a_____").loadClass("fixture.LateLoaded").getClassLoader());
            assertSame(loaders.get("b_____"), loaders.get("b_____").loadClass("fixture.LateLoaded").getClassLoader());
        } finally {
            owner.closeAsync().block(Duration.ofSeconds(5));
        }
    }

    @Test
    void parentLinkageErrorsAreNotTreatedAsAParentMiss(@TempDir Path work) throws Exception {
        var type = "com.sstlfsj.fibra.dynamic.Broken";
        var classes = compileClass(work.resolve("classes"), type);
        var artifact = artifact(work, "a", "1.0.0", List.of());
        addLibrary(artifact, "broken.jar", classes, type);
        var parent = new ClassLoader(JavaPluginRuntimeAdapterTest.class.getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.equals(type)) throw new NoClassDefFoundError("broken parent definition");
                return super.loadClass(name);
            }
        };
        var update = new JavaPluginRuntimeAdapter(parent, List.of("com.sstlfsj.fibra."))
            .create().createUpdate(List.of(artifact));

        var failure = assertThrows(JavaRuntimeException.class,
            () -> update.prepareAsync().block(Duration.ofSeconds(5)));

        assertEquals(JavaRuntimePhase.LOAD, failure.phase());
        assertEquals(new ArtifactId("a"), failure.artifactId());
        assertTrue(failure.getCause() instanceof NoClassDefFoundError);
        assertEquals("broken parent definition", failure.getCause().getMessage());
        assertTrue(update.snapshot().resources().isEmpty());
        update.closeAsync().block(Duration.ofSeconds(5));
    }

    @Test
    void multiReleaseJarsUseOnlyTheCurrentJvmView(@TempDir Path work) throws Exception {
        var classes = compileClass(work.resolve("classes"), "mr.Shared");
        var bytes = Files.readAllBytes(classes.resolve("mr/Shared.class"));
        var current = Runtime.version().feature();
        var base = artifact(work, "a", "1.0.0", List.of());
        var future = artifact(work, "b", "1.0.0", List.of("a"));
        addLibrary(base, "effective.jar", true, entries(
            "mr/Shared.class", bytes,
            "META-INF/versions/9/mr/Shared.class", bytes,
            "module-info.class", new byte[] {1},
            "META-INF/versions/9/module-info.class", new byte[] {2}));
        addLibrary(future, "future.jar", true, entries(
            "META-INF/versions/" + (current + 1) + "/mr/Shared.class", bytes));
        var owner = new JavaPluginRuntimeAdapter().create();

        install(owner, List.of(base, future));

        assertSame(loaders(owner).get("a_____"),
            loaders(owner).get("b_____").loadClass("mr.Shared").getClassLoader());
        owner.closeAsync().block(Duration.ofSeconds(5));
    }

    @Test
    void versionOnlyCurrentClassMayCoexistWithAnotherArtifactsRegularClass(@TempDir Path work) throws Exception {
        var classes = compileClass(work.resolve("classes"), "mr.VersionOnly");
        var bytes = Files.readAllBytes(classes.resolve("mr/VersionOnly.class"));
        var versioned = artifact(work, "a", "1.0.0", List.of());
        var regular = artifact(work, "b", "1.0.0", List.of("a"));
        addLibrary(versioned, "versioned.jar", true,
            entries("META-INF/versions/9/mr/VersionOnly.class", bytes));
        addLibrary(regular, "regular.jar", Map.of("mr/VersionOnly.class", bytes));

        var owner = new JavaPluginRuntimeAdapter().create();
        try {
            install(owner, List.of(versioned, regular));

            var loaders = loaders(owner);
            assertSame(loaders.get("a_____"), loaders.get("a_____").loadClass("mr.VersionOnly").getClassLoader());
            assertSame(loaders.get("b_____"), loaders.get("b_____").loadClass("mr.VersionOnly").getClassLoader());
        } finally {
            owner.closeAsync().block(Duration.ofSeconds(5));
        }
    }

    @Test
    void versionDirectoriesInNonMultiReleaseJarsDoNotDefineClasses(@TempDir Path work) throws Exception {
        var classes = compileClass(work.resolve("classes"), "mr.IgnoredVersion");
        var bytes = Files.readAllBytes(classes.resolve("mr/IgnoredVersion.class"));
        var ignored = artifact(work, "a", "1.0.0", List.of());
        var regular = artifact(work, "b", "1.0.0", List.of("a"));
        addLibrary(ignored, "not-mr.jar", false,
            entries("META-INF/versions/9/mr/IgnoredVersion.class", bytes));
        addLibrary(regular, "regular.jar", Map.of("mr/IgnoredVersion.class", bytes));
        var owner = new JavaPluginRuntimeAdapter().create();

        install(owner, List.of(ignored, regular));

        assertSame(loaders(owner).get("b_____"),
            loaders(owner).get("b_____").loadClass("mr.IgnoredVersion").getClassLoader());
        owner.closeAsync().block(Duration.ofSeconds(5));
    }

    private static JavaPluginRuntimeAdapter adapter(JavaPluginRuntimeAdapter.LoaderCloser closer) {
        return new JavaPluginRuntimeAdapter(JavaPluginRuntimeAdapterTest.class.getClassLoader(),
            List.of("java.", "com.sstlfsj.fibra.", "reactor.", "org.reactivestreams."), closer);
    }

    private static JavaRuntimeException prepareFailure(ArtifactRecord artifact) {
        return prepareFailure(List.of(artifact));
    }

    private static JavaRuntimeException prepareFailure(List<ArtifactRecord> artifacts) {
        var update = new JavaPluginRuntimeAdapter().create().createUpdate(artifacts);
        try {
            return assertThrows(JavaRuntimeException.class,
                () -> update.prepareAsync().block(Duration.ofSeconds(5)));
        } finally {
            update.closeAsync().block(Duration.ofSeconds(5));
        }
    }

    private static void assertConflict(JavaRuntimeException failure, String root, String className,
                                       String firstOwner, String secondOwner,
                                       String firstJar, String secondJar) {
        assertEquals(JavaRuntimePhase.LOAD, failure.phase());
        assertEquals(new ArtifactId(root), failure.artifactId());
        assertAll(
            () -> assertTrue(failure.getMessage().contains(className), failure.getMessage()),
            () -> assertTrue(failure.getMessage().contains(firstOwner), failure.getMessage()),
            () -> assertTrue(failure.getMessage().contains(secondOwner), failure.getMessage()),
            () -> assertTrue(failure.getMessage().contains(firstJar), failure.getMessage()),
            () -> assertTrue(failure.getMessage().contains(secondJar), failure.getMessage()));
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

    private static String invokeValue(Class<?> type) throws ReflectiveOperationException {
        return (String) type.getMethod("value").invoke(null);
    }

    private static void install(RuntimeResourceOwner owner, List<ArtifactRecord> target) {
        var update = owner.createUpdate(target);
        update.prepareAsync().block();
        update.adopt();
        update.closeAsync().block();
    }

    private static ArtifactRecord artifact(Path work, String id, String version, List<String> requires)
        throws Exception {
        return artifact(work, id, version, requires, generatedType(id, "Entrypoint"));
    }

    private static ArtifactRecord artifact(Path work, String id, String version, List<String> requires,
                                           String entrypoint) throws Exception {
        return artifact(work, id, version, requires, entrypoint, version);
    }

    private static ArtifactRecord contractArtifact(Path work, String id, String version,
                                                   List<String> requires) throws Exception {
        return artifact(work, id, version, requires, null, version);
    }

    private static ArtifactRecord artifact(Path work, String id, String version, List<String> requires,
                                           String entrypoint, String payload) throws Exception {
        var root = Files.createDirectories(work.resolve(id + '-' + version));
        var jar = Files.createDirectories(root.resolve("lib")).resolve(id + '-' + version + ".jar");
        var generatedClasses = generatedType(id, "Entrypoint").equals(entrypoint)
            ? compileGeneratedFixture(root.resolve("generated"), id) : null;
        Files.writeString(root.resolve("plugin.properties"), "formatVersion=1\nruntime=java\npayload=lib/"
            + jar.getFileName() + "\n");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/fibra/plugin.yaml"));
            var dependencies = requires.isEmpty() ? "[]" : requires.stream().map(value -> "\n  - id: "
                + value + "\n    version: '*'").collect(java.util.stream.Collectors.joining());
            var entrypointValue = entrypoint == null ? "" : "entrypoint: " + entrypoint + "\n";
            output.write(("id: " + id + "\nversion: " + version + "\n" + entrypointValue
                + "requires: " + dependencies + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
            if (generatedClasses != null) {
                writeClass(output, generatedClasses, generatedType(id, "Entrypoint"));
                writeClass(output, generatedClasses, generatedType(id, "LateLoaded"));
            }
            if (fixture.PreparationEntrypoint.class.getName().equals(entrypoint)) {
                var preparationClass = "fixture/PreparationEntrypoint.class";
                output.putNextEntry(new JarEntry(preparationClass));
                try (var input = fixture.PreparationEntrypoint.class.getResourceAsStream('/' + preparationClass)) {
                    output.write(input.readAllBytes());
                }
                output.closeEntry();
            }
            if ("fixture.ThrowingEntrypoint".equals(entrypoint)) {
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
            output.putNextEntry(new JarEntry("payload/" + id + ".txt"));
            output.write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return ArtifactRecord.builder().id(new ArtifactId(id)).runtimeId(JavaPluginRuntimeAdapter.RUNTIME_ID)
            .version(version).checksum(id + version).revision(version).location(root).state(ArtifactState.INSTALLED)
            .updatedAt(Instant.EPOCH).build();
    }

    private static Path compileClass(Path work, String type) throws IOException {
        return compileClass(work, type, "");
    }

    private static Path compileClass(Path work, String type, String members) throws IOException {
        var separator = type.lastIndexOf('.');
        var packageName = type.substring(0, separator);
        var simpleName = type.substring(separator + 1);
        var source = Files.createDirectories(work.resolve("src")
            .resolve(packageName.replace('.', '/'))).resolve(simpleName + ".java");
        Files.writeString(source, "package " + packageName + "; public final class "
            + simpleName + " { " + members + " }");
        var classes = Files.createDirectories(work.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), source.toString()));
        return classes;
    }

    private static byte[] classBytes(ClassLoader loader, String type) throws IOException {
        try (var input = loader.getResourceAsStream(type.replace('.', '/') + ".class")) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }

    private static void addLibrary(ArtifactRecord artifact, String name, Path classes,
                                   String... types) throws IOException {
        var values = new LinkedHashMap<String, byte[]>();
        for (var type : types) {
            var entry = type.replace('.', '/') + ".class";
            values.put(entry, Files.readAllBytes(classes.resolve(entry)));
        }
        addLibrary(artifact, name, values);
    }

    private static void addLibrary(ArtifactRecord artifact, String name,
                                   Map<String, byte[]> entries) throws IOException {
        writeJar(artifact.location().resolve("lib").resolve(name), null, entries);
    }

    private static void addLibrary(ArtifactRecord artifact, String name, boolean multiRelease,
                                   Map<String, byte[]> entries) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) manifest.getMainAttributes().putValue("Multi-Release", "true");
        writeJar(artifact.location().resolve("lib").resolve(name), manifest, entries);
    }

    private static void copyMainClassToLibrary(ArtifactRecord artifact, String type, String library)
        throws IOException {
        var path = type.replace('.', '/') + ".class";
        var main = mainJar(artifact);
        try (var jar = new JarFile(main.toFile(), true)) {
            try (var input = jar.getInputStream(jar.getJarEntry(path))) {
                addLibrary(artifact, library, Map.of(path, input.readAllBytes()));
            }
        }
    }

    private static void addMainClass(ArtifactRecord artifact, String type, byte[] bytes) throws IOException {
        var main = mainJar(artifact);
        var replacement = Files.createTempFile(artifact.location(), "main-", ".jar");
        try (var jar = new JarFile(main.toFile(), true);
             var output = new JarOutputStream(Files.newOutputStream(replacement))) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                output.putNextEntry(new JarEntry(entry.getName()));
                if (!entry.isDirectory()) {
                    try (var input = jar.getInputStream(entry)) {
                        input.transferTo(output);
                    }
                }
                output.closeEntry();
            }
            output.putNextEntry(new JarEntry(type.replace('.', '/') + ".class"));
            output.write(bytes);
            output.closeEntry();
        }
        Files.move(replacement, main, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path mainJar(ArtifactRecord artifact) {
        return artifact.location().resolve("lib")
            .resolve(artifact.id().value() + '-' + artifact.version() + ".jar");
    }

    private static LinkedHashMap<String, byte[]> entries(Object... values) {
        var result = new LinkedHashMap<String, byte[]>();
        for (var index = 0; index < values.length; index += 2) {
            result.put((String) values[index], (byte[]) values[index + 1]);
        }
        return result;
    }

    private static void writeJar(Path path, Manifest manifest, Map<String, byte[]> entries) throws IOException {
        try (var output = manifest == null
            ? new JarOutputStream(Files.newOutputStream(path))
            : new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private static Path compileGeneratedFixture(Path work, String id) throws IOException {
        var packageName = "fixture.generated";
        var prefix = generatedPrefix(id);
        var source = Files.createDirectories(work.resolve("src/fixture/generated"))
            .resolve(prefix + "Entrypoint.java");
        Files.writeString(source, """
            package %s;
            import com.sstlfsj.fibra.PluginDefinition;
            import com.sstlfsj.fibra.PluginEntrypoint;
            import reactor.core.publisher.Mono;
            public final class %sEntrypoint implements PluginEntrypoint<Void> {
                public PluginDefinition<Void> definition() {
                    return PluginDefinition.builder("%s", Void.class,
                        () -> (context, config) -> Mono.empty()).build();
                }
            }
            final class %sLateLoaded { }
            """.formatted(packageName, prefix, (id + "_____").substring(0, 6), prefix));
        var classes = Files.createDirectories(work.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        assertEquals(0, compiler.run(null, null, null, "-classpath",
            System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()));
        return classes;
    }

    private static void writeClass(JarOutputStream output, Path classes, String type) throws IOException {
        var name = type.replace('.', '/') + ".class";
        output.putNextEntry(new JarEntry(name));
        Files.copy(classes.resolve(name), output);
        output.closeEntry();
    }

    private static String generatedType(String id, String suffix) {
        return "fixture.generated." + generatedPrefix(id) + suffix;
    }

    private static String generatedPrefix(String id) {
        var cleaned = id.replaceAll("[^A-Za-z0-9]", "_");
        return Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
    }
}
