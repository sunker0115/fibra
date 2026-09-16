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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** 以完整逻辑 package 为原子单位的不可变内容存储。 */
public final class PluginPackageStore implements AutoCloseable {
    private static final Set<String> RECORD_FIELDS = Set.of(
        "pluginId", "version", "packageRevision", "location", "state", "updatedAt");
    private static final Set<String> JOURNAL_FIELDS = Set.of(
        "pluginId", "packageRevision");
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
    private final FileLock ownershipLock;
    private volatile boolean closed;
    private ArtifactException closeFailure;

    public PluginPackageStore(Path root) {
        this(root, FILES);
    }

    PluginPackageStore(Path root, StorageIo io) {
        var requestedRoot = Objects.requireNonNull(root, "root")
            .toAbsolutePath().normalize();
        this.io = Objects.requireNonNull(io, "io");
        FileChannel openedChannel = null;
        FileLock acquiredLock = null;
        try {
            createDirectoryChain(requestedRoot);
            this.root = requestedRoot.toRealPath();
            objects = this.root.resolve("objects");
            records = this.root.resolve("records");
            transactions = this.root.resolve("transactions");
            openedChannel = FileChannel.open(this.root.resolve("store.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            acquiredLock = openedChannel.tryLock();
            if (acquiredLock == null) {
                throw error(ArtifactPhase.RECOVER,
                    "plugin package store is already owned by another process", this.root, null);
            }
            createDirectoryChain(objects);
            createDirectoryChain(records);
            createDirectoryChain(transactions);
            forceDirectories(this.root, this.root.getRoot());
            ownershipChannel = openedChannel;
            ownershipLock = acquiredLock;
            recoverAbandonedTransactions();
        } catch (IOException | OverlappingFileLockException exception) {
            closeQuietly(acquiredLock);
            closeQuietly(openedChannel);
            throw error(ArtifactPhase.RECOVER,
                "cannot initialize plugin package store", requestedRoot, exception);
        } catch (RuntimeException exception) {
            closeQuietly(acquiredLock);
            closeQuietly(openedChannel);
            throw exception;
        }
    }

    public PluginPackageInstallTransaction prepareInstall(Path source) {
        var sourcePackage = PluginPackage.read(source);
        operationLock.lock();
        try {
            ensureOpen();
            var transactionPath = transactions.resolve(UUID.randomUUID().toString());
            var privateObject = transactionPath.resolve("object");
            var staged = privateObject.resolve("content");
            var transactionCreated = false;
            try {
                Files.createDirectory(transactionPath);
                transactionCreated = true;
                writeJournal(transactionPath, sourcePackage);
                Files.createDirectory(privateObject);
                io.copy(sourcePackage.root(), staged);
                var stagedPackage = PluginPackage.read(staged);
                requireSamePackage(sourcePackage, stagedPackage, ArtifactPhase.DIGEST,
                    "staged plugin package does not match its source");
                var objectDirectory = objects.resolve(sourcePackage.packageDigest());
                var object = objectDirectory.resolve("content");
                if (Files.exists(objectDirectory, LinkOption.NOFOLLOW_LINKS)) {
                    var ownedObject = requireOwnedObject(object, ArtifactPhase.DIGEST);
                    var existingPackage = readPackage(ownedObject, ArtifactPhase.DIGEST,
                        "existing package object is invalid");
                    requireSamePackage(sourcePackage, existingPackage, ArtifactPhase.DIGEST,
                        "existing package object does not match its content identity");
                    deleteTree(privateObject);
                } else {
                    atomicMove(privateObject, objectDirectory);
                }
                var frozenObject = requireOwnedObject(object, ArtifactPhase.DIGEST);
                var frozenPackage = readPackage(frozenObject, ArtifactPhase.DIGEST,
                    "staged package object is invalid");
                requireSamePackage(sourcePackage, frozenPackage, ArtifactPhase.DIGEST,
                    "staged package object changed before adoption");
                var candidate = record(frozenPackage, frozenPackage.root(), ArtifactState.STAGED,
                    Instant.now());
                return new Transaction(candidate, transactionPath);
            } catch (ArtifactException exception) {
                if (transactionCreated) {
                    deleteTreeQuietly(transactionPath);
                }
                throw exception;
            } catch (IOException exception) {
                if (transactionCreated) {
                    deleteTreeQuietly(transactionPath);
                }
                throw error(ArtifactPhase.STAGE, "cannot stage plugin package",
                    sourcePackage.root(), exception);
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

    public Optional<PluginPackageRecord> find(PluginId pluginId,
                                               String packageRevision) {
        Objects.requireNonNull(pluginId, "pluginId");
        requireDigest(packageRevision, "packageRevision");
        operationLock.lock();
        try {
            ensureOpen();
            var path = recordPath(pluginId, packageRevision);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            return Optional.of(readStoredRecord(pluginId, packageRevision, path));
        } finally {
            operationLock.unlock();
        }
    }

    public List<PluginPackageRecord> history(PluginId pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        operationLock.lock();
        try {
            ensureOpen();
            var directory = recordDirectory(pluginId);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return List.of();
            }
            try (var paths = Files.list(directory)) {
                return paths.filter(path -> Files.isRegularFile(path,
                        LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .sorted().map(path -> readStoredRecord(pluginId,
                        revisionFromRecordPath(path), path))
                    .sorted(Comparator.comparing(PluginPackageRecord::updatedAt)
                        .thenComparing(PluginPackageRecord::packageRevision))
                    .toList();
            } catch (IOException exception) {
                throw error(ArtifactPhase.RECOVER,
                    "cannot list plugin package history", directory, exception);
            }
        } finally {
            operationLock.unlock();
        }
    }

    @Override
    public void close() {
        operationLock.lock();
        try {
            if (closed) {
                if (closeFailure != null) {
                    throw closeFailure;
                }
                return;
            }
            closed = true;
            try {
                ownershipLock.release();
                io.close(ownershipChannel);
            } catch (IOException exception) {
                closeFailure = error(ArtifactPhase.RECOVER,
                    "cannot close plugin package store", root, exception);
                throw closeFailure;
            }
        } finally {
            operationLock.unlock();
        }
    }

    private void recoverAbandonedTransactions() throws IOException {
        try (var paths = Files.list(transactions)) {
            for (var path : paths.toList()) {
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("plugin package transaction is not a directory: " + path);
                }
                var journal = path.resolve("transaction.properties");
                if (Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        var values = readProperties(journal, JOURNAL_FIELDS);
                        new PluginId(values.getProperty("pluginId"));
                        requireDigest(values.getProperty("packageRevision"),
                            "packageRevision");
                    } catch (IOException | RuntimeException exception) {
                        throw error(ArtifactPhase.RECOVER,
                            "invalid plugin package transaction journal",
                            journal, exception);
                    }
                }
                deleteTree(path);
            }
        }
    }

    private PluginPackageRecord readStoredRecord(PluginId expectedPluginId,
                                                  String expectedRevision, Path path) {
        try {
            var values = readProperties(path, RECORD_FIELDS);
            var pluginId = new PluginId(values.getProperty("pluginId"));
            var version = required(values.getProperty("version"), "version");
            var revision = values.getProperty("packageRevision");
            requireDigest(revision, "packageRevision");
            var location = Path.of(values.getProperty("location"))
                .toAbsolutePath().normalize();
            var state = ArtifactState.valueOf(values.getProperty("state"));
            var updatedAt = Instant.parse(values.getProperty("updatedAt"));
            if (!pluginId.equals(expectedPluginId) || !revision.equals(expectedRevision)) {
                throw new IllegalArgumentException(
                    "plugin package record identity does not match its path");
            }
            if (state != ArtifactState.INSTALLED) {
                throw new IllegalArgumentException(
                    "stored plugin package record is not installed");
            }
            var expectedLocation = requireOwnedObject(
                objects.resolve(revision).resolve("content"), ArtifactPhase.RECOVER);
            if (!location.equals(expectedLocation)) {
                throw new IllegalArgumentException(
                    "plugin package record location is outside object store");
            }
            var storedPackage = readPackage(location, ArtifactPhase.RECOVER,
                "stored plugin package object is invalid");
            if (!pluginId.equals(storedPackage.pluginId())
                || !version.equals(storedPackage.version())
                || !revision.equals(storedPackage.packageDigest())) {
                throw new IllegalArgumentException(
                    "plugin package record does not match its stored object");
            }
            return record(storedPackage, location, state, updatedAt);
        } catch (ArtifactException exception) {
            if (exception.phase() == ArtifactPhase.RECOVER) {
                throw exception;
            }
            throw error(ArtifactPhase.RECOVER,
                "cannot recover plugin package record", path, exception);
        } catch (IOException | RuntimeException exception) {
            throw error(ArtifactPhase.RECOVER,
                "invalid plugin package record", path, exception);
        }
    }

    private void writeJournal(Path directory, PluginPackage pluginPackage)
        throws IOException {
        var values = new Properties();
        values.setProperty("pluginId", pluginPackage.pluginId().value());
        values.setProperty("packageRevision", pluginPackage.packageDigest());
        try (var output = Files.newOutputStream(directory.resolve("transaction.properties"),
            StandardOpenOption.CREATE_NEW)) {
            values.store(output, null);
        }
    }

    private void writeRecord(Path directory, PluginPackageRecord record)
        throws IOException {
        createDirectoryChain(directory);
        var values = new Properties();
        values.setProperty("pluginId", record.pluginId().value());
        values.setProperty("version", record.version());
        values.setProperty("packageRevision", record.packageRevision());
        values.setProperty("location", record.location().toString());
        values.setProperty("state", record.state().name());
        values.setProperty("updatedAt", record.updatedAt().toString());
        var target = directory.resolve(record.packageRevision() + ".properties");
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

    private Properties readProperties(Path path, Set<String> expected) throws IOException {
        var values = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            values.load(input);
        }
        if (!values.stringPropertyNames().equals(expected)) {
            throw new IllegalArgumentException(
                "stored fields do not match the required schema");
        }
        return values;
    }

    private void forcePackageContent(Path content) throws IOException {
        try (var paths = Files.walk(content)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                io.force(path);
            }
        }
        forceDirectories(content.getParent(), objects);
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
            if (directory.getParent() != null) {
                io.force(directory.getParent());
            }
        }
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("plugin package store path is not a directory: " + target);
        }
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

    private Path recordDirectory(PluginId pluginId) {
        return records.resolve(encoded(pluginId.value()));
    }

    private Path recordPath(PluginId pluginId, String packageRevision) {
        return recordDirectory(pluginId).resolve(packageRevision + ".properties");
    }

    private Path requireOwnedObject(Path object, ArtifactPhase phase) {
        var directory = object.getParent();
        try {
            if (directory == null || Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw error(phase,
                    "plugin package object directory is not an owned directory",
                    object, null);
            }
            var realObjects = objects.toRealPath();
            var realDirectory = directory.toRealPath();
            if (!realObjects.equals(realDirectory.getParent())) {
                throw error(phase,
                    "plugin package object directory is outside the store",
                    directory, null);
            }
            if (Files.isSymbolicLink(object)
                || !Files.isDirectory(object, LinkOption.NOFOLLOW_LINKS)) {
                throw error(phase,
                    "plugin package content is not an owned directory", object, null);
            }
            var realObject = object.toRealPath();
            if (!realDirectory.equals(realObject.getParent())) {
                throw error(phase,
                    "plugin package content is outside its object directory",
                    object, null);
            }
            return realObject;
        } catch (ArtifactException exception) {
            throw exception;
        } catch (IOException exception) {
            throw error(phase, "cannot resolve plugin package object", object, exception);
        }
    }

    private static PluginPackageRecord record(PluginPackage pluginPackage, Path location,
                                              ArtifactState state, Instant updatedAt) {
        var artifactIds = new LinkedHashMap<FacetId, ArtifactId>();
        for (var facet : pluginPackage.facets()) {
            artifactIds.put(facet.facetId(), new ArtifactId(
                pluginPackage.packageDigest() + ":" + facet.facetId().value()));
        }
        var managed = ManagedPluginPackage.from(pluginPackage, artifactIds);
        return PluginPackageRecord.builder().pluginId(pluginPackage.pluginId())
            .version(pluginPackage.version())
            .packageRevision(pluginPackage.packageDigest()).location(location)
            .managedPackage(managed).state(state).updatedAt(updatedAt).build();
    }

    private static PluginPackage readPackage(Path path, ArtifactPhase phase,
                                             String message) {
        try {
            return PluginPackage.read(path);
        } catch (RuntimeException exception) {
            throw error(phase, message, path, exception);
        }
    }

    private static void requireSamePackage(PluginPackage expected, PluginPackage actual,
                                           ArtifactPhase phase, String message) {
        if (!expected.pluginId().equals(actual.pluginId())
            || !expected.version().equals(actual.version())
            || !expected.packageDigest().equals(actual.packageDigest())) {
            throw error(phase, message, actual.root(), null);
        }
    }

    private static String revisionFromRecordPath(Path path) {
        var fileName = path.getFileName().toString();
        if (!fileName.endsWith(".properties")) {
            throw error(ArtifactPhase.RECOVER,
                "plugin package record must end in .properties", path, null);
        }
        var revision = fileName.substring(0,
            fileName.length() - ".properties".length());
        try {
            requireDigest(revision, "packageRevision");
            return revision;
        } catch (IllegalArgumentException exception) {
            throw error(ArtifactPhase.RECOVER,
                "plugin package record has an invalid revision path", path, exception);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireDigest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                name + " must be a lowercase SHA-256 digest");
        }
    }

    private static String encoded(String value) {
        return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("filesystem does not support atomic move", exception);
        }
    }

