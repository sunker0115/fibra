package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.engine.TargetSaveState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilePluginAuditRepositoryTest {
    @Test
    void shortWritesAreRetriedUntilTheCompleteRecordIsStored(@TempDir Path work) {
        var file = work.resolve("audit.log");
        var io = new CappedWriteIo();
        try (var audit = new FilePluginAuditRepository(file, io)) {
            append(audit);
            assertTrue(io.writes > 1);
        }

        try (var reopened = new FilePluginAuditRepository(file)) {
            assertEquals(1, reopened.history().size());
        }
    }

    @Test
    void forceFailurePreventsLaterAppendAndKeepsTheOwnerUntilClose(@TempDir Path work)
        throws Exception {
        var file = work.resolve("audit.log");
        var io = new ForceFailsAfterWritingIo();
        var audit = new FilePluginAuditRepository(file, io);
        try {
            assertThrows(IllegalStateException.class, () -> append(audit));
            var afterFailure = Files.readAllBytes(file);

            assertThrows(IllegalStateException.class, () -> new FilePluginAuditRepository(file));
            assertThrows(IllegalStateException.class, () -> append(audit));
            assertEquals(1, io.writes);
            assertArrayEquals(afterFailure, Files.readAllBytes(file));
        } finally {
            audit.close();
        }

        try (var reopened = new FilePluginAuditRepository(file)) {
            assertEquals(1, reopened.history().size());
            append(reopened);
            assertEquals(2, reopened.history().getLast().sequence());
        }
        try (var reopenedAgain = new FilePluginAuditRepository(file)) {
            assertEquals(2, reopenedAgain.history().size());
            assertEquals(2, reopenedAgain.history().getLast().sequence());
        }
    }

    @Test
    void partialWriteFailureLeavesTheEvidenceAndRejectsFurtherAppend(@TempDir Path work)
        throws Exception {
        var file = work.resolve("audit.log");
        var io = new PartialWriteThenFailIo();
        var audit = new FilePluginAuditRepository(file, io);
        byte[] partialRecord = null;
        try {
            assertThrows(IllegalStateException.class, () -> append(audit));
            partialRecord = Files.readAllBytes(file);

            assertThrows(IllegalStateException.class, () -> append(audit));
            assertEquals(1, io.writes);
            assertArrayEquals(partialRecord, Files.readAllBytes(file));
        } finally {
            audit.close();
        }

        assertThrows(IllegalStateException.class, () -> new FilePluginAuditRepository(file));
        assertArrayEquals(partialRecord, Files.readAllBytes(file));
    }

    @Test
    void rejectsAnInvalidSucceededBoolean(@TempDir Path work) throws Exception {
        var file = work.resolve("audit.log");
        Files.writeString(file, "1\t2026-09-11T00:00:00Z\taW5zdGFsbA\tc2FtcGxl\tinvalid\tSAVED\tMQ\tYWNjZXB0ZWQ\n");

        assertThrows(IllegalStateException.class, () -> new FilePluginAuditRepository(file));
    }

    @Test
    void rejectsACompleteRecordWithoutItsTerminalLineFeed(@TempDir Path work)
        throws Exception {
        var file = work.resolve("audit.log");
        var record = "1\t2026-09-11T00:00:00Z\taW5zdGFsbA\tc2FtcGxl\ttrue\tSAVED\tMQ\tYWNjZXB0ZWQ";
        Files.writeString(file, record);

        assertThrows(IllegalStateException.class, () -> new FilePluginAuditRepository(file));
        assertEquals(record, Files.readString(file));
    }

    @Test
    void closeFailureIsRememberedAfterTheChannelReleasesTheFileOwner(@TempDir Path work) {
        var file = work.resolve("audit.log");
        var io = new CloseAfterChannelCloseIo();
        var audit = new FilePluginAuditRepository(file, io);

        var firstFailure = assertThrows(IllegalStateException.class, audit::close);
        assertEquals(1, io.closes);
        var repeatedFailure = assertThrows(IllegalStateException.class, audit::close);
        assertSame(firstFailure.getCause(), repeatedFailure.getCause());
        assertEquals(1, io.closes);
        assertThrows(IllegalStateException.class, () -> append(audit));

        try (var reopened = new FilePluginAuditRepository(file)) {
            assertTrue(reopened.history().isEmpty());
        }
    }

    @Test
    void successfulAuditEntryRequiresASavedTargetInEveryConstructionPath(@TempDir Path work)
        throws Exception {
        for (var state : List.of(TargetSaveState.NOT_SAVED, TargetSaveState.UNCONFIRMED)) {
            assertThrows(IllegalArgumentException.class, () -> PluginAuditEntry.builder().sequence(1)
                .timestamp(Instant.parse("2026-09-11T00:00:00Z")).operation("install").target("sample")
                .succeeded(true).targetSaveState(state).viewRevision("1").detail("accepted").build());

            var file = work.resolve(state.name().toLowerCase() + ".log");
            try (var audit = new FilePluginAuditRepository(file)) {
                assertThrows(IllegalArgumentException.class, () -> audit.append("install",
                    "sample", true, state, "1", "accepted"));
            }

            Files.writeString(file, "1\t2026-09-11T00:00:00Z\taW5zdGFsbA\tc2FtcGxl\ttrue\t"
                + state + "\tMQ\tYWNjZXB0ZWQ\n");
            assertThrows(IllegalStateException.class, () -> new FilePluginAuditRepository(file));
        }
    }

    @Test
    void failedAuditMayRecordASavedTarget(@TempDir Path work) {
        var file = work.resolve("audit.log");
        try (var audit = new FilePluginAuditRepository(file)) {
            audit.append("install", "sample", false, TargetSaveState.SAVED, "1", "failed");
        }

        try (var reopened = new FilePluginAuditRepository(file)) {
            assertFalse(reopened.history().getFirst().succeeded());
            assertEquals(TargetSaveState.SAVED, reopened.history().getFirst().targetSaveState());
        }
    }

    @Test
    void appendAfterCloseDoesNotInvokeIo(@TempDir Path work) {
        var io = new CountingIo();
        var audit = new FilePluginAuditRepository(work.resolve("audit.log"), io);
        audit.close();

        assertThrows(IllegalStateException.class, () -> append(audit));
        assertEquals(0, io.writes);
    }

    private static void append(FilePluginAuditRepository audit) {
        audit.append("install", "sample", true, TargetSaveState.SAVED, "1", "accepted");
    }

    private static final class CappedWriteIo implements FilePluginAuditIo {
        private int writes;

        @Override
        public int write(FileChannel channel, ByteBuffer bytes) throws IOException {
            writes++;
            var limit = bytes.limit();
            bytes.limit(Math.min(bytes.position() + 3, limit));
            try {
                return channel.write(bytes);
            } finally {
                bytes.limit(limit);
            }
        }

        @Override
        public void force(FileChannel channel, boolean metaData) throws IOException {
            channel.force(metaData);
        }
    }

    private static final class ForceFailsAfterWritingIo implements FilePluginAuditIo {
        private int writes;
        private boolean fail = true;

        @Override
        public int write(FileChannel channel, ByteBuffer bytes) throws IOException {
            writes++;
            return channel.write(bytes);
        }

        @Override
        public void force(FileChannel channel, boolean metaData) throws IOException {
            channel.force(metaData);
            if (fail) {
                fail = false;
                throw new IOException("injected force failure");
            }
        }
    }

    private static final class PartialWriteThenFailIo implements FilePluginAuditIo {
        private int writes;

        @Override
        public int write(FileChannel channel, ByteBuffer bytes) throws IOException {
            writes++;
            if (writes == 1) {
                var limit = bytes.limit();
                bytes.limit(Math.min(bytes.position() + 3, limit));
                try {
                    channel.write(bytes);
                } finally {
                    bytes.limit(limit);
                }
                throw new IOException("injected partial write failure");
            }
            return channel.write(bytes);
        }

        @Override
        public void force(FileChannel channel, boolean metaData) throws IOException {
            channel.force(metaData);
        }
    }

    private static final class CountingIo implements FilePluginAuditIo {
        private int writes;

        @Override
        public int write(FileChannel channel, ByteBuffer bytes) {
            writes++;
            return bytes.remaining();
        }

        @Override
        public void force(FileChannel channel, boolean metaData) {
        }
    }

    private static final class CloseAfterChannelCloseIo implements FilePluginAuditIo {
        private int closes;

        @Override
        public int write(FileChannel channel, ByteBuffer bytes) throws IOException {
            return channel.write(bytes);
        }

        @Override
        public void force(FileChannel channel, boolean metaData) throws IOException {
            channel.force(metaData);
        }

        @Override
        public void close(FileChannel channel) throws IOException {
            closes++;
            channel.close();
            throw new IOException("injected close failure after channel close");
        }
    }
}
