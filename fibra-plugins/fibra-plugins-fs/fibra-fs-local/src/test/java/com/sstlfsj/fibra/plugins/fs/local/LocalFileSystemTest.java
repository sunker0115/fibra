package com.sstlfsj.fibra.plugins.fs.local;

import com.sstlfsj.fibra.CancellationSource;
import com.sstlfsj.fibra.CancellationToken;
import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.plugins.fs.FsEdit;
import com.sstlfsj.fibra.plugins.fs.FsEditIntent;
import com.sstlfsj.fibra.plugins.fs.FsErrorCode;
import com.sstlfsj.fibra.plugins.fs.FsException;
import com.sstlfsj.fibra.plugins.fs.FsFileType;
import com.sstlfsj.fibra.plugins.fs.FsWriteIntent;
import com.sstlfsj.fibra.plugins.fs.FsWriteOperation;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileSystemTest {
    @TempDir
    Path work;
    private final FibraRuntime runtime = FibraRuntime.create();

    @AfterEach
    void closeRuntime() {
        runtime.close();
    }

    @Test
    void resolvesOpaqueTargetsAndReportsFileMetadata() throws Exception {
        Files.writeString(work.resolve("note.txt"), "hello", StandardCharsets.UTF_8);
        var fileSystem = new LocalFileSystem(work);

        var target = fileSystem.resolve(context(), "note.txt", ".").block();
        var info = fileSystem.stat(context(), target).block();

        assertFalse(target.key().contains("note.txt"));
        assertEquals("note.txt", target.displayPath());
        assertEquals(FsFileType.FILE, info.type());
        assertEquals(5L, info.sizeBytes());

        var sameTarget = fileSystem.resolve(context(), "note.txt", ".").block();
        assertEquals(target.key(), sameTarget.key());
        assertEquals(".", fileSystem.resolve(context(), ".", ".").block().displayPath());
    }

    @Test
    void blockingFileOperationsRunOffTheSubscriberThread() {
        var observed = new AtomicReference<Thread>();
        var token = new CancellationToken() {
            @Override
            public boolean isCancelled() {
                observed.compareAndSet(null, Thread.currentThread());
                return false;
            }

            @Override
            public reactor.core.publisher.Mono<Void> cancelled() {
                return reactor.core.publisher.Mono.never();
            }
        };
        var fileSystem = new LocalFileSystem(work);
        var subscriberThread = Thread.currentThread();

        fileSystem.resolve(context().withCancellation(token), "note.txt", ".").block();

        assertNotNull(observed.get());
        assertNotSame(subscriberThread, observed.get());
    }

    @Test
    void basicVersionChangesWhenWindowsChangeTimeChanges() {
        var before = LocalFileSystem.basicVersion("same-file", 4, 10, 20);
        var after = LocalFileSystem.basicVersion("same-file", 4, 10, 21);

        assertFalse(before.equals(after));
    }

    @Test
    void resolvesSymlinkAliasesToOneIdentityAndWritesTheRealTarget() throws Exception {
        Files.writeString(work.resolve("real.txt"), "hello", StandardCharsets.UTF_8);
        Files.createSymbolicLink(work.resolve("link.txt"), work.resolve("real.txt"));
        var fileSystem = new LocalFileSystem(work);
        var real = fileSystem.resolve(context(), "real.txt", ".").block();
        var link = fileSystem.resolve(context(), "link.txt", ".").block();

        assertEquals(real.key(), link.key());
        fileSystem.editText(context(), link, new FsEdit("hello", "bye", false),
            new FsEditIntent.Unconditional()).block();

        assertTrue(Files.isSymbolicLink(work.resolve("link.txt")));
        assertEquals("bye", Files.readString(work.resolve("real.txt")));
    }

    @Test
    void enforcesTextSizeTypeAndUtf8Boundaries() throws Exception {
        Files.createDirectory(work.resolve("folder"));
        Files.write(work.resolve("binary.bin"), new byte[] {(byte) 0xc3, (byte) 0x28});
        Files.writeString(work.resolve("large.txt"), "12345", StandardCharsets.UTF_8);
        var fileSystem = new LocalFileSystem(work);

        assertCode(FsErrorCode.NOT_DIRECTORY, () -> fileSystem.readText(context(),
            fileSystem.resolve(context(), "folder", ".").block(), 10).block());
        assertCode(FsErrorCode.NOT_TEXT, () -> fileSystem.readText(context(),
            fileSystem.resolve(context(), "binary.bin", ".").block(), 10).block());
        assertCode(FsErrorCode.TOO_LARGE, () -> fileSystem.readText(context(),
            fileSystem.resolve(context(), "large.txt", ".").block(), 4).block());
    }

    @Test
    void writesAtomicallyWithCreateAndVersionGuards() throws Exception {
        var fileSystem = new LocalFileSystem(work);
        var target = fileSystem.resolve(context(), "created.txt", ".").block();

        var created = fileSystem.writeText(context(), target, "one",
            new FsWriteIntent.CreateIfAbsent()).block();
        var observed = fileSystem.stat(context(), target).block().version();
        var updated = fileSystem.writeText(context(), target, "two",
            new FsWriteIntent.ReplaceIfVersion(observed)).block();

        assertEquals(FsWriteOperation.CREATE, created.operation());
        assertNull(created.before());
        assertEquals(FsWriteOperation.UPDATE, updated.operation());
        assertEquals("one", updated.before());
        assertEquals("two", Files.readString(work.resolve("created.txt")));
        assertCode(FsErrorCode.STALE_VERSION, () -> fileSystem.writeText(context(), target, "three",
            new FsWriteIntent.ReplaceIfVersion(observed)).block());
        assertCode(FsErrorCode.STALE_VERSION, () -> fileSystem.writeText(context(),
            fileSystem.resolve(context(), "missing.txt", ".").block(), "three",
            new FsWriteIntent.ReplaceIfVersion(observed)).block());
    }

    @Test
    void statUsesMetadataWithoutOpeningFileContent() throws Exception {
        var path = work.resolve("metadata-only.txt");
        Files.writeString(path, "secret", StandardCharsets.UTF_8);
        Assumptions.assumeTrue(path.getFileSystem().supportedFileAttributeViews().contains("posix"));
        var original = Files.getPosixFilePermissions(path);
        Files.setPosixFilePermissions(path, Set.of());
        try {
            var fileSystem = new LocalFileSystem(work);
            var target = fileSystem.resolve(context(), "metadata-only.txt", ".").block();

            var info = fileSystem.stat(context(), target).block();

            assertEquals(FsFileType.FILE, info.type());
            assertEquals(6L, info.sizeBytes());
            assertCode(FsErrorCode.TOO_LARGE,
                () -> fileSystem.readText(context(), target, 5).block());
        } finally {
            Files.setPosixFilePermissions(path, original);
        }
    }

    @Test
    void guardedWritesUseDshFailureCodesAndNeverOverwriteACompetingCreate() throws Exception {
        Files.writeString(work.resolve("existing.txt"), "old", StandardCharsets.UTF_8);
        var fileSystem = new LocalFileSystem(work);
        var existing = fileSystem.resolve(context(), "existing.txt", ".").block();
        assertCode(FsErrorCode.NOT_OBSERVED, () -> fileSystem.writeText(context(), existing,
            "new", new FsWriteIntent.CreateIfAbsent()).block());

        var racedPath = work.resolve("raced.txt");
        var raced = fileSystem.resolve(context(), "raced.txt", ".").block();
        var checks = new AtomicInteger();
        var racingToken = new com.sstlfsj.fibra.CancellationToken() {
            @Override
            public boolean isCancelled() {
                if (checks.incrementAndGet() == 2) {
                    try {
                        Files.writeString(racedPath, "competitor", StandardCharsets.UTF_8);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                }
                return false;
            }

            @Override
            public reactor.core.publisher.Mono<Void> cancelled() {
                return reactor.core.publisher.Mono.never();
            }
        };
        assertCode(FsErrorCode.NOT_OBSERVED, () -> fileSystem.writeText(
            context().withCancellation(racingToken), raced, "ours",
            new FsWriteIntent.CreateIfAbsent()).block());
        assertEquals("competitor", Files.readString(racedPath));
    }

    @Test
    void unrelatedTargetsDoNotShareOneMutationLock() throws Exception {
        var fileSystem = new LocalFileSystem(work);
        var blocker = publicationBlocker();
        var firstTarget = fileSystem.resolve(context(), "first.txt", ".").block();
        var secondTarget = fileSystem.resolve(context(), "second.txt", ".").block();
        try (var executor = Executors.newCachedThreadPool()) {
            var first = executor.submit(() -> fileSystem.writeText(
                context().withCancellation(blocker.token()), firstTarget, "first",
                new FsWriteIntent.Unconditional()).block());
            assertTrue(blocker.blocked().await(2, TimeUnit.SECONDS));
            var second = executor.submit(() -> fileSystem.writeText(context(), secondTarget,
                "second", new FsWriteIntent.Unconditional()).block());
            try {
                assertNotNull(second.get(2, TimeUnit.SECONDS));
            } finally {
                blocker.release().countDown();
                assertNotNull(first.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void cancelledSameTargetWaiterDoesNotWaitForTheActiveWrite() throws Exception {
        var fileSystem = new LocalFileSystem(work);
        var blocker = publicationBlocker();
        var target = fileSystem.resolve(context(), "same.txt", ".").block();
        try (var executor = Executors.newCachedThreadPool()) {
            var first = executor.submit(() -> fileSystem.writeText(
                context().withCancellation(blocker.token()), target, "first",
                new FsWriteIntent.Unconditional()).block());
            assertTrue(blocker.blocked().await(2, TimeUnit.SECONDS));
            var cancellation = new CancellationSource();
            var waiting = executor.submit(() -> fileSystem.writeText(
                context().withCancellation(cancellation.token()), target, "second",
                new FsWriteIntent.Unconditional()).block());
            cancellation.cancel();
            try {
                var failure = assertThrows(ExecutionException.class,
                    () -> waiting.get(2, TimeUnit.SECONDS));
                assertEquals(FsErrorCode.ABORTED, ((FsException) failure.getCause()).code());
            } finally {
                blocker.release().countDown();
                assertNotNull(first.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void rejectsNulTextAndKeepsOverwriteDiffReadsBounded() throws Exception {
        Files.write(work.resolve("nul.txt"), new byte[] {'a', 0, 'b'});
        Files.write(work.resolve("binary.bin"), new byte[] {(byte) 0xff, 1, 2});
        Files.writeString(work.resolve("large.txt"), "12345", StandardCharsets.UTF_8);
        var fileSystem = new LocalFileSystem(work, 4);

        assertCode(FsErrorCode.NOT_TEXT, () -> fileSystem.readText(context(),
            fileSystem.resolve(context(), "nul.txt", ".").block(), 16).block());
        var binary = fileSystem.writeText(context(),
            fileSystem.resolve(context(), "binary.bin", ".").block(), "text",
            new FsWriteIntent.Unconditional()).block();
        var large = fileSystem.writeText(context(),
            fileSystem.resolve(context(), "large.txt", ".").block(), "small",
            new FsWriteIntent.Unconditional()).block();

        assertNull(binary.before());
        assertNull(large.before());
        assertEquals("text", Files.readString(work.resolve("binary.bin")));
    }

    @Test
    void skipsContextualDiffWhenNewUtf8ContentReachesTheByteLimit() throws Exception {
        var path = work.resolve("utf8-limit.txt");
        Files.writeString(path, "old", StandardCharsets.UTF_8);
        var fileSystem = new LocalFileSystem(work, 3);
        var target = fileSystem.resolve(context(), "utf8-limit.txt", ".").block();

        var result = fileSystem.writeText(context(), target, "你",
            new FsWriteIntent.Unconditional()).block();

        assertNull(result.before());
        assertEquals("你", Files.readString(path));
    }

    @Test
    void optionalDiffReadTreatsPreOpenDisappearanceAsNoContext() throws Exception {
        var path = work.resolve("diff-race.txt");
        Files.writeString(path, "old", StandardCharsets.UTF_8);
        var fileSystem = new LocalFileSystem(work);
        Files.delete(path);

        assertNull(fileSystem.tryReadDiffBasis(context(), path));
    }

    @Test
    void spillDirectoryCannotCreateThroughAnEscapingSymlink(@TempDir Path outside) throws Exception {
        Files.createSymbolicLink(work.resolve("escape"), outside);
        var store = new LocalResultSpillStore(new LocalFileSystem(work), work,
            "escape/created");

        assertThrows(FsException.class,
            () -> store.store(context(), "result", "content").block());
        assertFalse(Files.exists(outside.resolve("created")));
    }

    @Test
    void deniedParentUsesPermissionFailureTaxonomy() throws Exception {
        var protectedDirectory = work.resolve("protected");
        Files.createDirectory(protectedDirectory);
        Assumptions.assumeTrue(work.getFileSystem().supportedFileAttributeViews().contains("posix"));
        var original = Files.getPosixFilePermissions(protectedDirectory);
        Files.setPosixFilePermissions(protectedDirectory, Set.of());
        try {
            var fileSystem = new LocalFileSystem(work);

            assertCode(FsErrorCode.PERMISSION_DENIED,
                () -> fileSystem.resolve(context(), "protected/file.txt", ".").block());
        } finally {
            Files.setPosixFilePermissions(protectedDirectory, original);
        }
    }

    @Test
    void editsOnceOrAllAndReturnsStableFailures() throws Exception {
        Files.writeString(work.resolve("note.txt"), "a a", StandardCharsets.UTF_8);
        var fileSystem = new LocalFileSystem(work);
        var target = fileSystem.resolve(context(), "note.txt", ".").block();

        assertCode(FsErrorCode.AMBIGUOUS_EDIT, () -> fileSystem.editText(context(), target,
            new FsEdit("a", "b", false), new FsEditIntent.Unconditional()).block());
        var edited = fileSystem.editText(context(), target, new FsEdit("a", "b", true),
            new FsEditIntent.Unconditional()).block();

        assertEquals("a a", edited.before());
        assertEquals("b b", edited.after());
        assertCode(FsErrorCode.EDIT_NOT_FOUND, () -> fileSystem.editText(context(), target,
            new FsEdit("missing", "x", false), new FsEditIntent.Unconditional()).block());
    }

    @Test
    void guardedEditsCheckFreshnessBeforeReadingOrMatching() throws Exception {
        var path = work.resolve("guarded.txt");
        Files.writeString(path, "hello", StandardCharsets.UTF_8);
        var fileSystem = new LocalFileSystem(work);
        var target = fileSystem.resolve(context(), "guarded.txt", ".").block();
        var observed = fileSystem.stat(context(), target).block().version();

        Files.write(path, new byte[] {'o', 0, 'h', 'e', 'r'});
        assertCode(FsErrorCode.STALE_VERSION, () -> fileSystem.editText(context(), target,
            new FsEdit("hello", "bye", false), new FsEditIntent.ReplaceIfVersion(observed)).block());

        Files.delete(path);
        assertCode(FsErrorCode.STALE_VERSION, () -> fileSystem.editText(context(), target,
            new FsEdit("hello", "bye", false), new FsEditIntent.ReplaceIfVersion(observed)).block());
        assertCode(FsErrorCode.STALE_VERSION, () -> fileSystem.editText(context(), target,
            new FsEdit("hello", "bye", false), new FsEditIntent.Unconditional()).block());
    }

    @Test
    void editNormalizesOperandsAndRestoresCrlfWithoutDoublingCarriageReturns() throws Exception {
        var path = work.resolve("crlf.txt");
        Files.writeString(path, "a\r\nOLD\r\nb\r\n", StandardCharsets.UTF_8);
        var fileSystem = new LocalFileSystem(work);
        var target = fileSystem.resolve(context(), "crlf.txt", ".").block();

        var result = fileSystem.editText(context(), target,
            new FsEdit("OLD\r\nb", "NEW\r\nb", false), new FsEditIntent.Unconditional()).block();

        assertEquals("a\nOLD\nb\n", result.before());
        assertEquals("a\nNEW\nb\n", result.after());
        assertEquals("a\r\nNEW\r\nb\r\n", Files.readString(path));
    }

    @Test
    void editRejectsNulBytesBeyondTheReadSamplingWindow() throws Exception {
        var path = work.resolve("late-nul.txt");
        var content = new byte[9_000];
        java.util.Arrays.fill(content, (byte) 'a');
        System.arraycopy("needle".getBytes(StandardCharsets.UTF_8), 0, content, 0, 6);
        content[8_500] = 0;
        Files.write(path, content);
        var fileSystem = new LocalFileSystem(work);
        var target = fileSystem.resolve(context(), "late-nul.txt", ".").block();

        assertCode(FsErrorCode.NOT_TEXT, () -> fileSystem.editText(context(), target,
            new FsEdit("needle", "changed", false), new FsEditIntent.Unconditional()).block());
        assertTrue(java.util.Arrays.equals(content, Files.readAllBytes(path)));
    }

    @Test
    void atomicReplacementPreservesExistingPosixPermissions() throws Exception {
        var path = work.resolve("executable.sh");
        Files.writeString(path, "old", StandardCharsets.UTF_8);
        Assumptions.assumeTrue(path.getFileSystem().supportedFileAttributeViews().contains("posix"));
        var expected = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ);
        Files.setPosixFilePermissions(path, expected);
        var fileSystem = new LocalFileSystem(work);
        var target = fileSystem.resolve(context(), "executable.sh", ".").block();

        fileSystem.writeText(context(), target, "next", new FsWriteIntent.Unconditional()).block();
        assertEquals(expected, Files.getPosixFilePermissions(path));

        fileSystem.editText(context(), target, new FsEdit("next", "done", false),
            new FsEditIntent.Unconditional()).block();
        assertEquals(expected, Files.getPosixFilePermissions(path));
    }

    @Test
    void replacementRetainsInitiallyObservedPosixModeWhenTargetDisappearsDuringDiffRead()
        throws Exception {
        var path = work.resolve("recreated.txt");
        Files.writeString(path, "old", StandardCharsets.UTF_8);
        Assumptions.assumeTrue(path.getFileSystem().supportedFileAttributeViews().contains("posix"));
        var expected = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ);
        Files.setPosixFilePermissions(path, expected);
        var checks = new AtomicInteger();
        var token = new CancellationToken() {
            @Override
            public boolean isCancelled() {
                if (checks.incrementAndGet() == 4) {
                    try {
                        Files.delete(path);
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                }
                return false;
            }

            @Override
            public reactor.core.publisher.Mono<Void> cancelled() {
                return reactor.core.publisher.Mono.never();
            }
        };
        var fileSystem = new LocalFileSystem(work);
        var target = fileSystem.resolve(context(), "recreated.txt", ".").block();

        var result = fileSystem.writeText(context().withCancellation(token), target, "new",
            new FsWriteIntent.Unconditional()).block();

        assertEquals(FsWriteOperation.UPDATE, result.operation());
        assertEquals("new", Files.readString(path));
        assertEquals(expected, Files.getPosixFilePermissions(path));
    }

    @Test
    void windowsReplacementIsPreparedBeforeContentAndRecreatesALostTarget() throws Exception {
        var path = work.resolve("windows.txt");
        Files.writeString(path, "old", StandardCharsets.UTF_8);
        var preparedEmpty = new AtomicReference<Boolean>();
        var publisher = new WindowsFilePublisher(new WindowsFilePublisher.NativeOperations() {
            @Override
            public long readChangeTime(Path target) {
                return 0;
            }

            @Override
            public byte[] readDacl(Path target) {
                return new byte[] {1};
            }

            @Override
            public void setProtectedDacl(Path temporary, byte[] dacl) throws IOException {
                preparedEmpty.set(Files.size(temporary) == 0);
            }

            @Override
            public void replaceFile(Path target, Path temporary) throws IOException {
                Files.delete(target);
                throw new WindowsFilePublisher.NativeFailure("ReplaceFileW", target,
                    WindowsFilePublisher.ERROR_FILE_NOT_FOUND);
            }
        });
        var fileSystem = new LocalFileSystem(work, 1_024, publisher);
        var target = fileSystem.resolve(context(), "windows.txt", ".").block();

        fileSystem.writeText(context(), target, "new", new FsWriteIntent.Unconditional()).block();

        assertEquals(Boolean.TRUE, preparedEmpty.get());
        assertEquals("new", Files.readString(path));
    }

    @Test
    void windowsReplacementDoesNotRecreateBeforeTheDaclWasCopied() throws Exception {
        var path = work.resolve("windows-disappeared.txt");
        Files.writeString(path, "old", StandardCharsets.UTF_8);
        var checks = new AtomicInteger();
        var token = new CancellationToken() {
            @Override
            public boolean isCancelled() {
                if (checks.incrementAndGet() == 4) {
                    try {
                        Files.delete(path);
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                }
                return false;
            }

            @Override
            public reactor.core.publisher.Mono<Void> cancelled() {
                return reactor.core.publisher.Mono.never();
            }
        };
        var publisher = new WindowsFilePublisher(new WindowsFilePublisher.NativeOperations() {
            @Override
            public long readChangeTime(Path target) {
                return 0;
            }

            @Override
            public byte[] readDacl(Path target) throws IOException {
                if (!Files.exists(target)) {
                    throw new WindowsFilePublisher.NativeFailure("GetFileSecurityW", target,
                        WindowsFilePublisher.ERROR_FILE_NOT_FOUND);
                }
                return new byte[] {1};
            }

            @Override
            public void setProtectedDacl(Path temporary, byte[] dacl) {
            }

            @Override
            public void replaceFile(Path target, Path temporary) {
            }
        });
        var fileSystem = new LocalFileSystem(work, 1_024, publisher);
        var target = fileSystem.resolve(context(), "windows-disappeared.txt", ".").block();

        assertCode(FsErrorCode.NOT_FOUND, () -> fileSystem.writeText(
            context().withCancellation(token), target, "new",
            new FsWriteIntent.Unconditional()).block());

        assertFalse(Files.exists(path));
    }

    @Test
    void atomicWriteUsesPrivateStagingAndCleanupCannotReverseACommit() throws Exception {
        Assumptions.assumeTrue(work.getFileSystem().supportedFileAttributeViews().contains("posix"));
        var fileSystem = new LocalFileSystem(work);
        var blocker = publicationBlocker();
        var target = fileSystem.resolve(context(), "staged.txt", ".").block();
        Path staging;
        try (var executor = Executors.newCachedThreadPool()) {
            var write = executor.submit(() -> fileSystem.writeText(
                context().withCancellation(blocker.token()), target, "committed",
                new FsWriteIntent.Unconditional()).block());
            try {
                assertTrue(blocker.blocked().await(2, TimeUnit.SECONDS));
                try (var children = Files.list(work)) {
                    var directories = children.filter(Files::isDirectory).toList();
                    assertEquals(1, directories.size());
                    staging = directories.getFirst();
                }
                assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(staging));
                try (var children = Files.list(staging)) {
                    var files = children.toList();
                    assertEquals(1, files.size());
                    assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                        Files.getPosixFilePermissions(files.getFirst()));
                }
                Files.writeString(staging.resolve("cleanup-blocker"), "keep", StandardCharsets.UTF_8);
            } finally {
                blocker.release().countDown();
                assertNotNull(write.get(2, TimeUnit.SECONDS));
            }
        }

        assertEquals("committed", Files.readString(work.resolve("staged.txt")));
        assertTrue(Files.exists(staging.resolve("cleanup-blocker")));
    }

    @Test
    void pathThroughRegularFileUsesAbsentAndStaleFailureSemantics() throws Exception {
        var fileSystem = new LocalFileSystem(work);
        var target = fileSystem.resolve(context(), "parent/child.txt", ".").block();
        Files.writeString(work.resolve("parent"), "file", StandardCharsets.UTF_8);

        assertCode(FsErrorCode.NOT_FOUND,
            () -> fileSystem.resolve(context(), "parent/child.txt", ".").block());
        assertCode(FsErrorCode.NOT_FOUND, () -> fileSystem.stat(context(), target).block());
        assertCode(FsErrorCode.STALE_VERSION, () -> fileSystem.editText(context(), target,
            new FsEdit("old", "new", false), new FsEditIntent.Unconditional()).block());
        assertCode(FsErrorCode.STALE_VERSION, () -> fileSystem.writeText(context(), target, "new",
            new FsWriteIntent.ReplaceIfVersion(new com.sstlfsj.fibra.plugins.fs.FsVersion("old"))).block());
    }

    @Test
    void observesCancellationBeforePublication() {
        var source = new CancellationSource();
        source.cancel();
        var fileSystem = new LocalFileSystem(work);
        var invocation = context().withCancellation(source.token());
        var target = fileSystem.resolve(context(), "cancelled.txt", ".").block();

        assertCode(FsErrorCode.ABORTED, () -> fileSystem.writeText(invocation, target, "value",
            new FsWriteIntent.Unconditional()).block());
    }

    @Test
    void postCommitProbeUsesASentinelWhenTheTargetHasAlreadyDisappeared() throws Exception {
        var path = work.resolve("committed-then-removed.txt");
        Files.writeString(path, "committed", StandardCharsets.UTF_8);
        var fileSystem = new LocalFileSystem(work);
        Files.delete(path);

        assertTrue(fileSystem.versionAfterWrite(path).value().startsWith("missing:local:"));
    }

    private InvocationContext context() {
        return InvocationContext.of(runtime.rootScope().context(), "test");
    }

    private static void assertCode(FsErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(FsException.class, action).code());
    }

    private static PublicationBlocker publicationBlocker() {
        var checks = new AtomicInteger();
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var token = new CancellationToken() {
            @Override
            public boolean isCancelled() {
                if (checks.incrementAndGet() == 4) {
                    blocked.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                }
                return false;
            }

            @Override
            public reactor.core.publisher.Mono<Void> cancelled() {
                return reactor.core.publisher.Mono.never();
            }
        };
        return new PublicationBlocker(token, blocked, release);
    }

    private record PublicationBlocker(CancellationToken token, CountDownLatch blocked,
                                      CountDownLatch release) {
    }
}
