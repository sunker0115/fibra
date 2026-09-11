package com.sstlfsj.fibra.plugins.fs.local;

import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.plugins.fs.FileSystem;
import com.sstlfsj.fibra.plugins.fs.FsEdit;
import com.sstlfsj.fibra.plugins.fs.FsEditIntent;
import com.sstlfsj.fibra.plugins.fs.FsEditResult;
import com.sstlfsj.fibra.plugins.fs.FsErrorCode;
import com.sstlfsj.fibra.plugins.fs.FsException;
import com.sstlfsj.fibra.plugins.fs.FsFileType;
import com.sstlfsj.fibra.plugins.fs.FsInfo;
import com.sstlfsj.fibra.plugins.fs.FsTarget;
import com.sstlfsj.fibra.plugins.fs.FsVersion;
import com.sstlfsj.fibra.plugins.fs.FsWriteIntent;
import com.sstlfsj.fibra.plugins.fs.FsWriteOperation;
import com.sstlfsj.fibra.plugins.fs.FsWriteResult;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** A root-confined local implementation whose targets are stable, non-retained opaque handles. */
public final class LocalFileSystem implements FileSystem {
    private static final long DEFAULT_DIFF_BASIS_MAX_BYTES = 10L * 1_024 * 1_024;
    private static final long MAX_EDIT_BYTES = 64L * 1_024 * 1_024;
    private static final int BINARY_SAMPLE_BYTES = 8_192;
    private static final String TARGET_PREFIX = "local:";

    private final ConcurrentHashMap<Path, TargetLock> writeLocks = new ConcurrentHashMap<>();
    private final Path root;
    private final Path realRoot;
    private final long diffBasisMaxBytes;
    private final WindowsFilePublisher windowsPublisher;

    public LocalFileSystem(Path root) {
        this(root, DEFAULT_DIFF_BASIS_MAX_BYTES);
    }

    LocalFileSystem(Path root, long diffBasisMaxBytes) {
        this(root, diffBasisMaxBytes,
            System.getProperty("os.name", "").startsWith("Windows")
                ? WindowsFilePublisher.system() : null);
    }

