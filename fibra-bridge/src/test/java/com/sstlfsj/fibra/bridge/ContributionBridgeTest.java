package com.sstlfsj.fibra.bridge;

import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContributionBridgeTest {
    private static final ContributionKind<CommandDescriptor, String, String> COMMAND =
        ContributionKind.local("command", CommandDescriptor.class,
            String.class, String.class);

    @Test
    void bindsRegistrationToScopeAndDrainsInflightCallsBeforeRevocation() {
        try (var runtime = FibraRuntime.create()) {
            var bridge = new ContributionBridge();
            var provider = runtime.rootScope().openChild("provider");
            var caller = runtime.rootScope().openChild("caller");
            var response = Sinks.<String>one();
            var registration = bridge.register(provider.context(), COMMAND,
                "plugin-a", "greet", new CommandDescriptor("Greet"),
                (invocation, input) -> response.asMono().map(value -> value + input)).block();
            var id = new ContributionId("plugin-a", "greet");

            var result = bridge.invoke(caller.context(), COMMAND, id, " Fibra").toFuture();
            var closing = provider.closeAsync().toFuture();

            assertFalse(closing.isDone());
            assertThrows(ContributionUnavailableException.class,
                () -> bridge.invoke(caller.context(), COMMAND, id, "late").block());
            response.tryEmitValue("Hello");
            assertEquals("Hello Fibra", result.join());
            closing.join();
            assertEquals(0, bridge.snapshot().entries().size());
            assertEquals(id, registration.id());
        }
    }

    @Test
    void leavesExternalNamesToScenarioAdapters() {
        var id = new ContributionId("plugin-a", "greet");
        ContributionAdapter<CommandDescriptor> harness =
            (contributionId, descriptor) -> "plugin__" + contributionId.providerInstanceId()
                + "__" + contributionId.localName();
        ContributionAdapter<CommandDescriptor> cli =
            (contributionId, descriptor) -> descriptor.title().toLowerCase();

        assertEquals("plugin__plugin-a__greet",
            harness.externalName(id, new CommandDescriptor("Greet")));
        assertEquals("greet", cli.externalName(id, new CommandDescriptor("Greet")));
    }

    private record CommandDescriptor(String title) {
    }
}
