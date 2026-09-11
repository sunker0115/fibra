package com.sstlfsj.fibra.plugins.fs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileSystemContractTest {
    @Test
    void targetVersionAndInfoRejectInvalidConstruction() {
        assertThrows(NullPointerException.class, () -> new FsTarget(null, "file.txt"));
        assertThrows(IllegalArgumentException.class, () -> new FsTarget("", "file.txt"));
        assertThrows(NullPointerException.class, () -> new FsTarget("target", null));
        assertThrows(IllegalArgumentException.class, () -> new FsTarget("target", ""));
        assertThrows(NullPointerException.class, () -> new FsVersion(null));
        assertThrows(IllegalArgumentException.class, () -> new FsVersion(""));
        assertThrows(NullPointerException.class, () -> new FsInfo(null, new FsVersion("v1"), null));
        assertThrows(NullPointerException.class, () -> new FsInfo(FsFileType.FILE, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new FsInfo(FsFileType.FILE, new FsVersion("v1"), -1L));
    }

    @Test
    void valuesKeepOpaqueTargetAndNullableSize() {
        var target = new FsTarget("opaque-key", "workspace/a.txt");
        var version = new FsVersion("revision-1");
        var info = new FsInfo(FsFileType.DIRECTORY, version, null);

        assertEquals("opaque-key", target.key());
        assertEquals("workspace/a.txt", target.displayPath());
        assertEquals(version, info.version());
        assertNull(info.sizeBytes());
    }

    @Test
    void writeIntentVariantsRejectNullVersion() {
        assertEquals(new FsWriteIntent.Unconditional(), new FsWriteIntent.Unconditional());
        assertEquals(new FsWriteIntent.CreateIfAbsent(), new FsWriteIntent.CreateIfAbsent());
        assertThrows(NullPointerException.class, () -> new FsWriteIntent.ReplaceIfVersion(null));
        assertEquals(new FsVersion("v2"),
            new FsWriteIntent.ReplaceIfVersion(new FsVersion("v2")).version());
    }

    @Test
    void editIntentExplicitlyModelsGuardedAndUnguardedCalls() {
        assertEquals(new FsEditIntent.Unconditional(), new FsEditIntent.Unconditional());
        assertThrows(NullPointerException.class, () -> new FsEditIntent.ReplaceIfVersion(null));
        assertEquals(new FsVersion("v2"),
            new FsEditIntent.ReplaceIfVersion(new FsVersion("v2")).version());
    }

    @Test
    void writeAndEditValuesEnforceTheirConstructionRules() {
        var version = new FsVersion("v2");
        assertThrows(NullPointerException.class, () -> new FsWriteResult(null, version, null, "after"));
        assertThrows(NullPointerException.class, () -> new FsWriteResult(FsWriteOperation.CREATE, null, null, "after"));
        assertThrows(NullPointerException.class, () -> new FsWriteResult(FsWriteOperation.CREATE, version, null, null));
        assertEquals(FsWriteOperation.UPDATE,
            new FsWriteResult(FsWriteOperation.UPDATE, version, null, "after").operation());

        assertThrows(NullPointerException.class, () -> new FsEdit(null, "new", false));
        assertThrows(IllegalArgumentException.class, () -> new FsEdit("", "new", false));
        assertThrows(NullPointerException.class, () -> new FsEdit("old", null, false));
        assertThrows(NullPointerException.class, () -> new FsEditResult(null, "before", "after"));
        assertThrows(NullPointerException.class, () -> new FsEditResult(version, null, "after"));
        assertThrows(NullPointerException.class, () -> new FsEditResult(version, "before", null));
    }

    @Test
    void serviceKeyHasTheStableNameAndType() {
        assertEquals("fibra.fs", FileSystemServices.FILE_SYSTEM.name());
        assertEquals(FileSystem.class, FileSystemServices.FILE_SYSTEM.type());
    }

    @Test
    void exceptionRetainsStableCodeAndCause() {
        var cause = new IOException("denied");
        var exception = new FsException(FsErrorCode.PERMISSION_DENIED, "cannot write", cause);

        assertEquals(FsErrorCode.PERMISSION_DENIED, exception.code());
        assertSame(cause, exception.getCause());
        assertThrows(NullPointerException.class, () -> new FsException(null, "message"));
        assertThrows(NullPointerException.class, () -> new FsException(FsErrorCode.IO_ERROR, null));
    }

    @Test
    void errorCodesExposeTheCompleteStableVocabulary() {
        assertEquals(List.of(
            FsErrorCode.NOT_FOUND,
            FsErrorCode.NOT_DIRECTORY,
            FsErrorCode.NOT_TEXT,
            FsErrorCode.NOT_REGULAR_FILE,
            FsErrorCode.TOO_LARGE,
            FsErrorCode.PERMISSION_DENIED,
            FsErrorCode.IO_ERROR,
            FsErrorCode.STALE_VERSION,
            FsErrorCode.NOT_OBSERVED,
            FsErrorCode.AMBIGUOUS_EDIT,
            FsErrorCode.EDIT_NOT_FOUND,
            FsErrorCode.ABORTED), List.of(FsErrorCode.values()));
    }

    @Test
    void manifestIsTheContractOnlyPluginWithTheProjectVersion() throws IOException {
        try (var input = FileSystemContractTest.class.getResourceAsStream("/META-INF/fibra/plugin.yaml")) {
            var manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("id: fibra-fs\nversion: "
                + System.getProperty("fibra.test.projectVersion") + "\nrequires: []\n", manifest);
        }
    }
}
