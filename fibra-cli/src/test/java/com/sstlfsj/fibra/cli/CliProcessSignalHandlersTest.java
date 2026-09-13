package com.sstlfsj.fibra.cli;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliProcessSignalHandlersTest {
    @Test
    void intAndTermAreRegisteredSeparatelyAndPreviousHandlersAreRestored() {
        var registrar = new RecordingRegistrar();
        var exits = new java.util.ArrayList<Integer>();
        var shutdown = new CliProcessShutdown(() -> { }, () -> { }, () -> { }, exits::add,
            Duration.ofSeconds(5), Runnable::run);

        try (shutdown; var ignored = CliProcessSignalHandlers.install(shutdown, registrar)) {
            registrar.handlers.get("INT").run();
            registrar.handlers.get("TERM").run();

            assertEquals(java.util.List.of(130), exits,
                "首个进程信号必须独占同一个关闭协调结果");
            assertEquals(Map.of(), registrar.restored);
        }

        assertEquals(Map.of("TERM", "previous-TERM", "INT", "previous-INT"),
            registrar.restored);
    }

    @Test
    void registeredSignalStartsTheDeadlineBeforeBlockingCancellation() throws Exception {
        var invocations = new CliInvocationCoordinator();
        var invocation = invocations.begin();
        var cancelEntered = new CountDownLatch(1);
        var releaseCancel = new CountDownLatch(1);
        invocation.token().cancelled().doOnSuccess(ignored -> {
            cancelEntered.countDown();
            await(releaseCancel);
        }).subscribe();
        var forced = new CountDownLatch(1);
        var exitCode = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        var registrar = new RecordingRegistrar();
        try (var shutdown = new CliProcessShutdown(invocations::stopAdmission,
                 () -> invocations.cancelActive().join(), () -> { }, code -> { }, code -> {
                     exitCode.set(code);
                     forced.countDown();
                 }, Duration.ofMillis(25), executor);
             var ignored = CliProcessSignalHandlers.install(shutdown, registrar)) {
            registrar.handlers.get("INT").run();

            assertTrue(cancelEntered.await(5, TimeUnit.SECONDS));
            assertTrue(forced.await(2, TimeUnit.SECONDS));
            assertEquals(8, exitCode.get());
        } finally {
            releaseCancel.countDown();
            invocation.close();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class RecordingRegistrar implements CliProcessSignalHandlers.Registrar {
        private final Map<String, Runnable> handlers = new LinkedHashMap<>();
        private final Map<String, Object> restored = new LinkedHashMap<>();

        @Override
        public Object register(String name, Runnable handler) {
            handlers.put(name, handler);
            return "previous-" + name;
        }

        @Override
        public void unregister(String name, Object previous) {
            restored.put(name, previous);
        }
    }
}
