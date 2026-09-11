package com.sstlfsj.fibra.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public final class ArtifactStore implements AutoCloseable {
    private static final StorageIo FILES = path -> {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        }
    };

    private final Path root;
    private final Path objects;
    private final Path records;
    private final Path transactions;
    private final StorageIo io;
    private final ReentrantLock operationLock = new ReentrantLock();
    private final FileChannel ownershipChannel;
    private volatile boolean closed;
    private ArtifactException closeFailure;

    public ArtifactStore(Path root) {
        this(root, FILES);
    }

    ArtifactStore(Path root, StorageIo io) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        objects = this.root.resolve("objects");
        records = this.root.resolve("records");
        transactions = this.root.resolve("transactions");
        this.io = Objects.requireNonNull(io, "io");
        FileChannel openedChannel = null;
        FileLock acquiredLock = null;
        try {
            createDirectoryChain(this.root);
            openedChannel = FileChannel.open(this.root.resolve("store.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            acquiredLock = openedChannel.tryLock();
            if (acquiredLock == null) {
                throw error(ArtifactPhase.RECOVER,
                    "artifact store is already owned by another process", this.root, null);
            }
            createDirectoryChain(objects);
            createDirectoryChain(records);
            createDirectoryChain(transactions);
            forceDirectories(this.root, this.root.getRoot());
            ownershipChannel = openedChannel;
            recoverAbandonedTransactions();
        } catch (IOException | OverlappingFileLockException exception) {
            closeQuietly(acquiredLock);
            closeQuietly(openedChannel);
            throw error(ArtifactPhase.RECOVER, "cannot initialize artifact store",
                this.root, exception);
        } catch (RuntimeException exception) {
            closeQuietly(acquiredLock);
            closeQuietly(openedChannel);
            throw exception;
        }
    }

    public ArtifactInstallTransaction prepareInstall(ArtifactId id, RuntimeId runtimeId,
                                                     String version, Path source) {
        operationLock.lock();
        try {
            ensureOpen();
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(runtimeId, "runtimeId");
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("version must not be blank");
            }
            var candidate = validateSource(source);
            var transactionPath = transactions.resolve(UUID.randomUUID().toString());
            var privateObject = transactionPath.resolve("object");
            var staged = privateObject.resolve("content");
            var transactionCreated = false;
            try {
                Files.createDirectory(transactionPath);
                transactionCreated = true;
                Files.createDirectory(privateObject);
                io.copy(candidate, staged);
                var checksum = digest(staged);
                var revision = revision(id, runtimeId, version, checksum);
                var object = objects.resolve(checksum).resolve("content");
                var record = record(id, runtimeId, version, checksum, revision, object,
                    ArtifactState.STAGED, Instant.now());
                writeJournal(transactionPath, id, runtimeId, version, checksum, revision);
                if (Files.exists(object.getParent())) {
                    verifyContent(record);
                    deleteTree(privateObject);
                } else {
                    Files.move(privateObject, object.getParent(), StandardCopyOption.ATOMIC_MOVE);
                }
                return new Transaction(record, transactionPath);
            } catch (IOException exception) {
                if (transactionCreated) {
                    deleteTreeQuietly(transactionPath);
                }
                throw error(ArtifactPhase.STAGE, "cannot stage artifact", candidate, exception);
            } catch (RuntimeException exception) {
                if (transactionCreated) {
                    deleteTreeQuietly(transactionPath);
                }
                throw exception;
            }
        } finally {
            operationLock.unlock();
        }
    }

    public Optional<ArtifactRecord> find(ArtifactId id, String revision) {
        Objects.requireNonNull(id, "id");
        requireDigest(revision, "revision");
        operationLock.lock();
        try {
            ensureOpen();
            var path = records.resolve(encoded(id.value())).resolve(revision + ".properties");
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            return Optional.of(readStoredRecord(id, revision, path));
        } finally {
            operationLock.unlock();
        }
    }

    public List<ArtifactRecord> history(ArtifactId id) {
        Objects.requireNonNull(id, "id");
        operationLock.lock();
        try {
            ensureOpen();
            var directory = records.resolve(encoded(id.value()));
            if (!Files.isDirectory(directory)) {
                return List.of();
            }
            try (var paths = Files.list(directory)) {
                return paths.filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .map(path -> readStoredRecord(id, revisionFromRecordPath(path), path))
                    .sorted(Comparator.comparing(ArtifactRecord::updatedAt))
                    .toList();
            }
        } catch (IOException exception) {
            throw error(ArtifactPhase.RECOVER, "cannot read artifact history",
                records, exception);
        } finally {
            operationLock.unlock();
        }
    }

    @Override
    public void close() {
        operationLock.lock();
        try {
            if (closeFailure != null) {
                throw closeFailure;
            }
            if (closed) {
                return;
            }
            closed = true;
            io.close(ownershipChannel);
        } catch (IOException exception) {
            closeFailure = error(ArtifactPhase.RECOVER, "cannot close artifact store",
                root, exception);
            throw closeFailure;
        } finally {
            operationLock.unlock();
        }
    }

    private Path validateSource(Path source) {
        Objects.requireNonNull(source, "source");
        var normalized = source.toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(normalized)) {
                throw error(ArtifactPhase.VALIDATE,
                    "artifact source must not be a symbolic link", normalized, null);
            }
            var real = normalized.toRealPath();
            if (!Files.isRegularFile(real) && !Files.isDirectory(real)) {
                throw error(ArtifactPhase.VALIDATE,
                    "artifact source must be a regular file or directory", real, null);
            }
            try (var paths = Files.walk(real)) {
                var link = paths.filter(Files::isSymbolicLink).findFirst();
                if (link.isPresent()) {
                    throw error(ArtifactPhase.VALIDATE,
                        "artifact tree must not contain symbolic links", link.get(), null);
                }
            }
            return real;
        } catch (ArtifactException exception) {
            throw exception;
        } catch (IOException exception) {
            throw error(ArtifactPhase.VALIDATE, "cannot inspect artifact source",
                normalized, exception);
        }
    }

    private String digest(Path source) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            if (Files.isRegularFile(source)) {
                digest.update((byte) 'F');
                updateContent(digest, source);
            } else {
                try (var paths = Files.walk(source)) {
                    for (var path : paths.sorted().toList()) {
                        if (path.equals(source)) {
                            continue;
                        }
                        var relative = source.relativize(path).toString().replace('\\', '/');
                        digest.update((Files.isDirectory(path) ? "D" : "F")
                            .getBytes(StandardCharsets.UTF_8));
                        digest.update(relative.getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                        if (Files.isRegularFile(path)) {
                            updateContent(digest, path);
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw error(ArtifactPhase.DIGEST, "cannot digest artifact", source, exception);
        }
    }

    private static void updateContent(MessageDigest digest, Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            var buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
    }

    private static void copy(Path source, Path target) throws IOException {
        if (Files.isRegularFile(source)) {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory,
                                                     BasicFileAttributes attributes)
                throws IOException {
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)),
                    StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void recoverAbandonedTransactions() throws IOException {
        try (var paths = Files.list(transactions)) {
            for (var path : paths.toList()) {
                var journal = path.resolve("transaction.properties");
                if (Files.isRegularFile(journal)) {
                    var values = readJournal(journal);
                    var id = journalArtifactId(values, journal);
                    var revision = journalDigest(values, "revision", journal);
                    journalDigest(values, "checksum", journal);
                    var directory = records.resolve(encoded(id.value()));
                    var record = directory.resolve(revision + ".properties");
                    if (Files.isRegularFile(record)) {
                        readStoredRecord(id, revision, record);
                    }
                }
                deleteTree(path);
            }
        }
    }

    private void verifyContent(ArtifactRecord record) {
        if (!Files.exists(record.location())) {
            throw error(ArtifactPhase.COMMIT, "artifact content is missing", record.location(), null);
        }
        var actual = digest(record.location());
        if (!record.checksum().equals(actual)) {
            throw error(ArtifactPhase.COMMIT,
                "artifact content checksum does not match metadata", record.location(), null);
        }
    }

    private void writeJournal(Path directory, ArtifactId id, RuntimeId runtimeId,
                              String version, String checksum, String revision)
        throws IOException {
        var values = new Properties();
        values.setProperty("id", id.value());
        values.setProperty("runtime", runtimeId.value());
        values.setProperty("version", version);
        values.setProperty("checksum", checksum);
        values.setProperty("revision", revision);
        try (var output = Files.newOutputStream(directory.resolve("transaction.properties"),
            java.nio.file.StandardOpenOption.CREATE_NEW)) {
            values.store(output, null);
        }
    }

    private void writeRecord(Path directory, ArtifactRecord record) throws IOException {
        createDirectoryChain(directory);
        var values = new Properties();
        values.setProperty("id", record.id().value());
        values.setProperty("runtime", record.runtimeId().value());
        values.setProperty("version", record.version());
        values.setProperty("checksum", record.checksum());
        values.setProperty("revision", record.revision());
        values.setProperty("location", record.location().toString());
        values.setProperty("state", record.state().name());
        values.setProperty("updatedAt", record.updatedAt().toString());
        var target = directory.resolve(record.revision() + ".properties");
        var staged = Files.createTempFile(directory, ".record-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(staged)) {
                values.store(output, null);
            }
            io.force(staged);
            atomicMove(staged, target);
            io.force(target);
            forceDirectories(directory, records);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private void createDirectoryChain(Path target) throws IOException {
        var missing = new ArrayList<Path>();
        for (var directory = target; !Files.exists(directory); directory = directory.getParent()) {
            if (directory == null) {
                throw new IOException("cannot locate existing parent for " + target);
            }
            missing.add(directory);
        }
        for (var directory : missing.reversed()) {
            Files.createDirectory(directory);
            io.force(directory);
            var parent = directory.getParent();
            if (parent != null) {
                io.force(parent);
            }
        }
        if (!Files.isDirectory(target)) {
            throw new IOException("artifact store path is not a directory: " + target);
        }
    }

    private void forceArtifactContent(Path content) throws IOException {
        if (!Files.exists(content)) {
            throw new IOException("artifact content is missing: " + content);
        }
        try (var paths = Files.walk(content)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                io.force(path);
            }
        }
        forceDirectories(content.getParent(), objects);
    }

    private void forceDirectories(Path directory, Path boundary) throws IOException {
        for (var current = directory; current != null; current = current.getParent()) {
            io.force(current);
            if (current.equals(boundary)) {
                return;
            }
        }
        throw new IOException("directory is outside store boundary: " + directory);
    }

    private ArtifactRecord readRecord(Path path) {
        var values = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            values.load(input);
        } catch (IOException exception) {
            throw error(ArtifactPhase.RECOVER, "cannot read artifact record", path, exception);
        }
        try {
            return record(new ArtifactId(values.getProperty("id")),
                new RuntimeId(values.getProperty("runtime")), values.getProperty("version"),
                values.getProperty("checksum"), values.getProperty("revision"),
                Path.of(values.getProperty("location")),
                ArtifactState.valueOf(values.getProperty("state")),
                Instant.parse(values.getProperty("updatedAt")));
        } catch (RuntimeException exception) {
            throw error(ArtifactPhase.RECOVER, "invalid artifact record", path, exception);
        }
    }

    private ArtifactRecord readStoredRecord(ArtifactId expectedId, String expectedRevision,
                                            Path path) {
        var stored = readRecord(path);
        if (!stored.id().equals(expectedId) || !stored.revision().equals(expectedRevision)) {
            throw error(ArtifactPhase.RECOVER, "artifact record identity does not match path",
                path, null);
        }
        return verifyStoredRecord(stored, path);
    }

    private ArtifactRecord verifyStoredRecord(ArtifactRecord stored, Path path) {
        if (stored.state() != ArtifactState.INSTALLED) {
            throw error(ArtifactPhase.RECOVER, "artifact record is not installed", path, null);
        }
        if (!isDigest(stored.revision()) || !isDigest(stored.checksum())) {
            throw error(ArtifactPhase.RECOVER, "artifact record has invalid digest", path, null);
        }
        if (!stored.revision().equals(revision(stored.id(), stored.runtimeId(),
            stored.version(), stored.checksum()))) {
            throw error(ArtifactPhase.RECOVER, "artifact metadata does not match revision", path, null);
        }
        var expectedLocation = objects.resolve(stored.checksum()).resolve("content")
            .toAbsolutePath().normalize();
        if (!stored.location().equals(expectedLocation)) {
            throw error(ArtifactPhase.RECOVER, "artifact record location is outside object store",
                path, null);
        }
        try {
            verifyContent(stored);
        } catch (ArtifactException exception) {
            throw error(ArtifactPhase.RECOVER, "artifact content does not match record", path,
                exception);
        }
        return stored;
    }

    private static String revisionFromRecordPath(Path path) {
        var fileName = path.getFileName().toString();
        if (!fileName.endsWith(".properties")) {
            throw new IllegalArgumentException("artifact record must end in .properties");
        }
        var revision = fileName.substring(0, fileName.length() - ".properties".length());
        if (!isDigest(revision)) {
            throw error(ArtifactPhase.RECOVER, "artifact record has invalid revision path", path, null);
        }
        return revision;
    }

    private static boolean isDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static void requireDigest(String value, String name) {
        if (!isDigest(value)) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
        }
    }

    private Properties readJournal(Path path) {
        var values = new Properties();
        try (var input = Files.newInputStream(path)) {
            values.load(input);
            return values;
        } catch (IOException | RuntimeException exception) {
            throw error(ArtifactPhase.RECOVER, "cannot read artifact transaction journal",
                path, exception);
        }
    }

    private ArtifactId journalArtifactId(Properties values, Path path) {
        try {
            return new ArtifactId(values.getProperty("id"));
        } catch (RuntimeException exception) {
            throw error(ArtifactPhase.RECOVER, "invalid artifact transaction journal", path,
                exception);
        }
    }

    private String journalDigest(Properties values, String name, Path path) {
        var value = values.getProperty(name);
        if (!isDigest(value)) {
            throw error(ArtifactPhase.RECOVER, "invalid artifact transaction journal", path, null);
        }
        return value;
    }

    private static ArtifactRecord record(ArtifactId id, RuntimeId runtimeId,
                                         String version, String checksum,
                                         String revision, Path location,
                                         ArtifactState state, Instant updatedAt) {
        return ArtifactRecord.builder().id(id).runtimeId(runtimeId).version(version)
            .checksum(checksum).revision(revision).location(location).state(state)
            .updatedAt(updatedAt).build();
    }

    private static String encoded(String value) {
        return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String revision(ArtifactId id, RuntimeId runtimeId, String version,
                                   String checksum) {
        return digest((id.value() + "\0" + runtimeId.value() + "\0" + version + "\0"
            + checksum).getBytes(StandardCharsets.UTF_8));
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("filesystem does not support atomic move", exception);
        }
    }

    private static void deleteTreeQuietly(Path path) {
        try {
            deleteTree(path);
        } catch (IOException ignored) {
            // The abandoned transaction remains recoverable on the next store open.
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("artifact store is closed");
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

    private static void deleteTree(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (var candidate : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    private static ArtifactException error(ArtifactPhase phase, String message,
                                           Path path, Throwable cause) {
        return new ArtifactException(phase, message, path, cause);
    }

    private final class Transaction implements ArtifactInstallTransaction {
        private final ArtifactRecord stagedRecord;
        private final Path transactionPath;
        private ArtifactRecord result;
        private State state = State.PREPARED;

        private Transaction(ArtifactRecord stagedRecord, Path transactionPath) {
            this.stagedRecord = stagedRecord;
            this.transactionPath = transactionPath;
        }

        @Override
        public ArtifactRecord candidate() {
            return stagedRecord;
        }

        @Override
        public ArtifactRecord save() {
            operationLock.lock();
            try {
                ensureOpen();
                if (state == State.SAVED) {
                    return result;
                }
                ensurePrepared();
                verifyContent(stagedRecord);
                forceArtifactContent(stagedRecord.location());
                var directory = records.resolve(encoded(stagedRecord.id().value()));
                var recordPath = directory.resolve(stagedRecord.revision() + ".properties");
                if (Files.isRegularFile(recordPath)) {
                    var existing = readRecord(recordPath);
                    verifyExisting(existing);
                    result = existing;
                    io.force(recordPath);
                    forceDirectories(directory, records);
                } else {
                    result = stagedRecord.toBuilder().state(ArtifactState.INSTALLED)
                        .updatedAt(Instant.now()).build();
                    writeRecord(directory, result);
                }
                deleteTree(transactionPath);
                state = State.SAVED;
                return result;
            } catch (IOException exception) {
                throw error(ArtifactPhase.COMMIT, "cannot save artifact",
                    transactionPath, exception);
            } finally {
                operationLock.unlock();
            }
        }

        private void verifyExisting(ArtifactRecord existing) {
            if (!existing.id().equals(stagedRecord.id())
                || !existing.revision().equals(stagedRecord.revision())
                || !existing.runtimeId().equals(stagedRecord.runtimeId())
                || !existing.version().equals(stagedRecord.version())
                || !existing.checksum().equals(stagedRecord.checksum())
                || !existing.location().equals(stagedRecord.location())) {
                throw error(ArtifactPhase.COMMIT,
                    "existing revision metadata does not match artifact", existing.location(), null);
            }
            if (existing.state() != ArtifactState.INSTALLED) {
                throw error(ArtifactPhase.COMMIT,
                    "existing revision is not installed", existing.location(), null);
            }
            verifyContent(existing);
        }

        @Override
        public void rollback() {
            operationLock.lock();
            try {
                ensureOpen();
                if (state == State.ROLLED_BACK || state == State.SAVED) {
                    return;
                }
                deleteTree(transactionPath);
                state = State.ROLLED_BACK;
            } catch (IOException exception) {
                throw error(ArtifactPhase.RECOVER,
                    "cannot compensate artifact transaction", transactionPath, exception);
            } finally {
                operationLock.unlock();
            }
        }

        @Override
        public void close() {
            rollback();
        }

        private void ensurePrepared() {
            if (state != State.PREPARED) {
                throw new IllegalStateException("artifact transaction is " + state);
            }
        }
    }

    interface StorageIo {
        void force(Path path) throws IOException;

        default void copy(Path source, Path target) throws IOException {
            ArtifactStore.copy(source, target);
        }

        default void close(FileChannel channel) throws IOException {
            channel.close();
        }
    }

    private enum State {
        PREPARED,
        SAVED,
        ROLLED_BACK
    }
}