    private static void copy(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory,
                                                     BasicFileAttributes attributes)
                throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new IOException("plugin package contains a symbolic link");
                }
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new IOException("plugin package contains an unsupported entry");
                }
                Files.copy(file, target.resolve(source.relativize(file)),
                    StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTreeQuietly(Path path) {
        try {
            deleteTree(path);
        } catch (IOException ignored) {
            // The abandoned transaction remains recoverable on the next store open.
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

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("plugin package store is closed");
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

    private static ArtifactException error(ArtifactPhase phase, String message,
                                           Path path, Throwable cause) {
        return new ArtifactException(phase, message, path, cause);
    }

    private final class Transaction implements PluginPackageInstallTransaction {
        private final PluginPackageRecord stagedRecord;
        private final Path transactionPath;
        private PluginPackageRecord result;
        private State state = State.PREPARED;

        private Transaction(PluginPackageRecord stagedRecord, Path transactionPath) {
            this.stagedRecord = stagedRecord;
            this.transactionPath = transactionPath;
        }

        @Override
        public PluginPackageRecord candidate() {
            return stagedRecord;
        }

        @Override
        public PluginPackageRecord save() {
            operationLock.lock();
            try {
                ensureOpen();
                if (state == State.SAVED) {
                    return result;
                }
                ensurePrepared();
                var storedPackage = readPackage(stagedRecord.location(), ArtifactPhase.COMMIT,
                    "plugin package object is invalid before commit");
                if (!stagedRecord.pluginId().equals(storedPackage.pluginId())
                    || !stagedRecord.version().equals(storedPackage.version())
                    || !stagedRecord.packageRevision().equals(storedPackage.packageDigest())) {
                    throw error(ArtifactPhase.COMMIT,
                        "plugin package object changed before commit",
                        stagedRecord.location(), null);
                }
                forcePackageContent(stagedRecord.location());
                var directory = recordDirectory(stagedRecord.pluginId());
                var path = recordPath(stagedRecord.pluginId(),
                    stagedRecord.packageRevision());
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    result = readStoredRecord(stagedRecord.pluginId(),
                        stagedRecord.packageRevision(), path);
                    io.force(path);
                    forceDirectories(directory, records);
                } else {
                    result = stagedRecord.toBuilder().state(ArtifactState.INSTALLED)
                        .updatedAt(Instant.now()).build();
                    writeRecord(directory, result);
                }
                deleteTree(transactionPath);
                state = State.SAVED;
                return result;
            } catch (ArtifactException exception) {
                throw exception;
            } catch (IOException exception) {
                throw error(ArtifactPhase.COMMIT,
                    "cannot save plugin package", transactionPath, exception);
            } finally {
                operationLock.unlock();
            }
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
                    "cannot compensate plugin package transaction",
                    transactionPath, exception);
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
                throw new IllegalStateException(
                    "plugin package transaction is " + state);
            }
        }
    }

    interface StorageIo {
        void force(Path path) throws IOException;

        default void copy(Path source, Path target) throws IOException {
            PluginPackageStore.copy(source, target);
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
