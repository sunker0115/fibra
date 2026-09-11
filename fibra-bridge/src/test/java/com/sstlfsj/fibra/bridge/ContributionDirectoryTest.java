package com.sstlfsj.fibra.bridge;

import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContributionDirectoryTest {
    private static final ContributionKind<CommandDescriptor, String, String> COMMAND =
        ContributionKind.local("command", CommandDescriptor.class,
            String.class, String.class);
    private static final ServiceKey<ScopedService> SCOPED_SERVICE =
        ServiceKey.of("scoped-service", ScopedService.class);

    @Test
    void handlerUsesRegistrationContextWhileInvocationResourcesBelongToCallerScope() {
        var disposed = new AtomicInteger();
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var ownerScope = runtime.rootScope().openChild("provider");
            var owner = ownerScope.context().withRealm(SCOPED_SERVICE, "provider-realm");
            owner.services().provide(SCOPED_SERVICE, invocation -> {
                invocation.effects().add(Disposables.from(disposed::incrementAndGet));
                return "resolved";
            });
            var registration = directory.register(owner, COMMAND, "p", "c",
                new CommandDescriptor("Command"), (invocation, input) -> {
                    assertSame(owner, invocation.caller());
                    return reactor.core.publisher.Mono.fromSupplier(() ->
                        invocation.service(SCOPED_SERVICE).invoke((serviceInvocation, service) ->
                            service.call(serviceInvocation)));
                }).block();
            var caller = runtime.rootScope().openChild("invocation");

            assertEquals("resolved", directory.current().routes().invoke(caller.context(),
                COMMAND, registration.id(), "").block());
            assertEquals(0, disposed.get());

            caller.closeAsync().block(Duration.ofSeconds(3));
            assertEquals(1, disposed.get());
            assertFalse(ownerScope.isClosed());
        }
    }

    @Test
    void revokedRouteRejectsNewSubscriptionsEvenWhenTheSnapshotIsRetained() {
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var owner = runtime.rootScope().context();
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var registration = directory.register(owner, COMMAND, "p", "c",
                new CommandDescriptor("Command"), (invocation, input) -> {
                    calls.incrementAndGet();
                    return reactor.core.publisher.Mono.just(input);
                }).block();
            var routes = directory.current().routes();
            registration.dispose().block();

            assertThrows(ContributionUnavailableException.class, () -> routes.invoke(
                owner, COMMAND, registration.id(), "late").block());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void eachSubscriptionAcquiresItsOwnCallAndAnUnsubscribedCallDoesNotPinTheOwner() {
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var owner = runtime.rootScope().openChild("provider");
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var registration = directory.register(owner.context(), COMMAND, "p", "c",
                new CommandDescriptor("Command"), (invocation, input) -> {
                    calls.incrementAndGet();
                    return reactor.core.publisher.Mono.just(input);
                }).block();
            var call = directory.current().routes().invoke(runtime.rootScope().context(),
                COMMAND, registration.id(), "value");
            assertEquals(0, calls.get());
            assertEquals("value", call.block());
            assertEquals("value", call.block());
            assertEquals(2, calls.get());
            owner.closeAsync().block(java.time.Duration.ofSeconds(3));
            assertThrows(ContributionUnavailableException.class, call::block);
        }
    }

    @Test
    void neverSubscribedCallDoesNotDelayProviderDisposal() {
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var owner = runtime.rootScope().openChild("provider");
            var registration = directory.register(owner.context(), COMMAND, "p", "c",
                new CommandDescriptor("Command"), (invocation, input) ->
                    reactor.core.publisher.Mono.never()).block();
            var call = directory.current().routes().invoke(runtime.rootScope().context(),
                COMMAND, registration.id(), "");

            owner.closeAsync().block(java.time.Duration.ofSeconds(3));

            assertThrows(ContributionUnavailableException.class, call::block);
        }
    }

    @Test
    void cancellationReleasesTheCallBeforeRevokedProviderFinishesClosing() throws Exception {
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var owner = runtime.rootScope().openChild("provider");
            var registration = directory.register(owner.context(), COMMAND, "p", "c",
                new CommandDescriptor("Command"), (invocation, input) ->
                    reactor.core.publisher.Mono.never()).block();
            var call = directory.current().routes().invoke(runtime.rootScope().context(),
                COMMAND, registration.id(), "").toFuture();
            var close = owner.closeAsync().toFuture();
            try {
                assertFalse(close.isDone());
            } finally {
                call.cancel(true);
            }
            close.get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void synchronousHandlerFailureReleasesTheCall() {
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var owner = runtime.rootScope().openChild("provider");
            var failure = new IllegalStateException("handler failed");
            var registration = directory.register(owner.context(), COMMAND, "p", "c",
                new CommandDescriptor("Command"), (invocation, input) -> {
                    throw failure;
                }).block();

            org.junit.jupiter.api.Assertions.assertSame(failure,
                assertThrows(IllegalStateException.class, () -> directory.current().routes().invoke(
                    runtime.rootScope().context(), COMMAND, registration.id(), "").block()));
            owner.closeAsync().block(java.time.Duration.ofSeconds(3));
        }
    }

    @Test
    void runtimeCleanupIsNotEvenInvokedUntilContributionsHaveDrained() throws Exception {
        var response = Sinks.<String>one();
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var owner = runtime.rootScope().openChild("provider");
            var cleanupInvocations = new java.util.concurrent.atomic.AtomicInteger();
            var binding = new ContributionBinding<>(COMMAND, "c", new CommandDescriptor("Command"),
                (ContributionHandler<String, String>) (invocation, input) -> response.asMono());
            var registrations = directory.registerAll(owner.context(), "p", java.util.List.of(binding),
                () -> {
                    cleanupInvocations.incrementAndGet();
                    return reactor.core.publisher.Mono.empty();
                }).block();
            var call = directory.current().routes().invoke(runtime.rootScope().context(), COMMAND,
                registrations.getFirst().id(), "").toFuture();
            var closing = owner.closeAsync().toFuture();
            try {
                assertEquals(0, cleanupInvocations.get());
                assertFalse(closing.isDone());
            } finally {
                response.tryEmitValue("done");
            }
            assertEquals("done", call.get(3, java.util.concurrent.TimeUnit.SECONDS));
            closing.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, cleanupInvocations.get());
        }
    }

    @Test
    void repeatedCloseWaitsForCallsAlreadyRevokedFromTheDirectory() {
        var response = Sinks.<String>one();
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var owner = runtime.rootScope().context();
            var registration = directory.register(owner, COMMAND, "p", "c",
                new CommandDescriptor("Command"), (invocation, input) -> response.asMono()).block();
            var call = directory.current().routes().invoke(owner, COMMAND, registration.id(), "").toFuture();
            var revoked = registration.dispose().toFuture();
            var firstClose = directory.closeAsync().toFuture();
            var secondClose = directory.closeAsync().toFuture();
            try {
                assertFalse(revoked.isDone());
                assertFalse(firstClose.isDone());
                assertFalse(secondClose.isDone());
            } finally {
                response.tryEmitValue("done");
            }
            assertEquals("done", call.join());
            revoked.join();
            firstClose.join();
            secondClose.join();
        }
    }

    @Test
    void bindsRegistrationToScopeAndDrainsInflightCallsBeforeRevocation() {
        try (var runtime = FibraRuntime.create();
             var directory = new ContributionDirectory()) {
            var provider = runtime.rootScope().openChild("provider");
            var caller = runtime.rootScope().openChild("caller");
            var response = Sinks.<String>one();
            var registration = directory.register(provider.context(), COMMAND,
                "plugin-a", "greet", new CommandDescriptor("Greet"),
                (invocation, input) -> response.asMono().map(value -> value + input)).block();
            var id = new ContributionId("plugin-a", "greet");
            var published = directory.current();

            var result = published.routes().invoke(
                caller.context(), COMMAND, id, " Fibra").toFuture();
            var closing = provider.closeAsync().toFuture();

            assertFalse(closing.isDone());
            assertThrows(ContributionUnavailableException.class,
                () -> directory.current().routes().invoke(
                    caller.context(), COMMAND, id, "late").block());
            response.tryEmitValue("Hello");
            assertEquals("Hello Fibra", result.join());
            closing.join();
            assertEquals(0, directory.current().snapshot().entries().size());
            assertEquals(id, registration.id());
        }
    }

    @Test
    void freezesRouteMembershipWithTheMatchingSnapshotRevision() {
        try (var runtime = FibraRuntime.create();
             var directory = new ContributionDirectory()) {
            var owner = runtime.rootScope().context();
            directory.register(owner, COMMAND, "plugin-a", "first",
                new CommandDescriptor("First"),
                (invocation, input) -> reactor.core.publisher.Mono.just("first"))
                .block();
            var first = directory.current();
            directory.register(owner, COMMAND, "plugin-a", "second",
                new CommandDescriptor("Second"),
                (invocation, input) -> reactor.core.publisher.Mono.just("second"))
                .block();
            var second = directory.current();

            assertEquals(1, first.snapshot().entries().size());
            assertEquals(2, second.snapshot().entries().size());
            assertEquals(first.snapshot().revision() + 1,
                second.snapshot().revision());
            assertThrows(ContributionUnavailableException.class, () ->
                first.routes().invoke(owner, COMMAND,
                    new ContributionId("plugin-a", "second"), "").block());
        }
    }

    @Test
    void leavesExternalNamesToScenarioAdapters() {
        var id = new ContributionId("plugin-a", "greet");
        ContributionAdapter<CommandDescriptor> harness =
            (contributionId, descriptor) -> "plugin__"
                + contributionId.providerInstanceId() + "__"
                + contributionId.localName();
        ContributionAdapter<CommandDescriptor> cli =
            (contributionId, descriptor) -> descriptor.title().toLowerCase();

        assertEquals("plugin__plugin-a__greet",
            harness.externalName(id, new CommandDescriptor("Greet")));
        assertEquals("greet", cli.externalName(id, new CommandDescriptor("Greet")));
    }

    private record CommandDescriptor(String title) {
    }

    @FunctionalInterface
    private interface ScopedService {
        String call(com.sstlfsj.fibra.InvocationContext invocation);
    }
}