    LocalFileSystem(Path root, long diffBasisMaxBytes, WindowsFilePublisher windowsPublisher) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        if (diffBasisMaxBytes <= 0) {
            throw new IllegalArgumentException("diffBasisMaxBytes must be positive");
        }
        this.windowsPublisher = windowsPublisher;
        this.diffBasisMaxBytes = diffBasisMaxBytes;
        try {
            if (!Files.isDirectory(this.root)) {
                throw new IllegalArgumentException("root must be an existing directory");
            }
            realRoot = this.root.toRealPath();
        } catch (IOException failure) {
            throw new IllegalArgumentException("root is not accessible", failure);
        }
    }

    @Override
    public Mono<FsTarget> resolve(InvocationContext context, String path, String cwd) {
        return io(() -> {
            checkCancelled(context);
            var displayPath = resolveDisplayPath(path, cwd);
            if (blockedByNonDirectory(displayPath)) {
                throw error(FsErrorCode.NOT_FOUND, "a parent path segment is not a directory");
            }
            var targetPath = canonicalize(displayPath);
            verifyCanonicalWithinRoot(targetPath);
            return new FsTarget(encodeTarget(targetPath), display(displayPath));
        });
    }

    @Override
    public Mono<FsInfo> stat(InvocationContext context, FsTarget target) {
        return io(() -> {
            checkCancelled(context);
            var path = targetPath(target);
            var metadata = metadata(path);
            checkCancelled(context);
            return new FsInfo(metadata.type(), metadata.version(),
                metadata.type() == FsFileType.FILE ? metadata.size() : null);
        });
    }

    @Override
    public Mono<String> readText(InvocationContext context, FsTarget target, long maxBytes) {
        return io(() -> {
            checkCancelled(context);
            if (maxBytes <= 0) throw invalid("maxBytes must be positive");
            return decode(readBounded(context, regularFile(targetPath(target)), maxBytes));
        });
    }

    @Override
    public Mono<FsWriteResult> writeText(InvocationContext context, FsTarget target, String content,
                                         FsWriteIntent intent) {
        return io(() -> {
            var path = targetPath(target);
            return withWriteLock(context, path, () -> writeNow(context, path, content, intent));
        });
    }

    @Override
    public Mono<FsEditResult> editText(InvocationContext context, FsTarget target, FsEdit edit,
                                       FsEditIntent intent) {
        return io(() -> {
            Objects.requireNonNull(edit, "edit");
            Objects.requireNonNull(intent, "intent");
            var path = targetPath(target);
            return withWriteLock(context, path, () -> {
                checkCancelled(context);
                var metadata = editMetadata(path);
                if (metadata.type() != FsFileType.FILE) {
                    throw error(FsErrorCode.NOT_REGULAR_FILE, "path is not a regular file");
                }
                verifyEditVersion(metadata, intent);
                var security = captureSecurity(path);
                var stored = decodeForEdit(readBounded(context, path, MAX_EDIT_BYTES));
                var before = normalizeLineEndings(stored);
                var oldText = normalizeLineEndings(edit.oldText());
                var newText = normalizeLineEndings(edit.newText());
                var occurrences = occurrences(before, oldText);
                if (occurrences == 0) throw error(FsErrorCode.EDIT_NOT_FOUND, "text was not found");
                if (!edit.replaceAll() && occurrences != 1) {
                    throw error(FsErrorCode.AMBIGUOUS_EDIT, "text occurs more than once");
                }
                var after = edit.replaceAll() ? before.replace(oldText, newText)
                    : before.replaceFirst(java.util.regex.Pattern.quote(oldText),
                        java.util.regex.Matcher.quoteReplacement(newText));
                writeAtomically(context, path, restoreLineEndings(after, stored), true, false,
                    security);
                return new FsEditResult(versionAfterWrite(path), before, after);
            });
        });
    }

    Mono<Void> ensureDirectory(InvocationContext context, String relativeDirectory) {
        return io(() -> {
            checkCancelled(context);
            var path = canonicalize(resolveDisplayPath(relativeDirectory, "."));
            verifyCanonicalWithinRoot(path);
            try {
                Files.createDirectories(path);
                if (!Files.isDirectory(path)) {
                    throw error(FsErrorCode.NOT_DIRECTORY, "path is not a directory");
                }
            } catch (AccessDeniedException failure) {
                throw error(FsErrorCode.PERMISSION_DENIED, "cannot create directory", failure);
            } catch (IOException failure) {
                throw error(FsErrorCode.IO_ERROR, "cannot create directory", failure);
            }
            return true;
        }).then();
    }

    private FsWriteResult writeNow(InvocationContext context, Path path, String content,
                                   FsWriteIntent intent) throws IOException {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(intent, "intent");
        checkCancelled(context);
        verifyCanonicalWithinRoot(canonicalize(path));
        var exists = Files.exists(path);
        if (exists) regularFile(path);
        verifyWriteIntent(path, exists, intent);
        var security = exists ? captureSecurity(path) : FileSecurity.NONE;
        var before = exists && utf8LengthBelow(content, diffBasisMaxBytes)
            ? tryReadDiffBasis(context, path) : null;
        writeAtomically(context, path, content, exists,
            intent instanceof FsWriteIntent.CreateIfAbsent, security);
        return new FsWriteResult(exists ? FsWriteOperation.UPDATE : FsWriteOperation.CREATE,
            versionAfterWrite(path), before, normalizeLineEndings(content));
    }

    private void verifyWriteIntent(Path path, boolean exists, FsWriteIntent intent) throws IOException {
        if (intent instanceof FsWriteIntent.CreateIfAbsent && exists) {
            throw error(FsErrorCode.NOT_OBSERVED, "file already exists");
        }
        if (intent instanceof FsWriteIntent.ReplaceIfVersion guard) {
            if (!exists) throw error(FsErrorCode.STALE_VERSION, "file was not observed");
            if (!version(path).equals(guard.version())) {
                throw error(FsErrorCode.STALE_VERSION, "file version is stale");
            }
        }
    }

    private void verifyEditVersion(PathMetadata metadata, FsEditIntent intent) {
        if (intent instanceof FsEditIntent.ReplaceIfVersion guard
            && !metadata.version().equals(guard.version())) {
            throw error(FsErrorCode.STALE_VERSION, "file version is stale");
        }
    }

    private void writeAtomically(InvocationContext context, Path path, String content,
                                 boolean observedExisting, boolean createIfAbsent,
                                 FileSecurity security) throws IOException {
        var parent = path.getParent();
        if (parent == null) throw error(FsErrorCode.PERMISSION_DENIED, "path escapes root");
        verifyCanonicalWithinRoot(canonicalize(parent));
        Files.createDirectories(parent);
        if (!Files.isDirectory(parent)) throw error(FsErrorCode.NOT_DIRECTORY, "parent is not a directory");
        var stagingDirectory = createPrivateStagingDirectory(parent);
        Path temporary = null;
        try {
            temporary = createPrivateTempFile(stagingDirectory);
            if (windowsPublisher != null && observedExisting) {
                windowsPublisher.prepareReplacement(path, temporary);
            }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
                var bytes = StandardCharsets.UTF_8.encode(content);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            if (security.permissions() != null) {
                Files.setPosixFilePermissions(temporary, security.permissions());
            }
            checkCancelled(context);
            if (windowsPublisher != null && observedExisting) {
                try {
                    windowsPublisher.replaceExisting(path, temporary);
                } catch (WindowsFilePublisher.TargetMissingException targetMissing) {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                }
            } else if (!createIfAbsent) {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } else {
                publishCreate(temporary, path);
            }
        } catch (IOException | RuntimeException failure) {
            try {
                cleanupStaging(stagingDirectory, temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof AtomicMoveNotSupportedException) {
                throw error(FsErrorCode.IO_ERROR, "atomic move is unavailable", failure);
            }
            throw failure;
        }
        try {
            cleanupStaging(stagingDirectory, temporary);
        } catch (IOException ignoredCommittedCleanupFailure) {
            // Publication is the commit point; staging residue cannot reverse success.
        }
    }

    private void publishCreate(Path temporary, Path path) throws IOException {
        try {
            Files.createLink(path, temporary);
        } catch (FileAlreadyExistsException failure) {
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw error(FsErrorCode.NOT_OBSERVED, "file already exists", failure);
            }
            throw error(FsErrorCode.NOT_REGULAR_FILE, "path is not a regular file", failure);
        }
    }

    String tryReadDiffBasis(InvocationContext context, Path path) {
        try {
            if (Files.size(path) >= diffBasisMaxBytes) return null;
            return normalizeLineEndings(decode(readBounded(context, path, diffBasisMaxBytes - 1)));
        } catch (FsException failure) {
            if (failure.code() == FsErrorCode.ABORTED) throw failure;
            return null;
        } catch (IOException | SecurityException failure) {
            return null;
        }
    }

    private byte[] readBounded(InvocationContext context, Path path, long maxBytes) throws IOException {
        if (Files.size(path) > maxBytes) throw error(FsErrorCode.TOO_LARGE, "file exceeds limit");
        var initialSize = (int) Math.min(Math.min(Files.size(path), maxBytes), 8_192);
        try (var input = Files.newInputStream(path);
             var output = new ByteArrayOutputStream(initialSize)) {
            var buffer = new byte[8_192];
            long total = 0;
            for (int count; (count = input.read(buffer)) >= 0;) {
                checkCancelled(context);
                total += count;
                if (total > maxBytes || total > Integer.MAX_VALUE - 8L) {
                    throw error(FsErrorCode.TOO_LARGE, "file exceeds limit");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private Path targetPath(FsTarget target) {
        Objects.requireNonNull(target, "target");
        var key = target.key();
        if (!key.startsWith(TARGET_PREFIX)) throw error(FsErrorCode.NOT_FOUND, "unknown file target");
        try {
            var decoded = new String(Base64.getUrlDecoder().decode(key.substring(TARGET_PREFIX.length())),
                StandardCharsets.UTF_8);
            var path = Path.of(decoded).toAbsolutePath().normalize();
            verifyCanonicalWithinRoot(path);
            var current = canonicalize(path);
            verifyCanonicalWithinRoot(current);
            return current;
        } catch (IllegalArgumentException failure) {
            throw error(FsErrorCode.NOT_FOUND, "unknown file target", failure);
        }
    }

    private Path resolveDisplayPath(String path, String cwd) {
        if (path == null || path.isBlank()) throw invalid("path must not be blank");
        var base = cwd == null || cwd.isBlank() ? root : lexicalPath(cwd, root);
        return lexicalPath(path, base);
    }

    private Path lexicalPath(String value, Path base) {
        var candidate = Path.of(value);
        var result = (candidate.isAbsolute() ? candidate : base.resolve(candidate))
            .toAbsolutePath().normalize();
        if (!result.startsWith(root)) throw error(FsErrorCode.PERMISSION_DENIED, "path escapes root");
        verifyCanonicalWithinRoot(canonicalize(result));
        return result;
    }

    private Path canonicalize(Path path) {
        var missing = new ArrayDeque<Path>();
        var current = path.toAbsolutePath().normalize();
        while (current != null) {
            try {
                var result = current.toRealPath();
                for (var segment : missing) result = result.resolve(segment);
                return result.normalize();
            } catch (NoSuchFileException | NotDirectoryException failure) {
                if (current.getFileName() != null) missing.addFirst(current.getFileName());
                current = current.getParent();
            } catch (AccessDeniedException failure) {
                throw error(FsErrorCode.PERMISSION_DENIED, "cannot inspect path", failure);
            } catch (IOException failure) {
                if (blockedByNonDirectory(current)) {
                    if (current.getFileName() != null) missing.addFirst(current.getFileName());
                    current = current.getParent();
                    continue;
                }
                throw error(FsErrorCode.IO_ERROR, "cannot inspect path", failure);
            }
        }
        throw error(FsErrorCode.IO_ERROR, "cannot inspect path");
    }

    private void verifyCanonicalWithinRoot(Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(realRoot)) {
            throw error(FsErrorCode.PERMISSION_DENIED, "path escapes root");
        }
    }

    private String encodeTarget(Path path) {
        return TARGET_PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String display(Path path) {
        var relative = root.relativize(path);
        return relative.toString().isEmpty() ? "." : relative.toString().replace('\\', '/');
    }

    private Path regularFile(Path path) throws IOException {
        if (!Files.exists(path)) throw error(FsErrorCode.NOT_FOUND, "file does not exist");
        verifyCanonicalWithinRoot(canonicalize(path));
        if (Files.isDirectory(path)) throw error(FsErrorCode.NOT_DIRECTORY, "path is a directory");
        if (!Files.isRegularFile(path)) throw error(FsErrorCode.NOT_REGULAR_FILE, "path is not a regular file");
        return path;
    }

    private FsVersion version(Path path) throws IOException {
        return metadata(path).version();
    }

    FsVersion versionAfterWrite(Path path) throws IOException {
        try {
            return version(path);
        } catch (NoSuchFileException | NotDirectoryException failure) {
            return new FsVersion("missing:" + encodeTarget(path));
        }
    }

    private PathMetadata editMetadata(Path path) throws IOException {
        try {
            return metadata(path);
        } catch (NoSuchFileException | NotDirectoryException failure) {
            throw error(FsErrorCode.STALE_VERSION, "file version is stale", failure);
        }
    }

    private PathMetadata metadata(Path path) throws IOException {
        try {
            if (path.getFileSystem().supportedFileAttributeViews().contains("unix")) {
                Map<String, Object> attributes = Files.readAttributes(path, "unix:*");
                var type = Boolean.TRUE.equals(attributes.get("isRegularFile")) ? FsFileType.FILE
                    : Boolean.TRUE.equals(attributes.get("isDirectory")) ? FsFileType.DIRECTORY
                    : FsFileType.OTHER;
                var size = ((Number) attributes.get("size")).longValue();
                var version = new FsVersion("unix:" + attributes.get("dev") + ':'
                    + attributes.get("ino") + ':' + size + ':'
                    + nanos((FileTime) attributes.get("lastModifiedTime")) + ':'
                    + nanos((FileTime) attributes.get("ctime")));
                return new PathMetadata(type, version, size);
            }
            var attributes = Files.readAttributes(path, BasicFileAttributes.class);
            var type = attributes.isRegularFile() ? FsFileType.FILE
                : attributes.isDirectory() ? FsFileType.DIRECTORY : FsFileType.OTHER;
            var changeTime = windowsPublisher == null
                ? nanos(attributes.creationTime()) : windowsPublisher.changeTime(path);
            var version = basicVersion(attributes.fileKey(), attributes.size(),
                nanos(attributes.lastModifiedTime()), changeTime);
            return new PathMetadata(type, version, attributes.size());
        } catch (FileSystemException failure) {
            if (!blockedByNonDirectory(path)) throw failure;
            var absent = new NoSuchFileException(path.toString());
            absent.initCause(failure);
            throw absent;
        }
    }

    private static boolean blockedByNonDirectory(Path path) {
        for (var ancestor = path.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                return !Files.isDirectory(ancestor);
            }
        }
        return false;
    }

    static FsVersion basicVersion(Object fileKey, long size, long modifiedTime, long changeTime) {
        return new FsVersion("basic:" + Objects.toString(fileKey, "") + ':' + size + ':'
            + modifiedTime + ':' + changeTime);
    }

    private static boolean utf8LengthBelow(String value, long exclusiveLimit) {
        long length = 0;
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character <= 0x7f) length++;
            else if (character <= 0x7ff) length += 2;
            else if (Character.isHighSurrogate(character)
                && index + 1 < value.length()
                && Character.isLowSurrogate(value.charAt(index + 1))) {
                length += 4;
                index++;
            } else if (Character.isSurrogate(character)) length++;
            else length += 3;
            if (length >= exclusiveLimit) return false;
        }
        return true;
    }

    private static long nanos(FileTime time) {
        return time.to(TimeUnit.NANOSECONDS);
    }

    private static Path createPrivateTempFile(Path parent) throws IOException {
        if (parent.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            var permissions = EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE);
            var temporary = Files.createTempFile(parent, ".fibra-", ".tmp",
                PosixFilePermissions.asFileAttribute(permissions));
            Files.setPosixFilePermissions(temporary, permissions);
            return temporary;
        }
        return Files.createTempFile(parent, ".fibra-", ".tmp");
    }

    private static Path createPrivateStagingDirectory(Path parent) throws IOException {
        if (parent.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            var permissions = EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
            var staging = Files.createTempDirectory(parent, ".fibra-",
                PosixFilePermissions.asFileAttribute(permissions));
            Files.setPosixFilePermissions(staging, permissions);
            return staging;
        }
        return Files.createTempDirectory(parent, ".fibra-");
    }

    private static void cleanupStaging(Path stagingDirectory, Path temporary) throws IOException {
        IOException failure = null;
        if (temporary != null) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure = cleanupFailure;
            }
        }
        try {
            Files.deleteIfExists(stagingDirectory);
        } catch (IOException cleanupFailure) {
            if (failure == null) failure = cleanupFailure;
            else failure.addSuppressed(cleanupFailure);
        }
        if (failure != null) throw failure;
    }

    private static FileSecurity captureSecurity(Path path) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return new FileSecurity(Set.copyOf(Files.getPosixFilePermissions(path)));
        }
        return FileSecurity.NONE;
    }

    private static String decode(byte[] bytes) {
        rejectNul(bytes, Math.min(bytes.length, BINARY_SAMPLE_BYTES));
        return decodeUtf8(bytes);
    }

    private static String decodeForEdit(byte[] bytes) {
        rejectNul(bytes, bytes.length);
        return decodeUtf8(bytes);
    }

    private static void rejectNul(byte[] bytes, int limit) {
        for (var index = 0; index < limit; index++) {
            if (bytes[index] == 0) throw error(FsErrorCode.NOT_TEXT, "file contains NUL bytes");
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw error(FsErrorCode.NOT_TEXT, "file is not valid UTF-8", failure);
        }
    }

    private static String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n");
    }

    private static String restoreLineEndings(String value, String original) {
        var sampleLength = Math.min(original.length(), 8_192);
        var sample = original.substring(0, sampleLength);
        var crlf = 0;
        var lf = 0;
        for (var index = 0; index < sample.length(); index++) {
            if (sample.charAt(index) == '\n') {
                if (index > 0 && sample.charAt(index - 1) == '\r') crlf++;
                else lf++;
            }
        }
        return crlf > lf ? normalizeLineEndings(value).replace("\n", "\r\n") : value;
    }

    private static int occurrences(String value, String needle) {
        var result = 0;
        for (var offset = value.indexOf(needle); offset >= 0;
             offset = value.indexOf(needle, offset + needle.length())) result++;
        return result;
    }

    private <T> Mono<T> io(IoOperation<T> operation) {
        return Mono.fromCallable(operation::run)
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(this::translate);
    }

    private <T> T withWriteLock(InvocationContext context, Path path, IoOperation<T> operation)
        throws IOException {
        var targetLock = writeLocks.compute(path, (ignored, current) -> {
            var retained = current == null ? new TargetLock() : current;
            retained.users++;
            return retained;
        });
        var acquired = false;
        try {
            checkCancelled(context);
            while (!(acquired = targetLock.lock.tryLock(25, TimeUnit.MILLISECONDS))) {
                checkCancelled(context);
            }
            checkCancelled(context);
            return operation.run();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw error(FsErrorCode.ABORTED, "operation was interrupted", failure);
        } finally {
            if (acquired) targetLock.lock.unlock();
            writeLocks.computeIfPresent(path, (ignored, current) -> {
                if (current != targetLock) return current;
                current.users--;
                return current.users == 0 ? null : current;
            });
        }
    }

    private static void checkCancelled(InvocationContext context) {
        Objects.requireNonNull(context, "context");
        if (context.cancellation().isCancelled()) throw error(FsErrorCode.ABORTED, "operation was cancelled");
    }

    private static FsException invalid(String message) {
        return error(FsErrorCode.IO_ERROR, message);
    }

    private Throwable translate(Throwable failure) {
        if (failure instanceof FsException) return failure;
        if (failure instanceof AccessDeniedException || failure instanceof SecurityException) {
            return error(FsErrorCode.PERMISSION_DENIED, "access denied", failure);
        }
        if (failure instanceof NoSuchFileException || failure instanceof NotDirectoryException) {
            return error(FsErrorCode.NOT_FOUND, "file does not exist", failure);
        }
        if (failure instanceof FileSystemException fileFailure
            && fileFailure.getFile() != null
            && blockedByNonDirectory(Path.of(fileFailure.getFile()))) {
            return error(FsErrorCode.NOT_FOUND, "file does not exist", failure);
        }
        return error(FsErrorCode.IO_ERROR, "file operation failed", failure);
    }

    private static FsException error(FsErrorCode code, String message) {
        return new FsException(code, message);
    }

    private static FsException error(FsErrorCode code, String message, Throwable cause) {
        return new FsException(code, message, cause);
    }

    private record PathMetadata(FsFileType type, FsVersion version, long size) {
    }

    private record FileSecurity(Set<PosixFilePermission> permissions) {
        private static final FileSecurity NONE = new FileSecurity(null);
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws IOException;
    }

    private static final class TargetLock {
        private final ReentrantLock lock = new ReentrantLock(true);
        private int users;
    }
}
