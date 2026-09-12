package com.sstlfsj.fibra.plugins.subprocess.local;

import com.sun.jna.Pointer;

import java.io.IOException;
import java.util.List;

/** Injectable Win32 operations used by the Job lifecycle owner. */
interface WindowsJobKernel {
    Pointer createJob() throws IOException;

    void setKillOnClose(Pointer job) throws IOException;

    Pipe createPipe() throws IOException;

    void setInheritable(Pointer handle, boolean inheritable) throws IOException;

    ProcessHandles createProcess(String applicationName, String commandLine, String cwd, Pointer stdin,
                                 Pointer stdout, Pointer stderr, List<Pointer> inheritedHandles,
                                 int flags) throws IOException;

    void assign(Pointer job, Pointer process) throws IOException;

    void resume(Pointer thread) throws IOException;

    int waitForExit(Pointer process) throws IOException;

    int activeProcesses(Pointer job) throws IOException;

    void terminateJob(Pointer job, int exitCode) throws IOException;

    void terminateProcess(Pointer process, int exitCode) throws IOException;

    int read(Pointer handle, byte[] target, int offset, int length) throws IOException;

    void close(Pointer handle) throws IOException;

    record Pipe(Pointer read, Pointer write) {
    }

    record ProcessHandles(Pointer process, Pointer thread) {
    }
}
