package com.sstlfsj.fibra.plugins.subprocess.local;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JnaWindowsJobOwnerTest {
    @Test void createsSuspendedAssignsTheJobAndOnlyThenResumes() throws Exception {
        var kernel = new FakeKernel();
        var owner = new JnaWindowsJobOwner(kernel, (command, cwd) -> "C:\\resolved\\tool.exe");

        owner.start(spec());

        var create = "create-process:" + (JnaWindowsJobOwner.CREATE_SUSPENDED
            | JnaWindowsJobOwner.EXTENDED_STARTUPINFO_PRESENT);
        assertBefore(kernel.events, "create-job", "set-kill-on-close");
        assertBefore(kernel.events, "set-kill-on-close", create);
        assertBefore(kernel.events, create, "assign");
        for (var childHandle : List.of("close:30", "close:33", "close:35")) {
            assertBefore(kernel.events, "assign", childHandle);
            assertBefore(kernel.events, childHandle, "resume");
        }
        assertEquals(JnaWindowsJobOwner.CREATE_SUSPENDED
            | JnaWindowsJobOwner.EXTENDED_STARTUPINFO_PRESENT, kernel.creationFlags);
        assertEquals(List.of(Pointer.createConstant(30), Pointer.createConstant(33),
            Pointer.createConstant(35)), kernel.inheritedHandles);
        assertEquals("C:\\resolved\\tool.exe", kernel.applicationName);
        assertEquals("tool.exe \"literal arg\" \"tail \\\\\"", kernel.commandLine);
        owner.terminate();
        assertTrue(kernel.events.contains("terminate-job:1"));
        owner.close();
        assertEquals(1, kernel.events.stream().filter("close:job"::equals).count());
    }

    @Test void assignmentFailureTerminatesTheSuspendedProcessAndClosesEveryOwnedHandle() {
        var kernel = new FakeKernel();
        kernel.assignFailure = new IOException("AssignProcessToJobObject failed");
        var owner = owner(kernel);

        assertThrows(IOException.class, () -> owner.start(spec()));

        assertBefore(kernel.events, "create-process:" + (JnaWindowsJobOwner.CREATE_SUSPENDED
            | JnaWindowsJobOwner.EXTENDED_STARTUPINFO_PRESENT), "assign");
        assertTrue(kernel.events.contains("terminate-process:1"));
        assertTrue(kernel.events.contains("close:thread"));
        assertTrue(kernel.events.contains("close:process"));
        assertEquals(1, kernel.events.stream().filter("close:job"::equals).count());
    }

    @Test void resumeFailureTerminatesTheSuspendedProcessAndClosesEveryOwnedHandle() {
        var kernel = new FakeKernel();
        kernel.resumeFailure = new IOException("ResumeThread failed");
        var owner = owner(kernel);

        assertThrows(IOException.class, () -> owner.start(spec()));

        assertBefore(kernel.events, "assign", "resume");
        assertTrue(kernel.events.contains("terminate-process:1"));
        assertTrue(kernel.events.contains("close:thread"));
        assertTrue(kernel.events.contains("close:process"));
        assertEquals(1, kernel.events.stream().filter("close:job"::equals).count());
    }

    @Test void childPipeCloseFailureTerminatesBeforeResumeAndPreservesContainment() {
        var kernel = new FakeKernel();
        kernel.closeFailure = Pointer.createConstant(30);
        var owner = owner(kernel);

        var failure = assertThrows(IOException.class, () -> owner.start(spec()));

        assertEquals("CloseHandle failed", failure.getMessage());
        assertBefore(kernel.events, "assign", "close:30");
        assertTrue(kernel.events.stream().noneMatch("resume"::equals));
        assertTrue(kernel.events.contains("terminate-process:1"));
        assertTrue(kernel.events.contains("close:thread"));
        assertTrue(kernel.events.contains("close:process"));
        assertTrue(kernel.events.contains("close:job"));
    }

    @Test void twoOwnersCanCreateConcurrentlyWithOnlyTheirThreeTargetHandlesInherited()
        throws Exception {
        var barrier = new CyclicBarrier(2);
        var firstKernel = new FakeKernel();
        var secondKernel = new FakeKernel();
        secondKernel.nextPipe = 130;
        firstKernel.creationBarrier = barrier;
        secondKernel.creationBarrier = barrier;
        var first = owner(firstKernel);
        var second = owner(secondKernel);

        var firstStart = CompletableFuture.runAsync(() -> start(first));
        var secondStart = CompletableFuture.runAsync(() -> start(second));
        CompletableFuture.allOf(firstStart, secondStart).get(3, TimeUnit.SECONDS);

        var expected = List.of(Pointer.createConstant(30), Pointer.createConstant(33),
            Pointer.createConstant(35));
        assertEquals(expected, firstKernel.inheritedHandles);
        assertEquals(List.of(Pointer.createConstant(130), Pointer.createConstant(133),
            Pointer.createConstant(135)), secondKernel.inheritedHandles);
        first.close();
        second.close();
    }

    @Test void extendedJobLimitInformationUsesTheNativePointerLayout() {
        assertEquals(Native.POINTER_SIZE == 8 ? 144 : 112,
            JnaWindowsJobKernel.extendedLimitInformationSize());
        assertEquals(16, JnaWindowsJobKernel.limitFlagsOffset());
        assertEquals(Native.POINTER_SIZE == 8 ? 112 : 72,
            JnaWindowsJobKernel.startupInfoExSize());
    }

    @Test void jobObservationAndTerminationUseTheOwnedJobHandle() throws Exception {
        var kernel = new FakeKernel();
        kernel.activeProcesses = 0;
        var owner = owner(kernel);
        owner.start(spec());

        owner.waitForEmpty();
        owner.terminate();
        owner.terminate();

        assertTrue(kernel.events.contains("query:job"));
        assertEquals(1, kernel.events.stream().filter("terminate-job:1"::equals).count());
    }

    @Test void capabilityProbeConfiguresAndClosesAnEmptyJob() throws Exception {
        var kernel = new FakeKernel();

        JnaWindowsJobOwner.probe(kernel);

        assertEquals(List.of("create-job", "set-kill-on-close", "close:job"), kernel.events);
    }

    private static SubprocessSpec spec() {
        return SubprocessSpec.builder().argv(List.of("tool.exe", "literal arg", "tail \\"))
            .cwd("C:\\target").stdoutMaxBytes(10).stderrMaxBytes(10)
            .grace(Duration.ofSeconds(1)).build();
    }

    private static void assertBefore(List<String> events, String first, String second) {
        assertTrue(events.indexOf(first) >= 0, () -> first + " missing from " + events);
        assertTrue(events.indexOf(first) < events.indexOf(second), () -> events.toString());
    }

    private static JnaWindowsJobOwner owner(WindowsJobKernel kernel) {
        return new JnaWindowsJobOwner(kernel, (command, cwd) -> "C:\\resolved\\tool.exe");
    }

    private static void start(JnaWindowsJobOwner owner) {
        try {
            owner.start(spec());
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static final class FakeKernel implements WindowsJobKernel {
        private static final Pointer JOB = Pointer.createConstant(10);
        private static final Pointer PROCESS = Pointer.createConstant(20);
        private static final Pointer THREAD = Pointer.createConstant(21);
        private final List<String> events = new ArrayList<>();
        private int nextPipe = 30;
        private IOException assignFailure;
        private IOException resumeFailure;
        private Pointer closeFailure;
        private CyclicBarrier creationBarrier;
        private int activeProcesses;
        private boolean terminated;
        private String applicationName;
        private String commandLine;
        private int creationFlags;
        private List<Pointer> inheritedHandles;

        @Override public Pointer createJob() {
            events.add("create-job");
            return JOB;
        }

        @Override public void setKillOnClose(Pointer job) {
            events.add("set-kill-on-close");
        }

        @Override public Pipe createPipe() {
            return new Pipe(Pointer.createConstant(nextPipe++), Pointer.createConstant(nextPipe++));
        }

        @Override public void setInheritable(Pointer handle, boolean inheritable) {
            events.add("inherit:" + Pointer.nativeValue(handle) + ":" + inheritable);
        }

        @Override public ProcessHandles createProcess(String applicationName, String commandLine,
                                                       String cwd, Pointer stdin,
                                                       Pointer stdout, Pointer stderr,
                                                       List<Pointer> inheritedHandles, int flags)
            throws IOException {
            this.applicationName = applicationName;
            this.commandLine = commandLine;
            this.inheritedHandles = List.copyOf(inheritedHandles);
            this.creationFlags = flags;
            events.add("create-process:" + flags);
            if (creationBarrier != null) {
                try {
                    creationBarrier.await(2, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IOException("concurrent create did not overlap", failure);
                }
            }
            return new ProcessHandles(PROCESS, THREAD);
        }

        @Override public void assign(Pointer job, Pointer process) throws IOException {
            events.add("assign");
            if (assignFailure != null) throw assignFailure;
        }

        @Override public void resume(Pointer thread) throws IOException {
            events.add("resume");
            if (resumeFailure != null) throw resumeFailure;
        }

        @Override public int waitForExit(Pointer process) {
            return 0;
        }

        @Override public int activeProcesses(Pointer job) {
            events.add("query:job");
            return activeProcesses;
        }

        @Override public void terminateJob(Pointer job, int exitCode) {
            if (!terminated) events.add("terminate-job:" + exitCode);
            terminated = true;
        }

        @Override public void terminateProcess(Pointer process, int exitCode) {
            events.add("terminate-process:" + exitCode);
        }

        @Override public int read(Pointer handle, byte[] target, int offset, int length) {
            return -1;
        }

        @Override public void close(Pointer handle) throws IOException {
            String name = handle.equals(JOB) ? "job" : handle.equals(PROCESS) ? "process"
                : handle.equals(THREAD) ? "thread" : Long.toString(Pointer.nativeValue(handle));
            events.add("close:" + name);
            if (handle.equals(closeFailure)) throw new IOException("CloseHandle failed");
        }
    }
}
