package com.sstlfsj.fibra.bridge;

import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContributionDirectoryTest {
    private static final ContributionKind<CommandDescriptor, String, String> COMMAND =
        ContributionKind.local("command", CommandDescriptor.class,
            String.class, String.class);

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
}
