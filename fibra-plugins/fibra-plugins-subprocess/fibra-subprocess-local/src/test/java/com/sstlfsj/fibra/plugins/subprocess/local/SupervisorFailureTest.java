package com.sstlfsj.fibra.plugins.subprocess.local;

import com.sstlfsj.fibra.CancellationSource;
import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessErrorCode;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessException;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessSpec;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupervisorFailureTest {
    @TempDir
    Path directory;

    @Test void unresponsiveSupervisorFailsDrainWithinItsGraceBudget() throws Exception {
        var executable = directory.resolve("unresponsive-supervisor");
        Files.writeString(executable, """
            #!/bin/sh
            exec node -e 'require("fs").writeFileSync("supervisor.pid",String(process.pid)); process.stdout.write("P\\t"+process.pid+"\\n"); setInterval(()=>{},1000)'
            """);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        var runtime = FibraRuntime.create();
        var spec = SubprocessSpec.builder().argv(List.of("unused")).cwd(directory.toString())
            .stdoutMaxBytes(100).stderrMaxBytes(100).grace(Duration.ofMillis(100)).build();
        var unit = new LocalSubprocess(executable.toString()).spawn(
            InvocationContext.of(runtime.rootScope().context(), "test"), spec).block();
        long supervisorPid = Long.parseLong(Files.readString(directory.resolve("supervisor.pid")));
        try {
            unit.terminate();
            var failure = assertThrows(SubprocessException.class,
                () -> unit.waitForExit().block(Duration.ofSeconds(3)));
            assertEquals(SubprocessErrorCode.TERMINATION_FAILED, failure.code());
            assertThrows(SubprocessException.class, () -> unit.done().block(Duration.ofSeconds(1)));
        } finally {
            ProcessHandle.of(supervisorPid).ifPresent(ProcessHandle::destroyForcibly);
            assertThrows(RuntimeException.class, runtime::close);
        }
    }

    @Test void cancellationInterruptsSupervisorAcquisitionBeforeTheStartWatchdog() throws Exception {
        var executable = directory.resolve("cancellable-supervisor");
        Files.writeString(executable, """
            #!/bin/sh
            exec node -e 'const fs=require("fs");
            fs.writeFileSync("supervisor.pid", String(process.pid));
            process.stdin.resume();
            process.stdin.on("end", () => process.stdout.end(
              "E\\tSPAWN_FAILED\\tY2FuY2VsbGVk\\nQ\\n", () => process.exit(0)));
            setInterval(()=>{},1000)'
            """);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        var source = new CancellationSource();

        try (var runtime = FibraRuntime.create()) {
            var spec = SubprocessSpec.builder().argv(List.of("unused")).cwd(directory.toString())
                .stdoutMaxBytes(100).stderrMaxBytes(100).grace(Duration.ofMillis(100)).build();
            var future = new LocalSubprocess(executable.toString()).spawn(
                InvocationContext.of(runtime.rootScope().context(), "test")
                    .withCancellation(source.token()), spec).toFuture();
            Path pidFile = directory.resolve("supervisor.pid");
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!Files.exists(pidFile) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            long supervisorPid = Long.parseLong(Files.readString(pidFile));
            try {
                source.cancel();
                var failure = assertThrows(ExecutionException.class,
                    () -> future.get(2, TimeUnit.SECONDS));
                var processFailure = (SubprocessException) failure.getCause();
                assertEquals(SubprocessErrorCode.SPAWN_FAILED, processFailure.code());
                assertFalse(ProcessHandle.of(supervisorPid).map(ProcessHandle::isAlive).orElse(false));
            } finally {
                ProcessHandle.of(supervisorPid).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test void malformedSupervisorPayloadPidIsSpawnFailure() throws Exception {
        var executable = directory.resolve("malformed-supervisor");
        Files.writeString(executable, """
            #!/bin/sh
            printf 'P\\tnot-a-pid\\nQ\\n'
            """);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        var spec = SubprocessSpec.builder().argv(List.of("unused")).cwd(directory.toString())
            .stdoutMaxBytes(100).stderrMaxBytes(100).grace(Duration.ofMillis(100)).build();

        try (var runtime = FibraRuntime.create()) {
            var failure = assertThrows(SubprocessException.class, () ->
                new LocalSubprocess(executable.toString()).spawn(
                    InvocationContext.of(runtime.rootScope().context(), "test"), spec)
                    .block(Duration.ofSeconds(5)));
            assertEquals(SubprocessErrorCode.SPAWN_FAILED, failure.code());
        }
    }
}
