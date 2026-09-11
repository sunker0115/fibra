package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileEngineStateStoreTest {
    @Test
    void actualChannelCloseFailureRemainsVisibleOnRepeatedClose(@TempDir Path work) {
        var io = new RecordingIo();
        io.closeFailure = new IOException("channel closed but completion failed");
        var store = new FileEngineStateStore(work, io);

        var first = assertThrows(EngineStateStoreException.class, store::close);
        assertSame(io.closeFailure, first.getCause());
        assertSame(first, assertThrows(EngineStateStoreException.class, store::close));
        assertEquals(1, io.closes);
        assertThrows(IllegalStateException.class, store::load);
        assertThrows(IllegalStateException.class, () -> store.save(target("forbidden")));
        try (var reopened = new FileEngineStateStore(work)) {
            assertTrue(reopened.load().isEmpty());
        }
    }

    @Test
    void reopensOneCompleteTargetAndRejectsConcurrentOwnership(@TempDir Path work) throws Exception {
        var first = target("first");
        var next = target("next");
        try (var store = new FileEngineStateStore(work)) {
            assertTrue(store.load().isEmpty());
            assertThrows(EngineStateStoreException.class, () -> new FileEngineStateStore(work));
            store.save(first);
            store.save(next);
            assertEquals(next, store.load().orElseThrow());
            try (var files = Files.list(work)) {
                assertEquals(List.of("state.lock", "target.json"), files.map(path -> path.getFileName().toString())
                    .sorted().toList());
            }
        }
        try (var reopened = new FileEngineStateStore(work)) {
            assertEquals(next, reopened.load().orElseThrow());
        }
    }

    @Test
    void forcesNewDirectoryChainAndTheTargetInOrder(@TempDir Path work) {
        var io = new RecordingIo();
        var root = work.resolve("parent").resolve("state");
        try (var store = new FileEngineStateStore(root, io)) {
            assertTrue(io.forced.contains(work));
            assertTrue(io.forced.contains(root.getParent()));
            io.operations.clear();
            store.save(target("value"));
            assertEquals(List.of("force-file", "replace", "force-directory"), io.operations);
        }
    }

    @Test
    void existingDirectoriesStillNeedTheirParentEntriesSynced(@TempDir Path work) throws Exception {
        var root = Files.createDirectories(work.resolve("parent/state"));
        var io = new RecordingIo();
        try (var store = new FileEngineStateStore(root, io)) {
            for (var directory = root; directory != null; directory = directory.getParent()) {
                assertTrue(io.forced.contains(directory), "unsynced directory: " + directory);
            }
            store.save(target("value"));
        }
    }

    @Test
    void failureBeforeReplacementKeepsThePreviousTarget(@TempDir Path work) {
        var io = new RecordingIo();
        try (var store = new FileEngineStateStore(work, io)) {
            var previous = target("previous");
            store.save(previous);
            io.failFileForce = true;
            var failure = assertThrows(EngineStateStoreException.class, () -> store.save(target("next")));
            assertEquals(work.resolve("target.json"), failure.path());
            assertEquals(previous, store.load().orElseThrow());
        }
    }

    @Test
    void failedDirectorySyncReportsUnconfirmedWithoutRestoringOldTarget(@TempDir Path work) {
        var io = new RecordingIo();
        try (var store = new FileEngineStateStore(work, io)) {
            store.save(target("previous"));
            io.failDirectoryForce = true;
            var next = target("next");
            var failure = assertThrows(EngineStateStore.SaveUnconfirmedException.class, () -> store.save(next));
            assertEquals(work.resolve("target.json"), failure.path());
            assertEquals(next, store.load().orElseThrow());
        }
    }

    @Test
    void replacementThatHappenedBeforeAnIoFailureIsAlsoUnconfirmed(@TempDir Path work) {
        var io = new RecordingIo();
        try (var store = new FileEngineStateStore(work, io)) {
            store.save(target("previous"));
            io.failAfterReplace = true;
            var next = target("next");
            assertThrows(EngineStateStore.SaveUnconfirmedException.class, () -> store.save(next));
            assertEquals(next, store.load().orElseThrow());
        }
    }

    @Test
    void replacementIoFailureDoesNotClaimThatTheTargetWasUntouched(@TempDir Path work) {
        var io = new RecordingIo();
        try (var store = new FileEngineStateStore(work, io)) {
            var previous = target("previous");
            store.save(previous);
            io.failBeforeReplace = true;
            assertThrows(EngineStateStore.SaveUnconfirmedException.class, () -> store.save(target("next")));
            assertEquals(previous, store.load().orElseThrow());
        }
    }

    @Test
    void corruptionIsRejectedRatherThanTreatedAsAnEmptyStore(@TempDir Path work) throws Exception {
        try (var store = new FileEngineStateStore(work)) {
            store.save(target("first"));
            var path = work.resolve("target.json");
            var bytes = Files.readString(path);
            Files.writeString(path, bytes.replace("first", "other"));
            assertThrows(EngineStateStoreException.class, store::load);
            Files.writeString(path, "{");
            assertThrows(EngineStateStoreException.class, store::load);
        }
    }

    @Test
    void envelopeRejectsUnknownAndDuplicateFields(@TempDir Path work) throws Exception {
        try (var store = new FileEngineStateStore(work)) {
            var manifest = target("first");
            store.save(manifest);
            var path = work.resolve("target.json");
            var bytes = Files.readString(path);
            Files.writeString(path, "{\"unknown\":1," + bytes.substring(1));
            assertThrows(EngineStateStoreException.class, store::load);
            Files.writeString(path, "{\"revision\":\"" + manifest.revision() + "\"," + bytes.substring(1));
            assertThrows(EngineStateStoreException.class, store::load);
        }
    }

    @Test
    void closedStoreRejectsReadsAndWrites(@TempDir Path work) {
        var store = new FileEngineStateStore(work);
        store.close();
        store.close();
        assertThrows(IllegalStateException.class, store::load);
        assertThrows(IllegalStateException.class, () -> store.save(target("value")));
    }

    @Test
    void correctManifestDigestDoesNotPermitNonCanonicalOrTrailingData(@TempDir Path work) throws Exception {
        try (var store = new FileEngineStateStore(work)) {
            var manifest = target("first");
            store.save(manifest);
            var path = work.resolve("target.json");
            var bytes = Files.readString(path);
            Files.writeString(path, "{ " + bytes.substring(1));
            assertThrows(EngineStateStoreException.class, store::load);
            Files.writeString(path, bytes + "{}");
            assertThrows(EngineStateStoreException.class, store::load);
        }
    }

    private static DeploymentManifest target(String value) {
        return new DeploymentManifest(Map.of(new ArtifactId("artifact"), "a".repeat(64)),
            new DesiredInputGraph(List.of(DesiredInputEntry.builder("p", "definition")
                .enabled(false).config(LiteralValue.of(value)).build())));
    }

    private static final class RecordingIo implements FileEngineStateStore.StorageIo {
        private final List<Path> forced = new ArrayList<>();
        private final List<String> operations = new ArrayList<>();
        private boolean failFileForce;
        private boolean failDirectoryForce;
        private boolean failBeforeReplace;
        private boolean failAfterReplace;
        private IOException closeFailure;
        private int closes;

        @Override
        public void force(Path path) throws IOException {
            var directory = Files.isDirectory(path);
            operations.add(directory ? "force-directory" : "force-file");
            forced.add(path);
            if (directory ? failDirectoryForce : failFileForce) throw new IOException("force failed");
            try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }

        @Override
        public void replace(Path staged, Path target) throws IOException {
            operations.add("replace");
            if (failBeforeReplace) throw new IOException("replacement outcome is unknown");
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (failAfterReplace) throw new IOException("replacement outcome was not confirmed");
        }

        @Override
        public void close(FileChannel channel) throws IOException {
            FileEngineStateStore.StorageIo.super.close(channel);
            assertFalse(channel.isOpen());
            closes++;
            if (closeFailure != null) throw closeFailure;
        }
    }
}
