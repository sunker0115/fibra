package com.sstlfsj.fibra.plugins.subprocess.local;

import com.sstlfsj.fibra.EffectHandle;
import com.sstlfsj.fibra.EffectMetadata;
import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.logging.LogExporter;
import com.sstlfsj.fibra.logging.LogLevel;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessErrorCode;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessException;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessSpec;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsJobProcessUnitTest {
    @Test void localProviderSelectsTheNativeOwnerOnWindows() {
        var owner = new FakeOwner("native", "", 0);
        var probes = new AtomicInteger();
        var subprocess = new LocalSubprocess("must-not-run", true,
            new WindowsJobOwnerFactory() {
                @Override public void probe() {
                    probes.incrementAndGet();
                }

                @Override public WindowsJobOwner create(SubprocessSpec ignored) {
                    return owner;
                }
            });
        try (var runtime = FibraRuntime.create()) {
            var unit = subprocess.spawn(InvocationContext.of(runtime.rootScope().context(), "test"), spec()).block();
            owner.direct.countDown();
            owner.empty.countDown();

            assertEquals("tive", unit.done().block(Duration.ofSeconds(2)).stdout().text());
            unit.waitForExit().block(Duration.ofSeconds(2));
            assertEquals(1, probes.get());
        }
    }

    @Test void probesEveryWindowsSpawnAndWarnsOnlyOnceBeforeSupervisorFallback() {
        var probes = new AtomicInteger();
        var subprocess = new LocalSubprocess("node", true, new WindowsJobOwnerFactory() {
            @Override public void probe() {
                probes.incrementAndGet();
                throw new UnsatisfiedLinkError("Job Objects unavailable");
            }

            @Override public WindowsJobOwner create(SubprocessSpec ignored) {
                throw new AssertionError("native launch must not follow a failed probe");
            }
        });
        try (var runtime = FibraRuntime.create()) {
            var warnings = new java.util.concurrent.CopyOnWriteArrayList<
                com.sstlfsj.fibra.logging.LogMessage>();
            runtime.rootScope().context().logging().exporter(
                LogExporter.to(warnings::add, LogLevel.WARN));
            var invocation = InvocationContext.of(runtime.rootScope().context(), "test");
            for (int index = 0; index < 2; index++) {
                var unit = subprocess.spawn(invocation, fallbackSpec()).block(Duration.ofSeconds(2));
                assertEquals(0, unit.done().block(Duration.ofSeconds(2)).exitCode());
                unit.waitForExit().block(Duration.ofSeconds(2));
            }

            assertEquals(2, probes.get());
            assertEquals(1, warnings.stream()
                .filter(message -> {
                    String text = message.arguments().getFirst().toString();
                    return text.contains("Windows Job Object unavailable")
                        && text.contains("descendants may escape")
                        && text.contains("managed-range exit cannot be guaranteed");
                })
                .count());
        }
    }

    @Test void nativeLaunchFailureAfterSuccessfulProbeIsNotReplayedThroughSupervisor() {
        var probes = new AtomicInteger();
        var launches = new AtomicInteger();
        var subprocess = new LocalSubprocess("node", true, new WindowsJobOwnerFactory() {
            @Override public void probe() {
                probes.incrementAndGet();
            }

            @Override public WindowsJobOwner create(SubprocessSpec ignored) {
                launches.incrementAndGet();
                throw new UnsatisfiedLinkError("CreateProcessW unavailable");
            }
        });
        try (var runtime = FibraRuntime.create()) {
            var failure = assertThrows(SubprocessException.class, () -> subprocess.spawn(
                InvocationContext.of(runtime.rootScope().context(), "test"), fallbackSpec())
                .block(Duration.ofSeconds(2)));

            assertEquals(SubprocessErrorCode.SPAWN_FAILED, failure.code());
            assertEquals(1, probes.get());
            assertEquals(1, launches.get());
        }
    }

    @Test void doneTracksTheDirectCommandWhileWaitForExitRequiresAnEmptyJob() throws Exception {
        var owner = new FakeOwner("abcdef", "error", 7);
        var unit = new WindowsJobProcessUnit(ignored -> owner);
        unit.launch(spec());

        owner.direct.countDown();
        var outcome = unit.done().block(Duration.ofSeconds(2));
        assertEquals(7, outcome.exitCode());
        assertEquals("cdef", outcome.stdout().text());
        assertEquals(6, outcome.stdout().totalBytes());
        assertTrue(outcome.stdout().truncated());
        assertEquals("ror", outcome.stderr().text());
        assertFalse(unit.waitForExit().toFuture().isDone());

        owner.empty.countDown();
        unit.waitForExit().block(Duration.ofSeconds(2));
        assertEquals(1, owner.closeCalls.get());
    }

    @Test void directExitBoundsInheritedPipeDrainWithoutClosingOrTerminatingTheJob() {
        var heldStdout = new HeldOpenInputStream("descendant");
        var owner = new FakeOwner(heldStdout, new ByteArrayInputStream(new byte[0]), 0);
        var unit = new WindowsJobProcessUnit(ignored -> owner);
        unit.launch(spec(Duration.ofMillis(50)));

        owner.direct.countDown();
        var outcome = unit.done().block(Duration.ofSeconds(1));

        assertEquals("dant", outcome.stdout().text());
        assertEquals(10, outcome.stdout().totalBytes());
        assertTrue(outcome.stdout().truncated());
        assertTrue(heldStdout.closed);
        assertFalse(unit.waitForExit().toFuture().isDone());
        assertEquals(0, owner.terminateCalls.get());
        assertEquals(0, owner.closeCalls.get());

        owner.empty.countDown();
        unit.waitForExit().block(Duration.ofSeconds(1));
        assertEquals(1, owner.closeCalls.get());
    }

    @Test void terminateAndDisposeControlTheSameOwnerOnlyOnce() {
        var owner = new FakeOwner("", "", 1);
        var unit = new WindowsJobProcessUnit(ignored -> owner);
        unit.launch(spec());

        unit.terminate();
        unit.terminate();
        unit.dispose().subscribe();
        unit.drain().subscribe();

        assertEquals(1, owner.terminateCalls.get());
    }

    @Test void cancellationBeforeStartDoesNotCreateANativeOwner() {
        var creates = new AtomicInteger();
        var unit = new WindowsJobProcessUnit(ignored -> {
            creates.incrementAndGet();
            return new FakeOwner("", "", 0);
        });
        unit.terminate();

        var failure = assertThrows(SubprocessException.class, () -> unit.launch(spec()));

        assertEquals(SubprocessErrorCode.SPAWN_FAILED, failure.code());
        assertEquals(0, creates.get());
        unit.waitForExit().block(Duration.ofSeconds(1));
    }

    @Test void jobObservationFailureIsTerminationFailureRatherThanQuietSuccess() {
        var owner = new FakeOwner("ok", "", 0);
        owner.emptyFailure = new IOException("QueryInformationJobObject failed");
        var unit = new WindowsJobProcessUnit(ignored -> owner);
        unit.launch(spec());
        owner.direct.countDown();
        owner.empty.countDown();

        assertEquals(0, unit.done().block(Duration.ofSeconds(2)).exitCode());
        var failure = assertThrows(SubprocessException.class,
            () -> unit.waitForExit().block(Duration.ofSeconds(2)));
        assertEquals(SubprocessErrorCode.TERMINATION_FAILED, failure.code());
        assertEquals(1, owner.closeCalls.get());
    }

    @Test void nativeStartAndTerminationFailuresRemainLoud() {
        var startFailure = new WindowsJobProcessUnit(ignored -> {
            throw new IOException("CreateProcessW failed");
        });
        var spawn = assertThrows(SubprocessException.class, () -> startFailure.launch(spec()));
        assertEquals(SubprocessErrorCode.SPAWN_FAILED, spawn.code());

        var owner = new FakeOwner("", "", 1);
        owner.terminateFailure = new IOException("TerminateJobObject failed");
        var unit = new WindowsJobProcessUnit(ignored -> owner);
        unit.launch(spec());
        unit.terminate();

        var termination = assertThrows(SubprocessException.class,
            () -> unit.waitForExit().block(Duration.ofSeconds(2)));
        assertEquals(SubprocessErrorCode.TERMINATION_FAILED, termination.code());
        assertThrows(SubprocessException.class, () -> unit.done().block(Duration.ofSeconds(2)));
    }

    @Test void nullNativeOwnerIsSpawnFailureAndReleasesTheRegisteredEffect() {
        var releases = new AtomicInteger();
        var unit = new WindowsJobProcessUnit(ignored -> null);
        unit.ownedBy(new EffectHandle() {
            @Override public Mono<EffectHandle> ready() {
                return Mono.just(this);
            }

            @Override public boolean isDisposed() {
                return releases.get() > 0;
            }

            @Override public EffectMetadata metadata() {
                return new EffectMetadata("test", List.of());
            }

            @Override public Mono<Void> dispose() {
                releases.incrementAndGet();
                return Mono.empty();
            }
        });

        var failure = assertThrows(SubprocessException.class, () -> unit.launch(spec()));

        assertEquals(SubprocessErrorCode.SPAWN_FAILED, failure.code());
        assertEquals(1, releases.get());
        unit.waitForExit().block(Duration.ofSeconds(1));
    }

    private static SubprocessSpec spec() {
        return spec(Duration.ofSeconds(1));
    }

    private static SubprocessSpec spec(Duration grace) {
        return SubprocessSpec.builder().argv(List.of("tool.exe", "literal arg"))
            .cwd("C:\\target").stdoutMaxBytes(4).stderrMaxBytes(3)
            .grace(grace).build();
    }

    private static SubprocessSpec fallbackSpec() {
        return SubprocessSpec.builder().argv(List.of("bash", "-c", "exit 0"))
            .cwd(System.getProperty("java.io.tmpdir")).stdoutMaxBytes(4).stderrMaxBytes(3)
            .grace(Duration.ofSeconds(1)).build();
    }

    private static final class FakeOwner implements WindowsJobOwner {
        private final InputStream stdout;
        private final InputStream stderr;
        private final int exitCode;
        private final CountDownLatch direct = new CountDownLatch(1);
        private final CountDownLatch empty = new CountDownLatch(1);
        private final AtomicInteger terminateCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private IOException emptyFailure;
        private IOException terminateFailure;

        private FakeOwner(String stdout, String stderr, int exitCode) {
            this(new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8)), exitCode);
        }

        private FakeOwner(InputStream stdout, InputStream stderr, int exitCode) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }

        @Override public InputStream stdout() {
            return stdout;
        }

        @Override public InputStream stderr() {
            return stderr;
        }

        @Override public int waitForDirectExit() throws Exception {
            assertTrue(direct.await(2, TimeUnit.SECONDS));
            return exitCode;
        }

        @Override public void waitForEmpty() throws Exception {
            assertTrue(empty.await(2, TimeUnit.SECONDS));
            if (emptyFailure != null) throw emptyFailure;
        }

        @Override public void terminate() throws IOException {
            terminateCalls.incrementAndGet();
            if (terminateFailure != null) throw terminateFailure;
            direct.countDown();
            empty.countDown();
        }

        @Override public void close() {
            closeCalls.incrementAndGet();
            direct.countDown();
            empty.countDown();
        }
    }

    private static final class HeldOpenInputStream extends InputStream {
        private final byte[] prefix;
        private int offset;
        private boolean closed;

        private HeldOpenInputStream(String prefix) {
            this.prefix = prefix.getBytes(StandardCharsets.UTF_8);
        }

        @Override public int read() throws IOException {
            var single = new byte[1];
            int count = read(single, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(single[0]);
        }

        @Override public synchronized int read(byte[] target, int targetOffset, int length)
            throws IOException {
            if (offset < prefix.length) {
                int count = Math.min(length, prefix.length - offset);
                System.arraycopy(prefix, offset, target, targetOffset, count);
                offset += count;
                return count;
            }
            while (!closed) {
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", interrupted);
                }
            }
            return -1;
        }

        @Override public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }
}
