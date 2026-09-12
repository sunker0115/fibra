package com.sstlfsj.fibra.plugins.subprocess.local;

import com.sun.jna.IntegerType;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.IOException;
import java.lang.ref.Reference;
import java.util.Arrays;
import java.util.List;

/** Stock-JNA bindings for the Win32 process and Job operations used by Fibra. */
final class JnaWindowsJobKernel implements WindowsJobKernel {
    static final JnaWindowsJobKernel INSTANCE = new JnaWindowsJobKernel();
    private static final int HANDLE_FLAG_INHERIT = 0x00000001;
    private static final int STARTF_USESTDHANDLES = 0x00000100;
    private static final int WAIT_FAILED = -1;
    private static final int INFINITE = -1;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    private static final int JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION = 1;
    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    private static final long PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x00020002L;
    private static final int ERROR_BROKEN_PIPE = 109;
    private static final int ERROR_NO_DATA = 232;

    private JnaWindowsJobKernel() {
    }

    @Override
    public Pointer createJob() throws IOException {
        var handle = Kernel32.INSTANCE.CreateJobObjectW(null, null);
        if (nullHandle(handle)) throw failure("CreateJobObjectW");
        return handle;
    }

    @Override
    public void setKillOnClose(Pointer job) throws IOException {
        var information = new ExtendedLimitInformation();
        information.basicLimitInformation.limitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        information.write();
        if (!Kernel32.INSTANCE.SetInformationJobObject(job,
            JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, information.getPointer(), information.size())) {
            throw failure("SetInformationJobObject");
        }
    }

    @Override
    public Pipe createPipe() throws IOException {
        var read = new PointerByReference();
        var write = new PointerByReference();
        var security = new SecurityAttributes();
        security.length = security.size();
        security.inheritHandle = 1;
        security.write();
        if (!Kernel32.INSTANCE.CreatePipe(read, write, security, 0)) throw failure("CreatePipe");
        if (nullHandle(read.getValue()) || nullHandle(write.getValue())) {
            closeBestEffort(read.getValue());
            closeBestEffort(write.getValue());
            throw new IOException("CreatePipe returned a null handle");
        }
        return new Pipe(read.getValue(), write.getValue());
    }

    @Override
    public void setInheritable(Pointer handle, boolean inheritable) throws IOException {
        if (!Kernel32.INSTANCE.SetHandleInformation(handle, HANDLE_FLAG_INHERIT,
            inheritable ? HANDLE_FLAG_INHERIT : 0)) {
            throw failure("SetHandleInformation");
        }
    }

    @Override
    public ProcessHandles createProcess(String applicationName, String commandLine, String cwd,
                                        Pointer stdin,
                                        Pointer stdout, Pointer stderr,
                                        List<Pointer> inheritedHandles, int flags) throws IOException {
        if (inheritedHandles.size() != 3 || inheritedHandles.stream().anyMatch(
            JnaWindowsJobKernel::nullHandle)) {
            throw new IllegalArgumentException("exactly three non-null inherited handles are required");
        }
        var attributeBytes = new Memory(Native.SIZE_T_SIZE);
        attributeBytes.clear();
        Kernel32.INSTANCE.InitializeProcThreadAttributeList(null, 1, 0, attributeBytes);
        long byteCount = Native.SIZE_T_SIZE == Long.BYTES
            ? attributeBytes.getLong(0) : Integer.toUnsignedLong(attributeBytes.getInt(0));
        if (byteCount <= 0) throw failure("InitializeProcThreadAttributeList");
        var attributeList = new Memory(byteCount);
        if (!Kernel32.INSTANCE.InitializeProcThreadAttributeList(attributeList, 1, 0,
            attributeBytes)) {
            throw failure("InitializeProcThreadAttributeList");
        }
        var handleList = new Memory((long) Native.POINTER_SIZE * inheritedHandles.size());
        for (int index = 0; index < inheritedHandles.size(); index++) {
            handleList.setPointer((long) index * Native.POINTER_SIZE, inheritedHandles.get(index));
        }
        var information = new ProcessInformation();
        try {
            if (!Kernel32.INSTANCE.UpdateProcThreadAttribute(attributeList, 0,
                Pointer.createConstant(PROC_THREAD_ATTRIBUTE_HANDLE_LIST), handleList,
                new SizeT(handleList.size()), null, null)) {
                throw failure("UpdateProcThreadAttribute");
            }
            var startup = new StartupInfoEx();
            startup.startupInfo.size = startup.size();
            startup.startupInfo.flags = STARTF_USESTDHANDLES;
            startup.startupInfo.stdin = stdin;
            startup.startupInfo.stdout = stdout;
            startup.startupInfo.stderr = stderr;
            startup.attributeList = attributeList;
            startup.write();
            var mutableCommandLine = Arrays.copyOf(commandLine.toCharArray(),
                commandLine.length() + 1);
            if (!Kernel32.INSTANCE.CreateProcessW(applicationName, mutableCommandLine, null, null, true,
                flags, null, cwd, startup.getPointer(), information)) {
                throw failure("CreateProcessW");
            }
        } finally {
            Kernel32.INSTANCE.DeleteProcThreadAttributeList(attributeList);
            Reference.reachabilityFence(handleList);
        }
        information.read();
        if (nullHandle(information.process) || nullHandle(information.thread)) {
            if (!nullHandle(information.process)) {
                Kernel32.INSTANCE.TerminateProcess(information.process, 1);
            }
            closeBestEffort(information.thread);
            closeBestEffort(information.process);
            throw new IOException("CreateProcessW returned null process/thread handles");
        }
        return new ProcessHandles(information.process, information.thread);
    }

