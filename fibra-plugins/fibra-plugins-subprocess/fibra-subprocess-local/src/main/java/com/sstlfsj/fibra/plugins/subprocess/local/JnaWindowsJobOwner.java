package com.sstlfsj.fibra.plugins.subprocess.local;

import com.sun.jna.Pointer;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessSpec;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Native Windows process and kill-on-close Job owner. */
final class JnaWindowsJobOwner implements WindowsJobOwner {
    static final int CREATE_SUSPENDED = 0x00000004;
    static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    static final WindowsJobOwnerFactory FACTORY = new WindowsJobOwnerFactory() {
        @Override
        public void probe() throws IOException {
            JnaWindowsJobOwner.probe(JnaWindowsJobKernel.INSTANCE);
        }

        @Override
        public WindowsJobOwner create(SubprocessSpec spec) throws IOException {
            return launch(spec);
        }
    };
    private final WindowsJobKernel kernel;
    private final WindowsExecutableLookup executableLookup;
    private final List<Pointer> owned = new ArrayList<>();
    private Pointer job;
    private Pointer process;
    private Pointer stdoutRead;
    private Pointer stderrRead;
    private InputStream stdout;
    private InputStream stderr;
    private boolean terminationSent;

    JnaWindowsJobOwner(WindowsJobKernel kernel) {
        this(kernel, WindowsExecutableResolver.system());
    }

    JnaWindowsJobOwner(WindowsJobKernel kernel, WindowsExecutableLookup executableLookup) {
        this.kernel = kernel;
        this.executableLookup = executableLookup;
    }

    static WindowsJobOwner launch(SubprocessSpec spec) throws IOException {
        var owner = new JnaWindowsJobOwner(JnaWindowsJobKernel.INSTANCE);
        owner.start(spec);
        return owner;
    }

    static void probe(WindowsJobKernel kernel) throws IOException {
        Pointer probe = null;
        Throwable failure = null;
        try {
            probe = kernel.createJob();
            kernel.setKillOnClose(probe);
        } catch (IOException | LinkageError unavailable) {
            failure = unavailable;
            throw unavailable;
        } finally {
            if (probe != null) {
                try {
                    kernel.close(probe);
                } catch (IOException | LinkageError closeFailure) {
                    if (failure != null) failure.addSuppressed(closeFailure);
                    else throw closeFailure;
                }
            }
        }
    }

