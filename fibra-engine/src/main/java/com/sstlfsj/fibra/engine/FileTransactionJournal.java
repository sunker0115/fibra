package com.sstlfsj.fibra.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

public final class FileTransactionJournal implements TransactionJournal {
    private final Path root;
    private final Path records;
    private final FileChannel ownershipChannel;
    private final FileLock ownershipLock;
    private long nextSequence;
    private boolean closed;

    public FileTransactionJournal(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        records = this.root.resolve("records");
        FileChannel openedChannel = null;
        FileLock acquiredLock = null;
        try {
            Files.createDirectories(records);
            openedChannel = FileChannel.open(this.root.resolve("journal.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            acquiredLock = openedChannel.tryLock();
            if (acquiredLock == null) {
                throw failure("transaction journal is already owned", this.root, null);
            }
            ownershipChannel = openedChannel;
            ownershipLock = acquiredLock;
            nextSequence = findNextSequence();
            records();
        } catch (IOException | OverlappingFileLockException exception) {
            closeQuietly(acquiredLock);
            closeQuietly(openedChannel);
            throw failure("cannot initialize transaction journal", this.root, exception);
        } catch (RuntimeException exception) {
            closeQuietly(acquiredLock);
            closeQuietly(openedChannel);
            throw exception;
        }
    }

    @Override
    public synchronized void append(TransactionRecord record) {
        ensureOpen();
        Objects.requireNonNull(record, "record");
        var target = records.resolve(String.format("%020d.properties", nextSequence));
        Path staged = null;
        try {
            staged = Files.createTempFile(records, ".record-", ".tmp");
            var values = new Properties();
            values.setProperty("transactionId", record.transactionId());
            values.setProperty("state", record.state().name());
            values.setProperty("participants", String.join("\u001f", record.participants()));
            if (record.detail() != null) {
                values.setProperty("detail", record.detail());
            }
            values.setProperty("recordedAt", record.recordedAt().toString());
            try (OutputStream output = Files.newOutputStream(staged)) {
                values.store(output, null);
            }
            force(staged);
            atomicMove(staged, target);
            force(records);
            nextSequence++;
        } catch (IOException exception) {
            throw failure("cannot append transaction record", target, exception);
        } finally {
            deleteQuietly(staged);
        }
    }

    @Override
    public synchronized List<TransactionRecord> records() {
        ensureOpen();
        try (var paths = Files.list(records)) {
            var result = new ArrayList<TransactionRecord>();
            for (var path : paths.filter(FileTransactionJournal::isRecord)
                .sorted().toList()) {
                result.add(read(path));
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            throw failure("cannot read transaction journal", records, exception);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            ownershipLock.release();
            ownershipChannel.close();
        } catch (IOException exception) {
            throw failure("cannot close transaction journal", root, exception);
        }
    }

    private long findNextSequence() throws IOException {
        try (var paths = Files.list(records)) {
            return paths.filter(FileTransactionJournal::isRecord)
                .map(path -> path.getFileName().toString())
                .map(name -> name.substring(0, name.indexOf('.')))
                .mapToLong(Long::parseLong).max().orElse(-1L) + 1;
        }
    }

    private static boolean isRecord(Path path) {
        var name = path.getFileName().toString();
        return name.length() == 31 && name.endsWith(".properties")
            && name.substring(0, 20).chars().allMatch(Character::isDigit);
    }

    private static TransactionRecord read(Path path) {
        var values = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            values.load(input);
            var participants = values.getProperty("participants", "").isEmpty()
                ? List.<String>of()
                : List.of(values.getProperty("participants").split("\u001f", -1));
            return new TransactionRecord(required(values, "transactionId"),
                TransactionState.valueOf(required(values, "state")), participants,
                values.getProperty("detail"),
                Instant.parse(required(values, "recordedAt")));
        } catch (IOException | RuntimeException exception) {
            throw failure("cannot parse transaction record", path, exception);
        }
    }

    private static String required(Properties values, String key) {
        var value = values.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing property " + key);
        }
        return value;
    }

    private static void force(Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("filesystem does not support atomic move", exception);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("transaction journal is closed");
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The target record was either published or the staging file is recoverable garbage.
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Constructor failure remains the primary diagnostic.
        }
    }

    private static TransactionJournalException failure(String message, Path path,
                                                        Throwable cause) {
        return new TransactionJournalException(message, path, cause);
    }
}
