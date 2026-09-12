package com.sstlfsj.fibra.bridge;

import com.sstlfsj.fibra.value.LiteralValue;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContributionCallTest {
    private static final ContributionKind<CommandDescriptor, String, String> COMMAND =
        ContributionKind.local("command", CommandDescriptor.class,
            String.class, String.class);

    @Test
    void completedHandlerStillPinsRegistrationUntilTheCallIsClosed() throws Exception {
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var owner = runtime.rootScope().context();
            var registration = directory.register(owner, COMMAND, "plugin", "command",
                new CommandDescriptor("Command"), (invocation, input) -> Mono.just(input)).block();
            var call = directory.current().routes().acquire(COMMAND, registration.id());

            assertEquals("done", call.invoke(owner, "done").block());
            var revoked = registration.dispose().toFuture();
            assertFalse(revoked.isDone());

            call.close();
            revoked.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void closeIsIdempotent() throws Exception {
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var owner = runtime.rootScope().context();
            var registration = directory.register(owner, COMMAND, "plugin", "command",
                new CommandDescriptor("Command"), (invocation, input) -> Mono.just(input)).block();
            var call = directory.current().routes().acquire(COMMAND, registration.id());

            call.close();
            call.close();

            registration.dispose().toFuture().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void retainedRoutesDoNotResolveANewEntryWithTheSameId() {
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var owner = runtime.rootScope().context();
            var first = directory.register(owner, COMMAND, "plugin", "command",
                new CommandDescriptor("First"), (invocation, input) -> Mono.just("first")).block();
            var oldRoutes = directory.current().routes();
            var oldCall = oldRoutes.acquire(COMMAND, first.id());

            var revoked = first.dispose().toFuture();
            assertFalse(revoked.isDone());
            oldCall.close();
            revoked.join();
            var second = directory.register(owner, COMMAND, "plugin", "command",
                new CommandDescriptor("Second"), (invocation, input) -> Mono.just("second")).block();

            assertThrows(ContributionUnavailableException.class,
                () -> oldRoutes.acquire(COMMAND, second.id()));
            try (var newCall = directory.current().routes().acquire(COMMAND, second.id())) {
                assertEquals("second", newCall.invoke(owner, "").block());
            }
        }
    }

    @Test
    void cancellingAHandlerDoesNotReleaseAnExplicitCall() throws Exception {
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var owner = runtime.rootScope().context();
            var registration = directory.register(owner, COMMAND, "plugin", "command",
                new CommandDescriptor("Command"), (invocation, input) -> Mono.never()).block();
            var call = directory.current().routes().acquire(COMMAND, registration.id());
            var invocation = call.invoke(owner, "").toFuture();
            try {
                invocation.cancel(true);
                var revoked = registration.dispose().toFuture();
                assertFalse(revoked.isDone());

                call.close();
                revoked.get(3, TimeUnit.SECONDS);
            } finally {
                call.close();
            }
        }
    }

    @Test
    void cleanupFailureRevokesAdmissionThenWaitsForOtherLeasesBeforeFailingDrain()
        throws Exception {
        var runtime = FibraRuntime.create();
        try {
            var directory = new ContributionDirectory();
            var owner = runtime.rootScope().context();
            var registration = directory.register(owner, COMMAND, "plugin", "command",
                new CommandDescriptor("Command"), (invocation, input) -> Mono.just(input)).block();
            var routes = directory.current().routes();
            var failed = routes.acquire(COMMAND, registration.id());
            var retained = routes.acquire(COMMAND, registration.id());
            var draining = registration.dispose().toFuture();

            assertEquals(List.of(), directory.current().snapshot().entries());
            failed.failCleanup("invocation cleanup failed");
            assertFalse(draining.isDone());

            retained.close();
            var failure = assertThrows(ExecutionException.class,
                () -> draining.get(3, TimeUnit.SECONDS));
            var drainFailure = assertInstanceOf(ContributionDrainException.class,
                failure.getCause());
            assertEquals("invocation cleanup failed", drainFailure.detail());
            assertThrows(ContributionUnavailableException.class,
                () -> routes.acquire(COMMAND, registration.id()));
            assertTrue(directory.closeAsync().toFuture().isCompletedExceptionally());
            assertRuntimeCloseRetainsContributionFailure(runtime, "invocation cleanup failed");
        } finally {
            runtime.closeAsync().onErrorResume(ignored -> Mono.empty()).block();
        }
    }

    @Test
    void aCallMayBeSubscribedOnceAndNeverAfterItHasClosed() {
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var owner = runtime.rootScope().context();
            var invocations = new AtomicInteger();
            var registration = directory.register(owner, COMMAND, "plugin", "command",
                new CommandDescriptor("Command"), (invocation, input) -> Mono.fromSupplier(() -> {
                    invocations.incrementAndGet();
                    return input;
                })).block();
            var call = directory.current().routes().acquire(COMMAND, registration.id());
            var delayed = call.invoke(owner, "delayed");

            call.close();
            assertThrows(IllegalStateException.class, delayed::block);
            assertEquals(0, invocations.get());

            var once = directory.current().routes().acquire(COMMAND, registration.id());
            var invocation = once.invoke(owner, "once");
            assertEquals("once", invocation.block());
            assertThrows(IllegalStateException.class, invocation::block);
            assertEquals(1, invocations.get());
            once.close();
        }
    }

    @Test
    void batchDrainWaitsForEveryLeaseBeforeReportingACleanupFailure() throws Exception {
        var runtime = FibraRuntime.create();
        try {
            var directory = new ContributionDirectory();
            var owner = runtime.rootScope().openChild("provider");
            var afterDrain = new AtomicInteger();
            List<ContributionBinding<?, ?, ?>> bindings = List.of(
                new ContributionBinding<>(COMMAND, "first", new CommandDescriptor("First"),
                    (ContributionHandler<String, String>) (invocation, input) -> Mono.just(input)),
                new ContributionBinding<>(COMMAND, "second", new CommandDescriptor("Second"),
                    (ContributionHandler<String, String>) (invocation, input) -> Mono.just(input)));
            var registrations = directory.registerAll(owner.context(), "plugin", bindings,
                () -> Mono.fromRunnable(afterDrain::incrementAndGet)).block();
            var routes = directory.current().routes();
            var failed = routes.acquire(COMMAND, registrations.getFirst().id());
            var retained = routes.acquire(COMMAND, registrations.get(1).id());
            var closing = owner.closeAsync().toFuture();

            assertEquals(List.of(), directory.current().snapshot().entries());
            failed.failCleanup("first invocation cleanup failed");
            assertFalse(closing.isDone());

            retained.close();
            var failure = assertThrows(ExecutionException.class,
                () -> closing.get(3, TimeUnit.SECONDS));
            assertResourceDrainRetainsContributionFailure(failure, "first invocation cleanup failed");
            assertEquals(0, afterDrain.get());
            assertRuntimeCloseRetainsContributionFailure(runtime, "first invocation cleanup failed");
        } finally {
            runtime.closeAsync().onErrorResume(ignored -> Mono.empty()).block();
        }
    }

    @Test
    void legacyCodecsKeepTheDefaultRemoteFailureAndCancellationBehavior() {
        ContributionCodec<String, String, String> codec = new ContributionCodec<>() {
            @Override public int schemaVersion() { return 1; }
            @Override public String decodeDescriptor(Object descriptor) { return descriptor.toString(); }
            @Override public Object encodeInput(String input) { return input; }
            @Override public String decodeInput(Object input) { return input.toString(); }
            @Override public Object encodeOutput(String output) { return output; }
            @Override public String decodeOutput(Object output) { return output.toString(); }
        };

        assertFalse(codec.cancellationToken("input").isCancelled());
        assertInstanceOf(java.util.concurrent.CancellationException.class,
            codec.cancellationException());
        assertTrue(codec.mapRemoteFailure(new RemoteContributionFailure(-32000, "failed",
            LiteralValue.of(null))).isEmpty());
    }

    private static void assertRuntimeCloseRetainsContributionFailure(
        FibraRuntime runtime, String detail) throws Exception {
        var failure = assertThrows(ExecutionException.class,
            () -> runtime.closeAsync().toFuture().get(3, TimeUnit.SECONDS));
        assertResourceDrainRetainsContributionFailure(failure, detail);
    }

    private static void assertResourceDrainRetainsContributionFailure(
        ExecutionException failure, String detail) {
        var resourceDrain = assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("owned resource drain failed; resources retained", resourceDrain.getMessage());
        Throwable cause = resourceDrain;
        while (!(cause instanceof ContributionDrainException)) {
            cause = cause.getCause();
            if (cause == null) {
                throw new AssertionError("resource drain did not retain the contribution failure",
                    failure);
            }
        }
        assertEquals(detail, ((ContributionDrainException) cause).detail());
    }

    private record CommandDescriptor(String title) {
    }
}
