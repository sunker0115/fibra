package com.sstlfsj.fibra.plugins.subprocess.local;

import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.logging.LogExporter;
import com.sstlfsj.fibra.logging.LogLevel;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessErrorCode;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessException;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessSpec;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalSubprocessLinuxSelectionTest {
    @Test void cachesSuccessfulDeepProbeButRechecksManagerForEverySpawn() {
        var provider = new FakeProvider();
        var subprocess = subprocess(provider);
        try (var runtime = FibraRuntime.create()) {
            var invocation = InvocationContext.of(runtime.rootScope().context(), "test");
            for (int index = 0; index < 2; index++) {
                var unit = subprocess.spawn(invocation, spec()).block(Duration.ofSeconds(1));
                assertEquals(0, unit.done().block(Duration.ofSeconds(1)).exitCode());
                unit.waitForExit().block(Duration.ofSeconds(1));
            }
        }
        assertEquals(1, provider.deep.get());
        assertEquals(2, provider.manager.get());
        assertEquals(2, provider.launches.get());
    }

    @Test void failedDeepProbeIsRetriedAndWarnsOnceBeforeFallback() {
        var provider = new FakeProvider();
        provider.failDeep = true;
        var subprocess = subprocess(provider);
        try (var runtime = FibraRuntime.create()) {
            var warnings = new java.util.concurrent.CopyOnWriteArrayList<
                com.sstlfsj.fibra.logging.LogMessage>();
            runtime.rootScope().context().logging().exporter(
                LogExporter.to(warnings::add, LogLevel.WARN));
            var invocation = InvocationContext.of(runtime.rootScope().context(), "test");
            for (int index = 0; index < 2; index++) {
                var unit = subprocess.spawn(invocation, fallbackSpec()).block(Duration.ofSeconds(2));
                unit.waitForExit().block(Duration.ofSeconds(2));
            }
            assertEquals(1, warnings.stream().filter(message -> message.arguments().getFirst()
                .toString().contains("using native POSIX process-group boundary where setsid or "
                    + "reparented descendants may escape")).count());
        }
        assertEquals(2, provider.deep.get());
        assertEquals(0, provider.manager.get());
        assertEquals(0, provider.launches.get());
    }

    @Test void nativeLaunchFailureAfterPositiveProbesDoesNotReplayTheTarget() {
        var provider = new FakeProvider();
        provider.failLaunch = true;
        var subprocess = subprocess(provider);
        try (var runtime = FibraRuntime.create()) {
            var failure = assertThrows(SubprocessException.class, () -> subprocess.spawn(
                InvocationContext.of(runtime.rootScope().context(), "test"), spec())
                .block(Duration.ofSeconds(1)));
            assertEquals(SubprocessErrorCode.SPAWN_FAILED, failure.code());
        }
        assertEquals(1, provider.deep.get());
        assertEquals(1, provider.manager.get());
        assertEquals(1, provider.launches.get());
    }

    @Test void managerFailureFallsBackButKeepsTheSuccessfulDeepProbeCache() {
        var provider = new FakeProvider();
        provider.failManager = true;
        var subprocess = subprocess(provider);
        try (var runtime = FibraRuntime.create()) {
            var invocation = InvocationContext.of(runtime.rootScope().context(), "test");
            var fallback = subprocess.spawn(invocation, fallbackSpec()).block(Duration.ofSeconds(2));
            fallback.waitForExit().block(Duration.ofSeconds(2));
            provider.failManager = false;
            var nativeUnit = subprocess.spawn(invocation, spec()).block(Duration.ofSeconds(1));
            nativeUnit.waitForExit().block(Duration.ofSeconds(1));
        }
        assertEquals(1, provider.deep.get());
        assertEquals(2, provider.manager.get());
        assertEquals(1, provider.launches.get());
    }

    private static LocalSubprocess subprocess(LinuxScopeProvider provider) {
        return new LocalSubprocess("node", LocalSubprocess.HostPlatform.LINUX,
            JnaWindowsJobOwner.FACTORY, provider);
    }

    private static SubprocessSpec spec() {
        return SubprocessSpec.builder().argv(List.of("tool"))
            .cwd(System.getProperty("java.io.tmpdir")).stdoutMaxBytes(10).stderrMaxBytes(10)
            .grace(Duration.ofMillis(50)).build();
    }

    private static SubprocessSpec fallbackSpec() {
        return SubprocessSpec.builder().argv(List.of("bash", "-c", "exit 0"))
            .cwd(System.getProperty("java.io.tmpdir")).stdoutMaxBytes(10).stderrMaxBytes(10)
            .grace(Duration.ofMillis(50)).build();
    }

    private static final class FakeProvider implements LinuxScopeProvider {
        private final AtomicInteger deep = new AtomicInteger();
        private final AtomicInteger manager = new AtomicInteger();
        private final AtomicInteger launches = new AtomicInteger();
        private boolean failDeep;
        private boolean failManager;
        private boolean failLaunch;

        @Override public void deepProbe() throws java.io.IOException {
            deep.incrementAndGet();
            if (failDeep) throw new java.io.IOException("systemd unavailable");
        }

        @Override public void probeManager() throws java.io.IOException {
            manager.incrementAndGet();
            if (failManager) throw new java.io.IOException("manager unavailable");
        }

        @Override public LinuxScopeLaunch launch(String nodeExecutable, SubprocessSpec spec)
            throws java.io.IOException {
            launches.incrementAndGet();
            if (failLaunch) throw new java.io.IOException("systemd-run launch failed");
            return new LinuxScopeLaunch(new CompletedProcess(), new QuietRange());
        }
    }

    private static final class CompletedProcess extends Process {
        private final String control = "P\t123\nD\t0\t\t\tfalse\t0\t\tfalse\t0\nQ\n";
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream(control.getBytes(StandardCharsets.UTF_8));
        }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
        @Override public Process destroyForcibly() { return this; }
        @Override public boolean isAlive() { return false; }
    }

    private static final class QuietRange implements LinuxScopeRange {
        @Override public void established() { }
        @Override public void awaitQuietAfterExit(Duration grace) { }
        @Override public void forceQuiet(Duration grace) { }
    }
}
