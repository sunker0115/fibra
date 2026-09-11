package com.sstlfsj.fibra.plugins.fs.local;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WindowsFilePublisherTest {
    private static final Path TARGET = Path.of("target.txt");
    private static final Path TEMPORARY = Path.of(".fibra.tmp");

    @Test
    void copiesProtectedDaclBeforeReplacingExistingTarget() throws Exception {
        var operations = new RecordingOperations();
        var publisher = new WindowsFilePublisher(operations);

        publisher.prepareReplacement(TARGET, TEMPORARY);
        publisher.replaceExisting(TARGET, TEMPORARY);

        assertEquals(List.of("read:target.txt", "set:.fibra.tmp", "replace:target.txt:.fibra.tmp"),
            operations.calls);
        assertArrayEquals(new byte[] {1, 2, 3}, operations.installedDacl);
    }

    @Test
    void exposesTargetMissingOnlyWhenReplaceLosesTheRace() throws Exception {
        var operations = new RecordingOperations();
        operations.replaceFailure = new WindowsFilePublisher.NativeFailure("ReplaceFileW", TARGET,
            WindowsFilePublisher.ERROR_FILE_NOT_FOUND);
        var publisher = new WindowsFilePublisher(operations);

        publisher.prepareReplacement(TARGET, TEMPORARY);
        var failure = assertThrows(WindowsFilePublisher.TargetMissingException.class,
            () -> publisher.replaceExisting(TARGET, TEMPORARY));

        assertEquals(TARGET, failure.path());
        assertEquals(List.of("read:target.txt", "set:.fibra.tmp", "replace:target.txt:.fibra.tmp"),
            operations.calls);
    }

    @Test
    void mapsAccessDeniedWithoutAttemptingReplacement() {
        var operations = new RecordingOperations();
        operations.readFailure = new WindowsFilePublisher.NativeFailure("GetFileSecurityW", TARGET,
            WindowsFilePublisher.ERROR_ACCESS_DENIED);
        var publisher = new WindowsFilePublisher(operations);

        var failure = assertThrows(IOException.class,
            () -> publisher.prepareReplacement(TARGET, TEMPORARY));

        assertEquals(AccessDeniedException.class, failure.getClass());
        assertEquals(List.of("read:target.txt"), operations.calls);
    }

    @Test
    void createsExtendedLengthDriveAndUncPaths() {
        assertEquals("\\\\?\\C:\\work\\file.txt",
            WindowsFilePublisher.toNamespacedPath("C:\\work\\file.txt"));
        assertEquals("\\\\?\\UNC\\server\\share\\file.txt",
            WindowsFilePublisher.toNamespacedPath("\\\\server\\share\\file.txt"));
        assertEquals("\\\\?\\C:\\work\\file.txt",
            WindowsFilePublisher.toNamespacedPath("\\\\?\\C:\\work\\file.txt"));
    }

    @Test
    void exposesWindowsChangeTimeForVersionTokens() throws Exception {
        var operations = new RecordingOperations();
        operations.changeTime = 42L;
        var publisher = new WindowsFilePublisher(operations);

        assertEquals(42L, publisher.changeTime(TARGET));
        assertEquals(List.of("change:target.txt"), operations.calls);
    }

    private static final class RecordingOperations implements WindowsFilePublisher.NativeOperations {
        private final List<String> calls = new ArrayList<>();
        private byte[] installedDacl;
        private long changeTime;
        private IOException readFailure;
        private IOException replaceFailure;

        @Override
        public long readChangeTime(Path path) {
            calls.add("change:" + path);
            return changeTime;
        }

        @Override
        public byte[] readDacl(Path target) throws IOException {
            calls.add("read:" + target);
            if (readFailure != null) throw readFailure;
            return new byte[] {1, 2, 3};
        }

        @Override
        public void setProtectedDacl(Path temporary, byte[] dacl) {
            calls.add("set:" + temporary);
            installedDacl = dacl.clone();
        }

        @Override
        public void replaceFile(Path target, Path temporary) throws IOException {
            calls.add("replace:" + target + ':' + temporary);
            if (replaceFailure != null) throw replaceFailure;
        }
    }
}
