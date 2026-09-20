package com.sstlfsj.fibra.bridge;

import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContributionAdmissionTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final ContributionKind<String, String, String> COMMAND =
        ContributionKind.local("command", String.class, String.class, String.class);

    @Test
    void closingAnAdmissionSynchronouslyRevokesEveryRouteWhileAnAcceptedCallDrains() {
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var admission = directory.openAdmission("unit");
            var firstResult = Sinks.<String>one();
            var registrations = admission.registerAll(runtime.rootScope().context(),
                List.of(
                    new ContributionBinding<>(COMMAND, "first", "first",
                        (ContributionHandler<String, String>) (invocation, input) -> firstResult.asMono()),
                    new ContributionBinding<>(COMMAND, "second", "second",
                        (ContributionHandler<String, String>) (invocation, input) -> Mono.just(input))),
                Disposables.noop()).block(TIMEOUT);
            var routes = directory.current().routes();
            var first = routes.invoke(runtime.rootScope().context(), COMMAND,
                registrations.getFirst().id(), "accepted").toFuture();

            admission.closeAdmission();

            assertEquals(List.of(), directory.current().snapshot().entries());
            assertThrows(ContributionUnavailableException.class, () -> routes.invoke(
                runtime.rootScope().context(), COMMAND, registrations.get(1).id(), "late").block());
            var draining = admission.drainAsync().toFuture();
            assertFalse(draining.isDone());
            firstResult.tryEmitValue("done");
            assertEquals("done", first.join());
            draining.join();
        }
    }

    @Test
    void closeWinsAgainstLateAndConcurrentRegistrationWithoutTransientPublication()
        throws Exception {
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var lateAdmission = directory.openAdmission("late");
            var late = lateAdmission.register(runtime.rootScope().context(), COMMAND,
                "route", "late", (invocation, input) -> Mono.just(input));
            lateAdmission.closeAdmission();
            assertThrows(IllegalStateException.class, () -> late.block(TIMEOUT));
            assertEquals(List.of(), directory.current().snapshot().entries());

            for (var attempt = 0; attempt < 100; attempt++) {
                var unit = "unit-" + attempt;
                var admission = directory.openAdmission(unit);
                var ready = new CountDownLatch(1);
                var start = new CountDownLatch(1);
                try (var executor = Executors.newFixedThreadPool(2)) {
                    var registering = executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        try {
                            admission.register(runtime.rootScope().context(), COMMAND,
                                "route", "route",
                                (invocation, input) -> Mono.just(input)).block(TIMEOUT);
                        } catch (IllegalStateException expected) {
                            // Closing may acquire the directory monitor first.
                        }
                        return null;
                    });
                    var closing = executor.submit(() -> {
                        ready.await();
                        start.await();
                        admission.closeAdmission();
                        return null;
                    });
                    start.countDown();
                    registering.get(3, TimeUnit.SECONDS);
                    closing.get(3, TimeUnit.SECONDS);
                }
                assertEquals(List.of(), directory.current().snapshot().entries());
            }
        }
    }

    @Test
    void drainWaitsForAcceptedCallsWithoutRunningResourceCleanup() {
        var afterDrain = new AtomicInteger();
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var admission = directory.openAdmission("unit");
            var result = Sinks.<String>one();
            var registration = admission.registerAll(runtime.rootScope().context(),
                List.of(new ContributionBinding<>(COMMAND, "route", "route",
                    (ContributionHandler<String, String>) (invocation, input) -> result.asMono())),
                () -> Mono.fromRunnable(afterDrain::incrementAndGet)).block(TIMEOUT).getFirst();
            var call = directory.current().routes().invoke(runtime.rootScope().context(), COMMAND,
                registration.id(), "accepted").toFuture();

            admission.closeAdmission();
            var draining = admission.drainAsync().toFuture();
            assertFalse(draining.isDone());
            assertEquals(0, afterDrain.get());

            result.tryEmitValue("done");
            assertEquals("done", call.join());
            draining.join();
            assertEquals(0, afterDrain.get());

            runtime.closeAsync().block(TIMEOUT);
            assertEquals(1, afterDrain.get());
        }
    }

    @Test
    void directoryCloseClosesAdmissionsAndWaitsForTheirCalls() {
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var admission = directory.openAdmission("unit");
            var result = Sinks.<String>one();
            var registration = admission.register(runtime.rootScope().context(), COMMAND,
                "route", "route", (invocation, input) -> result.asMono()).block(TIMEOUT);
            var call = directory.current().routes().invoke(runtime.rootScope().context(), COMMAND,
                registration.id(), "accepted").toFuture();

            var closing = directory.closeAsync().toFuture();
            assertFalse(closing.isDone());
            assertThrows(ContributionUnavailableException.class, () -> directory.current().routes()
                .invoke(runtime.rootScope().context(), COMMAND, registration.id(), "late").block());
            assertThrows(IllegalStateException.class, () -> admission.register(
                runtime.rootScope().context(), COMMAND, "other", "other",
                (invocation, input) -> Mono.just(input)).block());

            result.tryEmitValue("done");
            assertEquals("done", call.join());
            closing.join();
            assertTrue(directory.current().snapshot().entries().isEmpty());
        }
    }
}
