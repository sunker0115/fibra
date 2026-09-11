package com.sstlfsj.fibra.plugins.fs.local;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

/** Windows-only secure replacement boundary for an already existing local file. */
final class WindowsFilePublisher {
    static final int ERROR_FILE_NOT_FOUND = 2;
    static final int ERROR_PATH_NOT_FOUND = 3;
    static final int ERROR_ACCESS_DENIED = 5;
    private static final int DACL_SECURITY_INFORMATION = 0x0000_0004;
    private static final int PROTECTED_DACL_SECURITY_INFORMATION = 0x8000_0000;
    private static final int FILE_READ_ATTRIBUTES = 0x0000_0080;
    private static final int FILE_SHARE_READ = 0x0000_0001;
    private static final int FILE_SHARE_WRITE = 0x0000_0002;
    private static final int FILE_SHARE_DELETE = 0x0000_0004;
    private static final int OPEN_EXISTING = 3;
    private static final int FILE_FLAG_BACKUP_SEMANTICS = 0x0200_0000;
    private static final int FILE_BASIC_INFO_CLASS = 0;

    private final NativeOperations operations;

    WindowsFilePublisher(NativeOperations operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    static WindowsFilePublisher system() {
        return new WindowsFilePublisher(new JnaNativeOperations());
    }

    /** Copies the existing target's protected DACL onto an empty staging file. */
    void prepareReplacement(Path target, Path temporary) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(temporary, "temporary");
        try {
            var dacl = operations.readDacl(target);
            operations.setProtectedDacl(temporary, dacl);
        } catch (NativeFailure failure) {
            throw map(failure);
        }
    }

