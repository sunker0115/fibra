package com.sstlfsj.fibra.engine;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/** 持有目录锁的单写者存储，原子替换和父目录 fsync 确认完整目标。 */
public final class FileDeploymentTargetStore implements DeploymentTargetStore {
    private static final StorageIo FILES = new StorageIo() {
        public void force(Path path) throws IOException {
            try (var channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
        }
        public void replace(Path staged, Path target) throws IOException {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    };
    private final Path root;
    private final Path target;
    private final StorageIo io;
    private final FileChannel ownership;
    private boolean closed;
    private boolean uncertain;
    private DeploymentTargetStoreException closeFailure;

    public FileDeploymentTargetStore(Path root) { this(root, FILES); }

    FileDeploymentTargetStore(Path root, StorageIo io) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        target = this.root.resolve("target.json");
        this.io = Objects.requireNonNull(io, "io");
        FileChannel channel = null;
        FileLock lock = null;
        try {
            Files.createDirectories(this.root);
            for (var directory = this.root; directory != null; directory = directory.getParent()) io.force(directory);
            channel = FileChannel.open(this.root.resolve("state.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) throw new IOException("deployment target store is already owned");
            io.force(this.root);
        } catch (IOException | RuntimeException failure) {
            closeOnFailure(lock, failure);
            closeOnFailure(channel, failure);
            throw new DeploymentTargetStoreException("cannot open deployment target store", this.root, failure);
        }
        ownership = channel;
    }

    @Override public synchronized Optional<StoredTarget> load() {
        ensureOpen();
        if (Files.notExists(target)) return Optional.empty();
        try {
            return Optional.of(StoredTarget.confirmed(DeploymentTargetCodec.decode(Files.readAllBytes(target))));
        } catch (IOException | RuntimeException failure) {
            throw new DeploymentTargetStoreException("cannot load deployment target", target, failure);
        }
    }

    @Override public synchronized DurableTargetToken save(long expectedRevision, DeploymentTarget value) {
        ensureOpen();
        DeploymentTargetStore.checkRevision(expectedRevision,
            load().map(stored -> stored.target().targetRevision()).orElse(0L), value);
        var bytes = DeploymentTargetCodec.encode(value);
        if (!value.equals(DeploymentTargetCodec.decode(bytes))) {
            throw new IllegalArgumentException("deployment target cannot round-trip through storage");
        }
        Path staged = null;
        boolean replacementAttempted = false;
        try {
            staged = Files.createTempFile(root, ".target-", ".tmp");
            Files.write(staged, bytes);
            io.force(staged);
            replacementAttempted = true;
            io.replace(staged, target);
            staged = null;
            io.force(root);
            return DurableTargetToken.issue(value.targetRevision(), value.targetDigest());
        } catch (IOException | RuntimeException failure) {
            if (staged != null) {
                try { Files.deleteIfExists(staged); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            if (replacementAttempted) {
                uncertain = true;
                throw new SaveUnconfirmedException(target, failure);
            }
            throw new DeploymentTargetStoreException("cannot save deployment target", target, failure);
        }
    }

    @Override public synchronized void close() {
        if (closeFailure != null) throw closeFailure;
        if (closed) return;
        closed = true;
        try { io.close(ownership); }
        catch (IOException failure) {
            closeFailure = new DeploymentTargetStoreException("cannot close deployment target store", root, failure);
            throw closeFailure;
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("deployment target store is closed");
        if (uncertain) throw new IllegalStateException("deployment target save is uncertain; reopen the store");
    }
    private static void closeOnFailure(AutoCloseable value, Throwable failure) {
        if (value == null) return;
        try { value.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
    }
    interface StorageIo {
        void force(Path path) throws IOException;
        void replace(Path staged, Path target) throws IOException;
        default void close(FileChannel channel) throws IOException { channel.close(); }
    }
}