    synchronized void start(SubprocessSpec spec) throws IOException {
        try {
            job = own(kernel.createJob());
            kernel.setKillOnClose(job);
            var stdinPipe = own(kernel.createPipe());
            var stdoutPipe = own(kernel.createPipe());
            var stderrPipe = own(kernel.createPipe());
            kernel.setInheritable(stdinPipe.write(), false);
            kernel.setInheritable(stdoutPipe.read(), false);
            kernel.setInheritable(stderrPipe.read(), false);

            var inheritedHandles = List.of(stdinPipe.read(), stdoutPipe.write(), stderrPipe.write());
            String applicationName = executableLookup.resolve(spec.argv().getFirst(), spec.cwd());
            var handles = kernel.createProcess(applicationName, commandLine(spec.argv()), spec.cwd(),
                stdinPipe.read(), stdoutPipe.write(), stderrPipe.write(), inheritedHandles,
                CREATE_SUSPENDED | EXTENDED_STARTUPINFO_PRESENT);
            Pointer thread = null;

            try {
                process = own(handles.process());
                thread = own(handles.thread());
                kernel.assign(job, process);
                closeOwned(stdinPipe.read());
                closeOwned(stdoutPipe.write());
                closeOwned(stderrPipe.write());
                closeOwned(stdinPipe.write());
                kernel.resume(thread);
                closeOwned(thread);
            } catch (IOException | LinkageError failure) {
                terminateProcessBestEffort(failure);
                if (thread != null) {
                    try {
                        closeOwned(thread);
                    } catch (IOException | LinkageError closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                throw failure;
            }
            stdoutRead = stdoutPipe.read();
            stderrRead = stderrPipe.read();
            stdout = new HandleInputStream(stdoutRead);
            stderr = new HandleInputStream(stderrRead);
        } catch (IOException | LinkageError failure) {
            closeAll(failure);
            throw failure;
        }
    }

    @Override
    public synchronized InputStream stdout() {
        if (stdout == null) throw new IllegalStateException("Windows Job has not started");
        return stdout;
    }

    @Override
    public synchronized InputStream stderr() {
        if (stderr == null) throw new IllegalStateException("Windows Job has not started");
        return stderr;
    }

    @Override
    public int waitForDirectExit() throws IOException {
        Pointer direct;
        synchronized (this) {
            direct = process;
        }
        if (direct == null) throw new IOException("Windows direct process handle is unavailable");
        try {
            return kernel.waitForExit(direct);
        } finally {
            synchronized (this) {
                if (process != null) closeOwned(process);
                process = null;
            }
        }
    }

    @Override
    public void waitForEmpty() throws IOException {
        for (;;) {
            Pointer current;
            synchronized (this) {
                current = job;
            }
            if (current == null) throw new IOException("Windows Job handle is unavailable");
            if (kernel.activeProcesses(current) == 0) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for Windows Job quiescence", interrupted);
            }
        }
    }

    @Override
    public synchronized void terminate() throws IOException {
        if (terminationSent) return;
        terminationSent = true;
        if (job != null) kernel.terminateJob(job, 1);
    }

    @Override
    public synchronized void close() throws IOException {
        if (job != null) {
            closeOwned(job);
            job = null;
        }
    }

    static String commandLine(List<String> argv) {
        return argv.stream().map(JnaWindowsJobOwner::quoteArg)
            .collect(java.util.stream.Collectors.joining(" "));
    }

    static String quoteArg(String argument) {
        if (argument.isEmpty()) return "\"\"";
        if (argument.chars().noneMatch(character -> Character.isWhitespace(character) || character == '\"')) {
            return argument;
        }
        var quoted = new StringBuilder("\"");
        for (int index = 0; index < argument.length(); index++) {
            int backslashes = 0;
            while (index < argument.length() && argument.charAt(index) == '\\') {
                backslashes++;
                index++;
            }
            if (index == argument.length()) {
                quoted.append("\\".repeat(backslashes * 2));
            } else if (argument.charAt(index) == '\"') {
                quoted.append("\\".repeat(backslashes * 2 + 1)).append('\"');
            } else {
                quoted.append("\\".repeat(backslashes)).append(argument.charAt(index));
            }
        }
        return quoted.append('\"').toString();
    }

    private Pointer own(Pointer handle) throws IOException {
        if (handle == null || Pointer.nativeValue(handle) == 0) throw new IOException("Win32 returned a null handle");
        owned.add(handle);
        return handle;
    }

    private WindowsJobKernel.Pipe own(WindowsJobKernel.Pipe pipe) throws IOException {
        own(pipe.read());
        own(pipe.write());
        return pipe;
    }

    private void terminateProcessBestEffort(Throwable failure) {
        if (process == null) return;
        try {
            kernel.terminateProcess(process, 1);
        } catch (IOException | LinkageError terminateFailure) {
            failure.addSuppressed(terminateFailure);
        }
    }

    private void closeOwned(Pointer handle) throws IOException {
        if (!owned.contains(handle)) return;
        kernel.close(handle);
        owned.remove(handle);
    }

    private void closeAll(Throwable failure) {
        for (var handle : List.copyOf(owned)) {
            try {
                closeOwned(handle);
            } catch (IOException | LinkageError closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        job = null;
        process = null;
    }

    private final class HandleInputStream extends InputStream {
        private Pointer handle;

        private HandleInputStream(Pointer handle) {
            this.handle = handle;
        }

        @Override
        public int read() throws IOException {
            var single = new byte[1];
            int count = read(single, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(single[0]);
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            Pointer current;
            synchronized (this) {
                current = handle;
            }
            if (current == null) return -1;
            return kernel.read(current, target, offset, length);
        }

        @Override
        public synchronized void close() throws IOException {
            if (handle == null) return;
            synchronized (JnaWindowsJobOwner.this) {
                closeOwned(handle);
            }
            if (handle.equals(stdoutRead)) stdoutRead = null;
            if (handle.equals(stderrRead)) stderrRead = null;
            handle = null;
        }
    }
}
