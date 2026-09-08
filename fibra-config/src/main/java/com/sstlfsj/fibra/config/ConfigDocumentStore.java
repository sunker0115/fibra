package com.sstlfsj.fibra.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public final class ConfigDocumentStore {
    private final Path path;
    private final ConfigDocumentReader reader;
    private final ReentrantLock lock = new ReentrantLock();

    public ConfigDocumentStore(Path path, ConfigLimits limits) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        reader = new ConfigDocumentReader(limits);
    }

    public ConfigDocumentSnapshot read() {
        lock.lock();
        try {
            return readUnlocked();
        } finally {
            lock.unlock();
        }
    }

    public ConfigWriteTransaction prepareReplace(String expectedRevision, byte[] content) {
        if (expectedRevision == null || expectedRevision.isBlank()) {
            throw new IllegalArgumentException("expectedRevision must not be blank");
        }
        Objects.requireNonNull(content, "content");
        lock.lock();
        Path staged = null;
        try {
            var current = readUnlocked();
            if (!current.revision().equals(expectedRevision)) {
                throw error("REVISION_CONFLICT",
                    "expected revision " + expectedRevision + " but found "
                        + current.revision(), null);
            }
            reader.read(path, content, null);
            var parent = path.getParent();
            staged = Files.createTempFile(parent,
                "." + path.getFileName() + ".fibra-txn-", ".tmp");
            try (var channel = FileChannel.open(staged, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
                var buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            return new Transaction(expectedRevision, staged,
                new ConfigDocumentSnapshot(path, revision(content), content));
        } catch (ConfigException exception) {
            deleteQuietly(staged);
            lock.unlock();
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(staged);
            lock.unlock();
            throw error("STAGE_FAILED", "cannot stage config replacement", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(staged);
            lock.unlock();
            throw exception;
        }
    }

    private ConfigDocumentSnapshot readUnlocked() {
        var document = reader.read(path, null);
        var content = document.bytes();
        return new ConfigDocumentSnapshot(document.path(), revision(content), content);
    }

    private final class Transaction implements ConfigWriteTransaction {
        private final String expectedRevision;
        private final Path staged;
        private final ConfigDocumentSnapshot candidate;
        private State state = State.PREPARED;

        private Transaction(String expectedRevision, Path staged,
                            ConfigDocumentSnapshot candidate) {
            this.expectedRevision = expectedRevision;
            this.staged = staged;
            this.candidate = candidate;
        }

        @Override
        public ConfigDocumentSnapshot candidate() {
            return candidate;
        }

        @Override
        public void commit() {
            if (state == State.COMMITTED) {
                return;
            }
            if (state == State.ROLLED_BACK) {
                throw new IllegalStateException("config transaction is rolled back");
            }
            try {
                var actualRevision = readUnlocked().revision();
                if (!actualRevision.equals(expectedRevision)) {
                    throw error("REVISION_CONFLICT",
                        "config changed after prepare", null);
                }
                try {
                    Files.move(staged, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    throw error("ATOMIC_MOVE_UNSUPPORTED",
                        "filesystem does not support atomic config replacement", exception);
                }
                forceDirectory(path.getParent());
                state = State.COMMITTED;
            } catch (IOException exception) {
                throw error("COMMIT_FAILED", "cannot commit config replacement", exception);
            } finally {
                if (state != State.PREPARED) {
                    lock.unlock();
                }
            }
        }

        @Override
        public void rollback() {
            if (state != State.PREPARED) {
                return;
            }
            try {
                Files.deleteIfExists(staged);
                state = State.ROLLED_BACK;
            } catch (IOException exception) {
                state = State.ROLLED_BACK;
                throw error("ROLLBACK_FAILED", "cannot remove staged config", exception);
            } finally {
                lock.unlock();
            }
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static String revision(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A failed prepare still reports the primary failure. Startup recovery may remove it.
        }
    }

    private ConfigException error(String code, String message, Throwable cause) {
        return new ConfigException(new ConfigDiagnostic(
            ConfigStage.WRITE, code, message, path, null), cause);
    }

    private enum State {
        PREPARED,
        COMMITTED,
        ROLLED_BACK
    }
}
