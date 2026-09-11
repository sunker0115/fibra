package com.sstlfsj.fibra.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactStoreTest {
    private static final ArtifactId SAMPLE = new ArtifactId("sample");
    private static final RuntimeId JAVA = new RuntimeId("java");

    @Test
    void actualChannelCloseFailureRemainsVisibleOnRepeatedClose(@TempDir Path work) {
        var ioFailure = new IOException("channel closed but completion failed");
        var closes = new AtomicInteger();
        var io = new ArtifactStore.StorageIo() {
            @Override
            public void force(Path path) { }

            @Override
            public void close(FileChannel channel) throws IOException {
                ArtifactStore.StorageIo.super.close(channel);
                assertFalse(channel.isOpen());
                closes.incrementAndGet();
                throw ioFailure;
            }
        };
        var store = new ArtifactStore(work, io);

        var first = assertThrows(ArtifactException.class, store::close);
        assertSame(ioFailure, first.getCause());
        assertSame(first, assertThrows(ArtifactException.class, store::close));
        assertEquals(1, closes.get());
        assertThrows(IllegalStateException.class, () -> store.history(SAMPLE));
        try (var reopened = new ArtifactStore(work)) {
            assertTrue(reopened.history(SAMPLE).isEmpty());
        }
    }

    @Test
    void stagesThenSavesAnImmutableOpaqueArtifact(@TempDir Path work) throws Exception {
        var source = work.resolve("sample.jar");
        Files.writeString(source, "jar-content");
        var store = new ArtifactStore(work.resolve("store"));

        try (var transaction = store.prepareInstall(SAMPLE, JAVA, "1.0.0", source)) {
            assertEquals(ArtifactState.STAGED, transaction.candidate().state());
            assertTrue(Files.exists(transaction.candidate().location()));
        }
        try (var paths = Files.list(work.resolve("store/objects"))) {
            assertEquals(1, paths.count(), "rollback does not delete a published immutable object");
        }
        assertTrue(store.history(SAMPLE).isEmpty(), "preparation does not save revision metadata");

        ArtifactRecord saved;
        try (var transaction = store.prepareInstall(SAMPLE, JAVA, "1.0.0", source)) {
            saved = transaction.save();
            assertEquals(saved, transaction.save());
        }
        assertEquals(ArtifactState.INSTALLED, saved.state());
        assertEquals(JAVA, saved.runtimeId());
        assertEquals(saved, store.find(SAMPLE, saved.revision()).orElseThrow());
        assertEquals("jar-content", Files.readString(saved.location()));
        assertFalse(Files.exists(recordDirectory(work).resolve("current")));
        assertEquals(List.of(saved), store.history(SAMPLE));
        store.close();
    }

    @Test
    void recoversAbandonedStagingAndRejectsSymbolicLinks(@TempDir Path work)
        throws Exception {
        var source = Files.createDirectory(work.resolve("node-package"));
        Files.writeString(source.resolve("index.mjs"), "export default {};");
        var storeRoot = work.resolve("store");
        var store = new ArtifactStore(storeRoot);
        var prepared = store.prepareInstall(SAMPLE, new RuntimeId("node"), "1.0.0", source).candidate();
        store.close();

        var recovered = new ArtifactStore(storeRoot);
        try (var paths = Files.list(storeRoot.resolve("transactions"))) {
            assertEquals(0, paths.count());
        }
        assertEquals("export default {};", Files.readString(prepared.location().resolve("index.mjs")));

        var external = work.resolve("external.txt");
        Files.writeString(external, "external");
        Files.createSymbolicLink(source.resolve("escape"), external);

        var failure = assertThrows(ArtifactException.class,
            () -> recovered.prepareInstall(SAMPLE, new RuntimeId("node"), "1.0.1", source));
        assertEquals(ArtifactPhase.VALIDATE, failure.phase());
        assertTrue(Files.exists(external));
        recovered.close();
    }

    @Test
    void savesDistinctRevisionsWithoutSelectingAnActiveArtifact(@TempDir Path work)
        throws Exception {
        var first = work.resolve("first.jar");
        var second = work.resolve("second.jar");
        Files.writeString(first, "first");
        Files.writeString(second, "second");
        var store = new ArtifactStore(work.resolve("store"));

        ArtifactRecord original;
        try (var transaction = store.prepareInstall(SAMPLE, JAVA, "1.0.0", first)) {
            original = transaction.save();
        }
        ArtifactRecord replacement;
        try (var transaction = store.prepareInstall(SAMPLE, JAVA, "2.0.0", second)) {
            replacement = transaction.save();
            transaction.rollback();
        }

        assertEquals(original, store.find(SAMPLE, original.revision()).orElseThrow());
        assertEquals(replacement, store.find(SAMPLE, replacement.revision()).orElseThrow());
        assertEquals("first", Files.readString(original.location()));
        assertEquals("second", Files.readString(replacement.location()));
        assertFalse(Files.exists(recordDirectory(work).resolve("current")));
        assertEquals(2, store.history(SAMPLE).size());
        store.close();
    }

    @Test
    void rejectsDuplicateRevisionWhenItsSavedContentNoLongerMatchesMetadata(@TempDir Path work)
        throws Exception {
        var source = work.resolve("sample.jar");
        Files.writeString(source, "immutable");
        var store = new ArtifactStore(work.resolve("store"));
        ArtifactRecord saved;
        try (var transaction = store.prepareInstall(SAMPLE, JAVA, "1.0.0", source)) {
            saved = transaction.save();
        }
        Files.writeString(saved.location(), "tampered");

        var failure = assertThrows(ArtifactException.class,
            () -> store.prepareInstall(SAMPLE, JAVA, "1.0.0", source));
        assertEquals(ArtifactPhase.COMMIT, failure.phase());
        assertEquals("tampered", Files.readString(saved.location()),
            "preparation must reject an invalid existing object without replacing it");
        assertCorruptRecord(() -> store.find(SAMPLE, saved.revision()));
        store.close();
    }

    @Test
    void rejectsTraversalAndCorruptedStoredMetadata(@TempDir Path work) throws Exception {
        var storeRoot = work.resolve("store");
        var store = new ArtifactStore(storeRoot);
        var first = save(store, work, "first", "1.0.0");
        var second = save(store, work, "second", "2.0.0");
        var third = save(store, work, "third", "3.0.0");

        assertThrows(IllegalArgumentException.class, () -> store.find(SAMPLE, "../escape"));

        rewriteRecord(recordPath(storeRoot, first), values -> values.setProperty("id", "other"));
        assertCorruptRecord(() -> store.find(SAMPLE, first.revision()));

        rewriteRecord(recordPath(storeRoot, second),
            values -> values.setProperty("revision", first.revision()));
        assertCorruptRecord(() -> store.find(SAMPLE, second.revision()));

        rewriteRecord(recordPath(storeRoot, third),
            values -> values.setProperty("location", "../../outside"));
        assertCorruptRecord(() -> store.find(SAMPLE, third.revision()));
        store.close();
    }

    @Test
    void preservesAnInvalidRecoveryJournalForInvestigation(@TempDir Path work) throws Exception {
        var storeRoot = work.resolve("store");
        var store = new ArtifactStore(storeRoot);
        store.close();
        var transaction = Files.createDirectory(storeRoot.resolve("transactions/abandoned"));
        var journal = transaction.resolve("transaction.properties");
        var values = new Properties();
        values.setProperty("id", SAMPLE.value());
        values.setProperty("checksum", "../../outside");
        values.setProperty("revision", "../../outside");
        try (OutputStream output = Files.newOutputStream(journal)) {
            values.store(output, null);
        }

        var failure = assertThrows(ArtifactException.class, () -> new ArtifactStore(storeRoot));
        assertEquals(ArtifactPhase.RECOVER, failure.phase());
        assertTrue(Files.exists(journal));
    }

    @Test
    void forcesFinalContentAndRecordDirectoryBeforeSavingMetadata(@TempDir Path work)
        throws Exception {
        var source = Files.createDirectory(work.resolve("artifact"));
        Files.createDirectories(source.resolve("nested"));
        Files.writeString(source.resolve("nested/plugin.jar"), "plugin");
        var forced = new ArrayList<Path>();
        var store = new ArtifactStore(work.resolve("store"), forced::add);

        ArtifactRecord saved;
        try (var transaction = store.prepareInstall(SAMPLE, JAVA, "1.0.0", source)) {
            saved = transaction.save();
        }

        var recordDirectory = recordDirectory(work);
        assertTrue(forced.contains(saved.location()));
        assertTrue(forced.contains(saved.location().resolve("nested/plugin.jar")));
        assertTrue(forced.contains(saved.location().getParent()));
        assertTrue(forced.contains(recordDirectory.resolve(saved.revision() + ".properties")));
        assertTrue(forced.contains(recordDirectory));
        store.close();
    }

    @Test
    void provesAnExistingStoreRootsAncestorEntriesDurable(@TempDir Path work) throws Exception {
        var storeRoot = Files.createDirectories(work.resolve("preexisting/root"));
        var forced = new ArrayList<Path>();
        try (var store = new ArtifactStore(storeRoot, forced::add)) {
            for (var directory = storeRoot; directory != null; directory = directory.getParent()) {
                assertTrue(forced.contains(directory), "unproven directory durability: " + directory);
            }
        }
    }

    @Test
    void doesNotSaveMetadataWhenForcingFinalContentFails(@TempDir Path work)
        throws Exception {
        var source = work.resolve("sample.jar");
        Files.writeString(source, "immutable");
        var store = new ArtifactStore(work.resolve("store"), path -> {
            if (path.getFileName() != null && path.getFileName().toString().equals("content")) {
                throw new IOException("injected force failure");
            }
        });

        try (var transaction = store.prepareInstall(SAMPLE, JAVA, "1.0.0", source)) {
            var failure = assertThrows(ArtifactException.class, transaction::save);
            assertEquals(ArtifactPhase.COMMIT, failure.phase());
        }
        assertTrue(store.history(SAMPLE).isEmpty());
        store.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"runtime", "version", "checksum"})
    void rejectsMetadataThatDoesNotRecomputeToItsRevision(String field, @TempDir Path work)
        throws Exception {
        var storeRoot = work.resolve("store");
        try (var store = new ArtifactStore(storeRoot)) {
            var first = save(store, work, "first", "1.0.0");
            var second = save(store, work, "second", "2.0.0");
            rewriteRecord(recordPath(storeRoot, first), values -> {
                switch (field) {
                    case "runtime" -> values.setProperty("runtime", "node");
                    case "version" -> values.setProperty("version", "changed");
                    case "checksum" -> {
                        values.setProperty("checksum", second.checksum());
                        values.setProperty("location", second.location().toString());
                    }
                    default -> throw new AssertionError(field);
                }
            });
            assertCorruptRecord(() -> store.find(SAMPLE, first.revision()));
            assertCorruptRecord(() -> store.history(SAMPLE));
        }
    }

    @Test
    void rollingBackOnePreparationDoesNotDeleteAnotherCandidatesSharedObject(@TempDir Path work)
        throws Exception {
        var source = work.resolve("shared.jar");
        Files.writeString(source, "shared");
        try (var store = new ArtifactStore(work.resolve("store"));
             var first = store.prepareInstall(SAMPLE, JAVA, "1.0.0", source);
             var second = store.prepareInstall(new ArtifactId("other"), JAVA, "2.0.0", source)) {
            var location = second.candidate().location();
            assertEquals(first.candidate().location(), location);
            first.rollback();
            assertEquals("shared", Files.readString(location));
            assertEquals(location, second.save().location());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void copyFailureNeverLeavesPartialContentInTheObjectStore(int failedCopy, @TempDir Path work)
        throws Exception {
        var source = Files.createDirectory(work.resolve("source"));
        Files.writeString(source.resolve("first.txt"), "first");
        Files.writeString(source.resolve("second.txt"), "second");
        var copies = new AtomicInteger();
        var storeRoot = work.resolve("store");
        var io = new ArtifactStore.StorageIo() {
            @Override
            public void force(Path path) { }

            @Override
            public void copy(Path from, Path target) throws IOException {
                if (copies.incrementAndGet() == failedCopy) {
                    Files.createDirectories(target);
                    Files.copy(from.resolve("first.txt"), target.resolve("first.txt"));
                    throw new IOException("injected partial copy");
                }
                ArtifactStore.StorageIo.super.copy(from, target);
            }
        };
        try (var store = new ArtifactStore(storeRoot, io)) {
            try (var transaction = store.prepareInstall(SAMPLE, JAVA, "1.0.0", source)) {
                assertEquals("second", Files.readString(transaction.candidate().location().resolve("second.txt")));
            } catch (ArtifactException failure) {
                assertEquals(ArtifactPhase.STAGE, failure.phase());
            }
            try (var objects = Files.list(storeRoot.resolve("objects"))) {
                for (var object : objects.toList()) {
                    assertTrue(Files.isRegularFile(object.resolve("content/second.txt")),
                        "every published object must contain the complete source, even after a copy failure");
                }
            }
            try (var transactions = Files.list(storeRoot.resolve("transactions"))) {
                assertEquals(0, transactions.count());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"save", "rollback", "close"})
    void closedStoreRejectsPreviouslyPreparedMutationHandles(String operation, @TempDir Path work)
        throws Exception {
        var source = work.resolve("source.jar");
        Files.writeString(source, "source");
        var store = new ArtifactStore(work.resolve("store"));
        var transaction = store.prepareInstall(SAMPLE, JAVA, "1.0.0", source);
        store.close();
        assertThrows(IllegalStateException.class, () -> {
            switch (operation) {
                case "save" -> transaction.save();
                case "rollback" -> transaction.rollback();
                case "close" -> transaction.close();
                default -> throw new AssertionError(operation);
            }
        });
    }

    @Test
    void closeWaitsForAnAdmittedPreparationBeforeReleasingStoreOwnership(@TempDir Path work)
        throws Exception {
        var source = work.resolve("source.jar");
        Files.writeString(source, "source");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closingStarted = new CountDownLatch(1);
        var io = new ArtifactStore.StorageIo() {
            @Override
            public void force(Path path) { }

            @Override
            public void copy(Path from, Path target) throws IOException {
                entered.countDown();
                await(release);
                ArtifactStore.StorageIo.super.copy(from, target);
            }
        };
        var storeRoot = work.resolve("store");
        try (var store = new ArtifactStore(storeRoot, io);
             var executor = Executors.newFixedThreadPool(2)) {
            var preparing = executor.submit(() -> store.prepareInstall(SAMPLE, JAVA, "1.0.0", source));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var closing = executor.submit(() -> {
                    closingStarted.countDown();
                    store.close();
                });
                assertTrue(closingStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> closing.get(200, TimeUnit.MILLISECONDS));
                assertThrows(ArtifactException.class, () -> new ArtifactStore(storeRoot));
                release.countDown();
                preparing.get(5, TimeUnit.SECONDS);
                closing.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IOException("test gate timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(exception);
        }
    }

    private static ArtifactRecord save(ArtifactStore store, Path work, String content,
                                       String version) throws IOException {
        var source = work.resolve(version + ".jar");
        Files.writeString(source, content);
        try (var transaction = store.prepareInstall(SAMPLE, JAVA, version, source)) {
            return transaction.save();
        }
    }

    private static Path recordDirectory(Path work) {
        return work.resolve("store/records/73616d706c65");
    }

    private static Path recordPath(Path storeRoot, ArtifactRecord record) {
        return storeRoot.resolve("records/73616d706c65")
            .resolve(record.revision() + ".properties");
    }

    private static void rewriteRecord(Path path, java.util.function.Consumer<Properties> change)
        throws IOException {
        var values = new Properties();
        try (var input = Files.newInputStream(path)) {
            values.load(input);
        }
        change.accept(values);
        try (var output = Files.newOutputStream(path)) {
            values.store(output, null);
        }
    }

    private static void assertCorruptRecord(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(ArtifactException.class, action);
        assertEquals(ArtifactPhase.RECOVER, failure.phase());
    }
}