    @Override
    public void assign(Pointer job, Pointer process) throws IOException {
        if (!Kernel32.INSTANCE.AssignProcessToJobObject(job, process)) {
            throw failure("AssignProcessToJobObject");
        }
    }

    @Override
    public void resume(Pointer thread) throws IOException {
        if (Kernel32.INSTANCE.ResumeThread(thread) == -1) throw failure("ResumeThread");
    }

    @Override
    public int waitForExit(Pointer process) throws IOException {
        if (Kernel32.INSTANCE.WaitForSingleObject(process, INFINITE) == WAIT_FAILED) {
            throw failure("WaitForSingleObject");
        }
        var exitCode = new IntByReference();
        if (!Kernel32.INSTANCE.GetExitCodeProcess(process, exitCode)) throw failure("GetExitCodeProcess");
        return exitCode.getValue();
    }

    @Override
    public int activeProcesses(Pointer job) throws IOException {
        var information = new Memory(48);
        information.clear();
        if (!Kernel32.INSTANCE.QueryInformationJobObject(job,
            JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION, information,
            Math.toIntExact(information.size()), null)) {
            throw failure("QueryInformationJobObject");
        }
        return information.getInt(40);
    }

    @Override
    public void terminateJob(Pointer job, int exitCode) throws IOException {
        if (!Kernel32.INSTANCE.TerminateJobObject(job, exitCode)) throw failure("TerminateJobObject");
    }

    @Override
    public void terminateProcess(Pointer process, int exitCode) throws IOException {
        if (!Kernel32.INSTANCE.TerminateProcess(process, exitCode)) throw failure("TerminateProcess");
    }

    @Override
    public int read(Pointer handle, byte[] target, int offset, int length) throws IOException {
        if (length == 0) return 0;
        var buffer = offset == 0 && length == target.length ? target : new byte[length];
        var read = new IntByReference();
        if (!Kernel32.INSTANCE.ReadFile(handle, buffer, length, read, null)) {
            int code = Native.getLastError();
            if (code == ERROR_BROKEN_PIPE || code == ERROR_NO_DATA) return -1;
            throw failure("ReadFile", code);
        }
        int count = read.getValue();
        if (buffer != target) System.arraycopy(buffer, 0, target, offset, count);
        return count;
    }

    @Override
    public void close(Pointer handle) throws IOException {
        if (!Kernel32.INSTANCE.CloseHandle(handle)) throw failure("CloseHandle");
    }

    static int extendedLimitInformationSize() {
        return new ExtendedLimitInformation().size();
    }

    static int limitFlagsOffset() {
        return new ExtendedLimitInformation().limitFlagsOffset();
    }

    static int startupInfoExSize() {
        return new StartupInfoEx().size();
    }

    private static boolean nullHandle(Pointer handle) {
        return handle == null || Pointer.nativeValue(handle) == 0;
    }

    private static IOException failure(String operation) {
        return failure(operation, Native.getLastError());
    }

    private static IOException failure(String operation, int code) {
        return new IOException(operation + " failed with Win32 error " + code);
    }

    private static void closeBestEffort(Pointer handle) {
        if (!nullHandle(handle)) Kernel32.INSTANCE.CloseHandle(handle);
    }

