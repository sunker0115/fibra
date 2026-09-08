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
    private final Path root;
    private final Path objects;
    private final Path records;
    private final Path transactions;
    private final Path quarantine;
    private final ReentrantLock operationLock = new ReentrantLock();
    private final FileChannel ownershipChannel;
    private final FileLock ownershipLock;
    private volatile boolean closed;

    public ArtifactStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        objects = this.root.resolve("objects");
        records = this.root.resolve("records");
        transactions = this.root.resolve("transactions");
        quarantine = this.root.resolve("quarantine");
        FileChannel openedChannel = null;
        FileLock acquiredLock = null;
        try {
            Files.createDirectories(objects);
            Files.createDirectories(records);
            Files.createDirectories(transactions);
            Files.createDirectories(quarantine);
            openedChannel = FileChannel.open(this.root.resolve("store.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            acquiredLock = openedChannel.tryLock();
            if (acquiredLock == null) {
                throw error(ArtifactPhase.RECOVER,
                    "artifact store is already owned by another process", this.root, null);
            }
            ownershipChannel = openedChannel;
            ownershipLock = acquiredLock;
            recoverAbandonedTransactions();
        } catch (IOException | OverlappingFileLockException exception) {
            closeQuietly(acquiredLock);
            closeQuietly(openedChannel);
            throw error(ArtifactPhase.RECOVER, "cannot initialize artifact store",
                this.root, exception);
        }
    }

    public ArtifactInstallTransaction prepareInstall(ArtifactId id, RuntimeId runtimeId,
                                                     String version, Path source) {
        ensureOpen();
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runtimeId, "runtimeId");
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        var candidate = validateSource(source);
        var checksum = digest(candidate);
        var revision = digest((id.value() + "\0" + runtimeId.value() + "\0"
            + version + "\0" + checksum).getBytes(StandardCharsets.UTF_8));
        var transactionPath = transactions.resolve(UUID.randomUUID().toString());
        var staged = transactionPath.resolve("content");
        var object = objects.resolve(checksum).resolve("content");
        var objectCreated = false;
        try {
            Files.createDirectory(transactionPath);
            copy(candidate, staged);
            writeJournal(transactionPath, id, runtimeId, version, checksum, revision);
            Files.createDirectories(object.getParent());
            if (Files.exists(object)) {
                deleteTree(staged);
            } else {
                atomicMove(staged, object);
                objectCreated = true;
            }
        } catch (IOException exception) {
            deleteTreeQuietly(transactionPath);
            if (objectCreated) {
                deleteTreeQuietly(object.getParent());
            }
            throw error(ArtifactPhase.STAGE, "cannot stage artifact", candidate, exception);
        }
        var currentRevision = find(id).map(ArtifactRecord::revision).orElse(null);
        var record = record(id, runtimeId, version, checksum, revision, object,
            ArtifactState.STAGED, Instant.now());
        return new Transaction(record, transactionPath, currentRevision, objectCreated);
    }

    public Optional<ArtifactRecord> find(ArtifactId id) {
        ensureOpen();
        Objects.requireNonNull(id, "id");
        operationLock.lock();
        try {
            var directory = records.resolve(encoded(id.value()));
            var current = directory.resolve("current");
            if (!Files.isRegularFile(current)) {
                return Optional.empty();
            }
            var revision = Files.readString(current, StandardCharsets.UTF_8).trim();
            var record = readRecord(directory.resolve(revision + ".properties"));
            return record.state() == ArtifactState.INSTALLED
                ? Optional.of(record) : Optional.empty();
        } catch (IOException exception) {
            throw error(ArtifactPhase.RECOVER, "cannot read artifact record",
                records, exception);
        } finally {
            operationLock.unlock();
        }
    }

    public List<ArtifactRecord> history(ArtifactId id) {
        ensureOpen();
        Objects.requireNonNull(id, "id");
        operationLock.lock();
        try {
            var directory = records.resolve(encoded(id.value()));
            if (!Files.isDirectory(directory)) {
                return List.of();
            }
            try (var paths = Files.list(directory)) {
                return paths.filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .map(this::readRecord)
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

    public List<ArtifactRecord> installed() {
        ensureOpen();
        operationLock.lock();
        try {
            if (!Files.isDirectory(records)) {
                return List.of();
            }
            var result = new ArrayList<ArtifactRecord>();
            try (var directories = Files.list(records)) {
                for (var directory : directories.filter(Files::isDirectory).toList()) {
                    var current = directory.resolve("current");
                    if (!Files.isRegularFile(current)) {
                        continue;
                    }
                    var revision = Files.readString(current, StandardCharsets.UTF_8).trim();
                    var record = readRecord(directory.resolve(revision + ".properties"));
                    if (record.state() == ArtifactState.INSTALLED) {
                        result.add(record);
                    }
                }
            }
            return result.stream().sorted(Comparator.comparing(
                value -> value.id().value())).toList();
        } catch (IOException exception) {
            throw error(ArtifactPhase.RECOVER, "cannot list installed artifacts",
                records, exception);
        } finally {
            operationLock.unlock();
        }
    }

    public ArtifactRecord retire(ArtifactRecord artifact) {
        ensureOpen();
        Objects.requireNonNull(artifact, "artifact");
        operationLock.lock();
        try {
            var directory = records.resolve(encoded(artifact.id().value()));
            var stored = readRecord(directory.resolve(artifact.revision() + ".properties"));
            var retired = stored.toBuilder().state(ArtifactState.RETIRED)
                .updatedAt(Instant.now()).build();
            writeRecord(directory, retired);
            var current = directory.resolve("current");
            if (Files.isRegularFile(current)
                && Files.readString(current, StandardCharsets.UTF_8).trim()
                .equals(retired.revision())) {
                Files.delete(current);
            }
            return retired;
        } catch (IOException exception) {
            throw error(ArtifactPhase.RETIRE, "cannot retire artifact",
                artifact.location(), exception);
        } finally {
            operationLock.unlock();
        }
    }

    @Override
    public void close() {
        operationLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            ownershipLock.release();
            ownershipChannel.close();
        } catch (IOException exception) {
            throw error(ArtifactPhase.RECOVER, "cannot close artifact store",
                root, exception);
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
                    var values = new Properties();
                    try (var input = Files.newInputStream(journal)) {
                        values.load(input);
                    }
                    var id = new ArtifactId(values.getProperty("id"));
                    var revision = values.getProperty("revision");
                    var checksum = values.getProperty("checksum");
                    var directory = records.resolve(encoded(id.value()));
                    var record = directory.resolve(revision + ".properties");
                    if (Files.isRegularFile(record)) {
                        var stored = readRecord(record);
                        if (stored.state() == ArtifactState.INSTALLED) {
                            writeCurrent(directory, revision);
                        }
                    } else {
                        var object = objects.resolve(checksum);
                        if (!objectReferenced(object.resolve("content"))) {
                            deleteTree(object);
                        }
                    }
                }
                deleteTree(path);
            }
        }
    }

    private boolean objectReferenced(Path object) throws IOException {
        if (!Files.isDirectory(records)) {
            return false;
        }
        try (var paths = Files.walk(records)) {
            for (var record : paths.filter(path ->
                path.getFileName().toString().endsWith(".properties")).toList()) {
                if (readRecord(record).location().equals(object)) {
                    return true;
                }
            }
        }
        return false;
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
        Files.createDirectories(directory);
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
            atomicMove(staged, target);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private ArtifactRecord readRecord(Path path) {
        var values = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            values.load(input);
        } catch (IOException exception) {
            throw error(ArtifactPhase.RECOVER, "cannot read artifact record", path, exception);
        }
        return record(new ArtifactId(values.getProperty("id")),
            new RuntimeId(values.getProperty("runtime")), values.getProperty("version"),
            values.getProperty("checksum"), values.getProperty("revision"),
            Path.of(values.getProperty("location")),
            ArtifactState.valueOf(values.getProperty("state")),
            Instant.parse(values.getProperty("updatedAt")));
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
        private final String expectedCurrentRevision;
        private final boolean objectCreated;
        private ArtifactRecord result;
        private State state = State.PREPARED;

        private Transaction(ArtifactRecord stagedRecord, Path transactionPath,
                            String expectedCurrentRevision, boolean objectCreated) {
            this.stagedRecord = stagedRecord;
            this.transactionPath = transactionPath;
            this.expectedCurrentRevision = expectedCurrentRevision;
            this.objectCreated = objectCreated;
        }

        @Override
        public ArtifactRecord candidate() {
            return stagedRecord;
        }

        @Override
        public ArtifactRecord commit() {
            if (state == State.COMMITTED) {
                return result;
            }
            ensurePrepared();
            operationLock.lock();
            try {
                var current = find(stagedRecord.id()).map(ArtifactRecord::revision).orElse(null);
                if (!Objects.equals(current, expectedCurrentRevision)) {
                    throw error(ArtifactPhase.COMMIT,
                        "artifact changed after prepare", transactionPath, null);
                }
                result = stagedRecord.toBuilder()
                    .state(ArtifactState.INSTALLED).updatedAt(Instant.now()).build();
                var directory = records.resolve(encoded(result.id().value()));
                writeRecord(directory, result);
                writeCurrent(directory, result.revision());
                deleteTree(transactionPath);
                state = State.COMMITTED;
                return result;
            } catch (IOException exception) {
                throw error(ArtifactPhase.COMMIT, "cannot commit artifact",
                    transactionPath, exception);
            } finally {
                operationLock.unlock();
            }
        }

        @Override
        public ArtifactRecord quarantine(String reason) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
            ensurePrepared();
            operationLock.lock();
            try {
                var target = quarantine.resolve(transactionPath.getFileName());
                atomicMove(transactionPath, target);
                var quarantinedContent = target.resolve("content");
                copy(stagedRecord.location(), quarantinedContent);
                if (objectCreated) {
                    deleteTree(stagedRecord.location().getParent());
                }
                result = stagedRecord.toBuilder().location(quarantinedContent)
                    .state(ArtifactState.QUARANTINED).updatedAt(Instant.now()).build();
                writeRecord(records.resolve(encoded(result.id().value())), result);
                Files.writeString(target.resolve("reason.txt"), reason,
                    StandardCharsets.UTF_8);
                state = State.QUARANTINED;
                return result;
            } catch (IOException exception) {
                throw error(ArtifactPhase.COMMIT, "cannot quarantine artifact",
                    transactionPath, exception);
            } finally {
                operationLock.unlock();
            }
        }

        @Override
        public void rollback() {
            if (state == State.ROLLED_BACK || state == State.QUARANTINED) {
                return;
            }
            operationLock.lock();
            try {
                if (state == State.COMMITTED) {
                    var directory = records.resolve(encoded(stagedRecord.id().value()));
                    var current = directory.resolve("current");
                    var actual = Files.isRegularFile(current)
                        ? Files.readString(current, StandardCharsets.UTF_8).trim() : null;
                    if (!Objects.equals(actual, stagedRecord.revision())) {
                        throw error(ArtifactPhase.RECOVER,
                            "cannot compensate artifact after current revision changed",
                            current, null);
                    }
                    Files.deleteIfExists(directory.resolve(
                        stagedRecord.revision() + ".properties"));
                    if (expectedCurrentRevision == null) {
                        Files.deleteIfExists(current);
                    } else {
                        writeCurrent(directory, expectedCurrentRevision);
                    }
                } else {
                    deleteTree(transactionPath);
                }
                if (objectCreated && !objectReferenced(stagedRecord.location())) {
                    deleteTree(stagedRecord.location().getParent());
                }
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
            if (state == State.PREPARED) {
                rollback();
            }
        }

        private void ensurePrepared() {
            if (state != State.PREPARED) {
                throw new IllegalStateException("artifact transaction is " + state);
            }
        }
    }

    private void writeCurrent(Path directory, String revision) throws IOException {
        var staged = Files.createTempFile(directory, ".current-", ".tmp");
        try {
            Files.writeString(staged, revision, StandardCharsets.UTF_8);
            atomicMove(staged, directory.resolve("current"));
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private enum State {
        PREPARED,
        COMMITTED,
        QUARANTINED,
        ROLLED_BACK
    }
}