    /**
     * Calls {@code ReplaceFileW} after the prepared staging file has been closed. A target which
     * disappears during this final call is reported separately so the caller may recreate it.
     */
    void replaceExisting(Path target, Path temporary) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(temporary, "temporary");
        try {
            operations.replaceFile(target, temporary);
        } catch (NativeFailure failure) {
            if (isMissing(failure.win32Code())) throw new TargetMissingException(target, failure);
            throw map(failure);
        }
    }

    long changeTime(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try {
            return operations.readChangeTime(path);
        } catch (NativeFailure failure) {
            throw map(failure);
        }
    }

    private static boolean isMissing(int win32Code) {
        return win32Code == ERROR_FILE_NOT_FOUND || win32Code == ERROR_PATH_NOT_FOUND;
    }

    private static IOException map(NativeFailure failure) {
        if (isMissing(failure.win32Code())) {
            return new NoSuchFileException(failure.path().toString());
        }
        if (failure.win32Code() == ERROR_ACCESS_DENIED) {
            return new AccessDeniedException(failure.path().toString());
        }
        return failure;
    }

    static String toNamespacedPath(String absolutePath) {
        if (absolutePath.startsWith("\\\\?\\")) return absolutePath;
        if (absolutePath.startsWith("\\\\")) return "\\\\?\\UNC\\" + absolutePath.substring(2);
        return "\\\\?\\" + absolutePath;
    }

    interface NativeOperations {
        long readChangeTime(Path path) throws IOException;

        byte[] readDacl(Path target) throws IOException;

        void setProtectedDacl(Path temporary, byte[] dacl) throws IOException;

        void replaceFile(Path target, Path temporary) throws IOException;
    }

    static final class TargetMissingException extends IOException {
        private final Path path;

        TargetMissingException(Path path, NativeFailure cause) {
            super("ReplaceFileW target disappeared: " + path, cause);
            this.path = path;
        }

        Path path() {
            return path;
        }
    }

    static final class NativeFailure extends IOException {
        private final String operation;
        private final Path path;
        private final int win32Code;

        NativeFailure(String operation, Path path, int win32Code) {
            super(operation + " failed with Win32 error " + win32Code + ": " + path);
            this.operation = Objects.requireNonNull(operation, "operation");
            this.path = Objects.requireNonNull(path, "path");
            this.win32Code = win32Code;
        }

        String operation() {
            return operation;
        }

        Path path() {
            return path;
        }

        int win32Code() {
            return win32Code;
        }
    }

    private static final class JnaNativeOperations implements NativeOperations {
        @Override
        public long readChangeTime(Path path) throws IOException {
            var handle = KernelFiles.INSTANCE.CreateFileW(nativePath(path), FILE_READ_ATTRIBUTES,
                FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, null, OPEN_EXISTING,
                FILE_FLAG_BACKUP_SEMANTICS, null);
            if (handle == null || Pointer.nativeValue(handle) == -1L) {
                throw failure("CreateFileW", path);
            }
            try {
                var info = new FileBasicInfo();
                if (!KernelFiles.INSTANCE.GetFileInformationByHandleEx(handle,
                    FILE_BASIC_INFO_CLASS, info, info.size())) {
                    throw failure("GetFileInformationByHandleEx", path);
                }
                return info.changeTime;
            } finally {
                KernelFiles.INSTANCE.CloseHandle(handle);
            }
        }

        @Override
        public byte[] readDacl(Path target) throws IOException {
            var required = new IntByReference();
            FileSecurity.INSTANCE.GetFileSecurityW(nativePath(target), DACL_SECURITY_INFORMATION,
                null, 0, required);
            if (required.getValue() == 0) throw failure("GetFileSecurityW", target);

            var dacl = new Memory(required.getValue());
            if (!FileSecurity.INSTANCE.GetFileSecurityW(nativePath(target), DACL_SECURITY_INFORMATION,
                dacl, required.getValue(), required)) {
                throw failure("GetFileSecurityW", target);
            }
            return dacl.getByteArray(0, required.getValue());
        }

        @Override
        public void setProtectedDacl(Path temporary, byte[] dacl) throws IOException {
            var descriptor = new Memory(dacl.length);
            descriptor.write(0, dacl, 0, dacl.length);
            var information = DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION;
            if (!FileSecurity.INSTANCE.SetFileSecurityW(nativePath(temporary), information, descriptor)) {
                throw failure("SetFileSecurityW", temporary);
            }
        }

        @Override
        public void replaceFile(Path target, Path temporary) throws IOException {
            if (!ReplaceFile.INSTANCE.ReplaceFileW(nativePath(target), nativePath(temporary), null,
                0, null, null)) {
                throw failure("ReplaceFileW", target);
            }
        }

        private static NativeFailure failure(String operation, Path path) {
            return new NativeFailure(operation, path, Native.getLastError());
        }

        private static String nativePath(Path path) {
            return toNamespacedPath(path.toAbsolutePath().normalize().toString());
        }
    }

    private interface ReplaceFile extends StdCallLibrary {
        ReplaceFile INSTANCE = Native.load("Kernel32", ReplaceFile.class, W32APIOptions.UNICODE_OPTIONS);

        boolean ReplaceFileW(String replaced, String replacement, String backup, int flags,
                             Pointer exclude, Pointer reserved);
    }

    private interface KernelFiles extends StdCallLibrary {
        KernelFiles INSTANCE = Native.load("Kernel32", KernelFiles.class,
            W32APIOptions.UNICODE_OPTIONS);

        Pointer CreateFileW(String path, int desiredAccess, int shareMode, Pointer securityAttributes,
                            int creationDisposition, int flagsAndAttributes, Pointer templateFile);

        boolean GetFileInformationByHandleEx(Pointer handle, int informationClass,
                                             FileBasicInfo information, int size);

        boolean CloseHandle(Pointer handle);
    }

    private interface FileSecurity extends StdCallLibrary {
        FileSecurity INSTANCE = Native.load("Advapi32", FileSecurity.class, W32APIOptions.UNICODE_OPTIONS);

        boolean GetFileSecurityW(String path, int information, Pointer descriptor, int length,
                                 IntByReference required);

        boolean SetFileSecurityW(String path, int information, Pointer descriptor);
    }

    @Structure.FieldOrder({"creationTime", "lastAccessTime", "lastWriteTime", "changeTime",
        "fileAttributes"})
    public static final class FileBasicInfo extends Structure {
        public long creationTime;
        public long lastAccessTime;
        public long lastWriteTime;
        public long changeTime;
        public int fileAttributes;
    }
}