    private interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("Kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);

        Pointer CreateJobObjectW(Pointer securityAttributes, String name);

        boolean SetInformationJobObject(Pointer job, int informationClass,
                                        Pointer information, int informationLength);

        boolean CreatePipe(PointerByReference read, PointerByReference write,
                           SecurityAttributes attributes, int size);

        boolean SetHandleInformation(Pointer handle, int mask, int flags);

        boolean CreateProcessW(String applicationName, char[] commandLine,
                               Pointer processAttributes, Pointer threadAttributes,
                               boolean inheritHandles, int creationFlags, Pointer environment,
                               String cwd, Pointer startupInfo, ProcessInformation processInformation);

        boolean InitializeProcThreadAttributeList(Pointer attributeList, int attributeCount,
                                                  int flags, Pointer size);

        boolean UpdateProcThreadAttribute(Pointer attributeList, int flags,
                                          Pointer attribute, Pointer value, SizeT size,
                                          Pointer previousValue, Pointer returnSize);

        void DeleteProcThreadAttributeList(Pointer attributeList);

        boolean AssignProcessToJobObject(Pointer job, Pointer process);

        int ResumeThread(Pointer thread);

        int WaitForSingleObject(Pointer handle, int milliseconds);

        boolean GetExitCodeProcess(Pointer process, IntByReference exitCode);

        boolean QueryInformationJobObject(Pointer job, int informationClass,
                                          Pointer information, int informationLength,
                                          IntByReference returnLength);

        boolean TerminateJobObject(Pointer job, int exitCode);

        boolean TerminateProcess(Pointer process, int exitCode);

        boolean ReadFile(Pointer file, byte[] buffer, int bytesToRead,
                         IntByReference bytesRead, Pointer overlapped);

        boolean CloseHandle(Pointer handle);
    }

    @Structure.FieldOrder({"length", "securityDescriptor", "inheritHandle"})
    public static final class SecurityAttributes extends Structure {
        public int length;
        public Pointer securityDescriptor;
        public int inheritHandle;
    }

    @Structure.FieldOrder({"size", "reserved", "desktop", "title", "x", "y", "xSize", "ySize",
        "xCountChars", "yCountChars", "fillAttribute", "flags", "showWindow", "reserved2Size",
        "reserved2", "stdin", "stdout", "stderr"})
    public static final class StartupInfo extends Structure {
        public int size;
        public Pointer reserved;
        public Pointer desktop;
        public Pointer title;
        public int x;
        public int y;
        public int xSize;
        public int ySize;
        public int xCountChars;
        public int yCountChars;
        public int fillAttribute;
        public int flags;
        public short showWindow;
        public short reserved2Size;
        public Pointer reserved2;
        public Pointer stdin;
        public Pointer stdout;
        public Pointer stderr;

        public StartupInfo() {
            super(ALIGN_MSVC);
        }
    }

    @Structure.FieldOrder({"startupInfo", "attributeList"})
    public static final class StartupInfoEx extends Structure {
        public StartupInfo startupInfo = new StartupInfo();
        public Pointer attributeList;

        public StartupInfoEx() {
            super(ALIGN_MSVC);
        }
    }

    @Structure.FieldOrder({"process", "thread", "processId", "threadId"})
    public static final class ProcessInformation extends Structure {
        public Pointer process;
        public Pointer thread;
        public int processId;
        public int threadId;
    }

    @Structure.FieldOrder({"perProcessUserTimeLimit", "perJobUserTimeLimit", "limitFlags",
        "minimumWorkingSetSize", "maximumWorkingSetSize", "activeProcessLimit", "affinity",
        "priorityClass", "schedulingClass"})
    public static final class BasicLimitInformation extends Structure {
        public long perProcessUserTimeLimit;
        public long perJobUserTimeLimit;
        public int limitFlags;
        public SizeT minimumWorkingSetSize = new SizeT();
        public SizeT maximumWorkingSetSize = new SizeT();
        public int activeProcessLimit;
        public SizeT affinity = new SizeT();
        public int priorityClass;
        public int schedulingClass;

        public BasicLimitInformation() {
            super(ALIGN_MSVC);
        }

        int limitFlagsOffset() {
            return fieldOffset("limitFlags");
        }
    }

    @Structure.FieldOrder({"readOperationCount", "writeOperationCount", "otherOperationCount",
        "readTransferCount", "writeTransferCount", "otherTransferCount"})
    public static final class IoCounters extends Structure {
        public long readOperationCount;
        public long writeOperationCount;
        public long otherOperationCount;
        public long readTransferCount;
        public long writeTransferCount;
        public long otherTransferCount;

        public IoCounters() {
            super(ALIGN_MSVC);
        }
    }

    @Structure.FieldOrder({"basicLimitInformation", "ioInfo", "processMemoryLimit",
        "jobMemoryLimit", "peakProcessMemoryUsed", "peakJobMemoryUsed"})
    public static final class ExtendedLimitInformation extends Structure {
        public BasicLimitInformation basicLimitInformation = new BasicLimitInformation();
        public IoCounters ioInfo = new IoCounters();
        public SizeT processMemoryLimit = new SizeT();
        public SizeT jobMemoryLimit = new SizeT();
        public SizeT peakProcessMemoryUsed = new SizeT();
        public SizeT peakJobMemoryUsed = new SizeT();

        public ExtendedLimitInformation() {
            super(ALIGN_MSVC);
        }

        int limitFlagsOffset() {
            return fieldOffset("basicLimitInformation")
                + basicLimitInformation.limitFlagsOffset();
        }
    }

    public static final class SizeT extends IntegerType {
        public SizeT() {
            this(0);
        }

        public SizeT(long value) {
            super(Native.SIZE_T_SIZE, value, true);
        }
    }
}
