package com.sstlfsj.fibra.plugins.subprocess.local;

import com.sstlfsj.fibra.CancellationToken;
import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.EffectHandle;
import com.sstlfsj.fibra.EffectMetadata;
import com.sstlfsj.fibra.Effects;
import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.Scope;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessErrorCode;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessException;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessSpec;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSubprocessTest {
    @TempDir
    Path directory;
    private final LocalSubprocess subprocess = new LocalSubprocess("node");

    private SubprocessSpec spec(String command) {
        return spec(command, Duration.ofSeconds(2));
    }

    private SubprocessSpec spec(String command, Duration grace) {
        return SubprocessSpec.builder().argv(List.of("bash", "-c", command))
            .cwd(directory.toString()).stdoutMaxBytes(5).stderrMaxBytes(4)
            .grace(grace).build();
    }

    @Test void capturesSeparateBoundedOutputAndNonzeroExit() {
        try (var runtime = FibraRuntime.create()) {
            var unit = subprocess.spawn(InvocationContext.of(runtime.rootScope().context(), "test"),
                spec("printf abcdefgh; printf error123 >&2; exit 7")).block();
            var result = unit.done().block(Duration.ofSeconds(5));
            assertEquals(7, result.exitCode());
            assertEquals("defgh", result.stdout().text());
            assertEquals(8, result.stdout().totalBytes());
            assertTrue(result.stdout().truncated());
            assertEquals("r123", result.stderr().text());
            assertTrue(result.stderr().truncated());
            unit.waitForExit().block(Duration.ofSeconds(5));
        }
    }

    @Test void keepsByteExactUtf8TailAcrossOutputChunks() {
        try (var runtime = FibraRuntime.create()) {
            var command = "node -e 'process.stdout.write(Buffer.from([0x61,0xe4]));"
                + "setTimeout(()=>process.stdout.write(Buffer.from([0xbd,0xa0,0xe5,0xa5,0xbd])),50)'";
            var unit = subprocess.spawn(InvocationContext.of(runtime.rootScope().context(), "test"),
                spec(command)).block();
            var result = unit.done().block(Duration.ofSeconds(5));
            assertEquals("��好", result.stdout().text());
            assertEquals(7, result.stdout().totalBytes());
            assertTrue(result.stdout().truncated());
            unit.waitForExit().block(Duration.ofSeconds(5));
        }
    }

    @Test void parentExitStillTerminatesItsBackgroundDescendant() throws Exception {
        try (var runtime = FibraRuntime.create()) {
            var unit = subprocess.spawn(InvocationContext.of(runtime.rootScope().context(), "test"),
                spec("sleep 60 >/dev/null 2>&1 & echo $! > child.pid; exit 0")).block();
            assertEquals(0, unit.done().block(Duration.ofSeconds(5)).exitCode());
            long child = Long.parseLong(Files.readString(directory.resolve("child.pid")).trim());
            unit.waitForExit().block(Duration.ofSeconds(5));
            assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
            unit.terminate();
            unit.dispose().block();
            unit.drain().block();
        }
    }

    @Test void freezesOutputOnlyAfterInheritedPipesClose() {
        try (var runtime = FibraRuntime.create()) {
            var command = "node -e 'const fs=require(\"fs\");"
                + "process.on(\"SIGTERM\",()=>{process.stdout.write(\"after\");process.exit(0)});"
                + "fs.writeFileSync(\"child-ready\",\"\");setInterval(()=>{},1000)' & "
                + "while [ ! -f child-ready ]; do sleep 0.01; done; exit 0";
            var unit = subprocess.spawn(InvocationContext.of(runtime.rootScope().context(), "test"),
                spec(command)).block();
            var outcome = unit.done().block(Duration.ofSeconds(5));
            assertEquals("after", outcome.stdout().text());
            assertFalse(outcome.stdout().truncated());
            unit.waitForExit().block(Duration.ofSeconds(5));
        }
    }

    @Test void grantsTheCompleteGraceBeforeEscalatingToKill() throws Exception {
        try (var runtime = FibraRuntime.create()) {
            var command = "node -e 'const fs=require(\"fs\");"
                + "process.on(\"SIGTERM\",()=>setTimeout(()=>process.exit(0),750));"
                + "fs.writeFileSync(\"ready\",\"\");setInterval(()=>{},1000)'";
            var unit = subprocess.spawn(InvocationContext.of(runtime.rootScope().context(), "test"),
                spec(command, Duration.ofSeconds(1))).block();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!Files.exists(directory.resolve("ready")) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(Files.exists(directory.resolve("ready")));
            unit.terminate();
            var outcome = unit.done().block(Duration.ofSeconds(3));
            assertEquals(0, outcome.exitCode());
            assertNull(outcome.signal());
            unit.waitForExit().block(Duration.ofSeconds(3));
        }
    }

    @Test void callerScopeCloseTerminatesLiveTree() throws Exception {
        try (var runtime = FibraRuntime.create()) {
            var caller = runtime.rootScope().openChild("caller");
            var unit = subprocess.spawn(InvocationContext.of(caller.context(), "test"),
                spec("printf '%s' $$ > payload.pid; sleep 60 & echo $! > child.pid; wait")).block();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!Files.exists(directory.resolve("child.pid")) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            long payload = Long.parseLong(Files.readString(directory.resolve("payload.pid")).trim());
            long child = Long.parseLong(Files.readString(directory.resolve("child.pid")).trim());
            caller.closeAsync().block(Duration.ofSeconds(5));
            unit.waitForExit().block(Duration.ofSeconds(1));
            assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
            assertFalse(ProcessHandle.of(payload).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test void confirmedQuiescenceReleasesTheCallerEffect() throws Exception {
        var effects = new TrackingEffects();
        var contextHolder = new java.util.concurrent.atomic.AtomicReference<Context>();
        var scope = (Scope) java.lang.reflect.Proxy.newProxyInstance(
            Scope.class.getClassLoader(), new Class<?>[] {Scope.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("context")) return contextHolder.get();
                if (method.getName().equals("sharesDomainWith")) return proxy == arguments[0];
                if (method.getDeclaringClass() == Object.class) return method.invoke(this, arguments);
                throw new UnsupportedOperationException(method.getName());
            });
        var context = (Context) java.lang.reflect.Proxy.newProxyInstance(
            Context.class.getClassLoader(), new Class<?>[] {Context.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("effects")) return effects;
                if (method.getName().equals("scope")) return scope;
                if (method.getDeclaringClass() == Object.class) return method.invoke(this, arguments);
                throw new UnsupportedOperationException(method.getName());
            });
        contextHolder.set(context);
        var unit = subprocess.spawn(InvocationContext.of(context, scope, "test"), spec("exit 0")).block();
        unit.waitForExit().block(Duration.ofSeconds(5));
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (effects.disposals.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(1, effects.disposals.get());
    }

    @Test void confirmedQuiescenceReleasesTheProviderCancellationListener() throws Exception {
        var cancellation = new TrackingCancellationToken();
        try (var runtime = FibraRuntime.create()) {
            var invocation = InvocationContext.of(runtime.rootScope().context(), "test")
                .withCancellation(cancellation);
            var unit = subprocess.spawn(invocation, spec("sleep 0.2")).block();
            assertEquals(1, cancellation.listeners.get());
            unit.waitForExit().block(Duration.ofSeconds(5));
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (cancellation.listeners.get() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(0, cancellation.listeners.get());
        }
    }

    @Test void rejectsGraceBeyondTheSupportedTimerRange() {
        try (var runtime = FibraRuntime.create()) {
            var oversized = spec("exit 0", Duration.ofMillis((long) Integer.MAX_VALUE + 1));
            var failure = assertThrows(SubprocessException.class, () -> subprocess.spawn(
                InvocationContext.of(runtime.rootScope().context(), "test"), oversized).block(Duration.ofSeconds(5)));
            assertEquals(SubprocessErrorCode.SPAWN_FAILED, failure.code());
            assertEquals("grace must not exceed 2147483647 ms", failure.getMessage());
        }
    }

    @Test void missingExecutableIsSpawnFailure() {
        try (var runtime = FibraRuntime.create()) {
            var bad = SubprocessSpec.builder().argv(List.of("/no/such/fibra-command"))
                .cwd(directory.toString()).stdoutMaxBytes(10).stderrMaxBytes(10)
                .grace(Duration.ofSeconds(1)).build();
            var error = assertThrows(SubprocessException.class, () ->
                subprocess.spawn(InvocationContext.of(runtime.rootScope().context(), "test"), bad)
                    .block(Duration.ofSeconds(5)));
            assertEquals(SubprocessErrorCode.SPAWN_FAILED, error.code());
        }
    }

    @Test void supervisorThatExitsBeforePayloadStartIsSpawnFailure() {
        try (var runtime = FibraRuntime.create()) {
            var error = assertThrows(SubprocessException.class, () ->
                new LocalSubprocess("/usr/bin/false").spawn(
                    InvocationContext.of(runtime.rootScope().context(), "test"), spec("pwd"))
                    .block(Duration.ofSeconds(5)));
            assertEquals(SubprocessErrorCode.SPAWN_FAILED, error.code());
        }
    }


    private static final class TrackingEffects implements Effects {
        private final AtomicInteger disposals = new AtomicInteger();

        @Override
        public EffectHandle add(Disposable disposable) {
            return new EffectHandle() {
                @Override
                public Mono<EffectHandle> ready() {
                    return Mono.just(this);
                }

                @Override
                public boolean isDisposed() {
                    return disposals.get() > 0;
                }

                @Override
                public EffectMetadata metadata() {
                    return new EffectMetadata("test", List.of());
                }

                @Override
                public Mono<Void> dispose() {
                    disposals.incrementAndGet();
                    return Mono.empty();
                }
            };
        }

        @Override
        public EffectHandle effect(java.util.function.Supplier<? extends Disposable> source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EffectHandle effect(java.util.function.Supplier<? extends Disposable> source, String label) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EffectHandle collect(Publisher<? extends Disposable> source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EffectHandle collect(Publisher<? extends Disposable> source, String label) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EffectHandle supervise(Publisher<?> health, String label) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class TrackingCancellationToken implements CancellationToken {
        private final Sinks.Empty<Void> cancellation = Sinks.empty();
        private final AtomicInteger listeners = new AtomicInteger();

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public Mono<Void> cancelled() {
            return cancellation.asMono()
                .doOnSubscribe(ignored -> listeners.incrementAndGet())
                .doFinally(ignored -> listeners.decrementAndGet());
        }
    }
}
