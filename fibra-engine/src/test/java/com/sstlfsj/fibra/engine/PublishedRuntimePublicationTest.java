package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.config.DesiredEntry;
import com.sstlfsj.fibra.config.DesiredGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublishedRuntimePublicationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ContributionKind<CommandDescriptor, String, String> COMMAND =
        ContributionKind.local("command", CommandDescriptor.class,
            String.class, String.class);
    private static final ContributionId ID = new ContributionId("command", "run");

    @Test
    void publishesStateAndRoutesAsOneViewAndRejectsAStaleRevision() throws Exception {
        var repository = new InMemoryDesiredStateRepository(graph("old-"));
        try (var engine = FibraEngine.builder(repository)
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition(),
                value -> (String) value))).build()) {
            var first = engine.start().block(TIMEOUT);

            assertEquals(EngineState.RUNNING, first.engine().state());
            assertEquals(1, first.contributions().entries().size());
            assertEquals(first.generationRevision(),
                first.diagnostics().generationRevision());
            assertEquals(first.generationRevision(),
                first.engineDiagnostics().currentGenerationRevision());
            assertEquals("old-value", engine.published().invoke(
                first.viewRevision(), COMMAND, ID, "value").block(TIMEOUT));

            var candidateSignal = engine.published().views()
                .filter(view -> view.engineDiagnostics().candidateGenerationRevision()
                    != null)
                .next().toFuture();
            var second = engine.submit(new ReplaceDesiredGraph(
                first.viewRevision(), first.engine().desiredSource().revision(),
                graph("new-"))).block(TIMEOUT).view();
            var candidate = candidateSignal.get(5, TimeUnit.SECONDS);

            assertEquals(first.generationRevision(), candidate.generationRevision());
            assertNotEquals(candidate.generationRevision(),
                candidate.engineDiagnostics().candidateGenerationRevision());
            assertNotEquals(first.viewRevision(), second.viewRevision());
            assertNotEquals(first.generationRevision(), second.generationRevision());
            assertEquals(second.generationRevision(),
                second.diagnostics().generationRevision());
            assertEquals(second.generationRevision(),
                second.engineDiagnostics().currentGenerationRevision());
            assertNull(second.engineDiagnostics().candidateGenerationRevision());
            assertThrows(PublishedRevisionConflictException.class, () ->
                engine.published().invoke(first.viewRevision(), COMMAND, ID, "value")
                    .block(TIMEOUT));
            assertEquals("new-value", engine.published().invoke(
                second.viewRevision(), COMMAND, ID, "value").block(TIMEOUT));
            assertEquals(second, engine.published().current());
        }
    }

    private static PluginDefinition<String> definition() {
        return PluginDefinition.builder("command", String.class,
            () -> (context, prefix) -> {
                var provider = context.plugins().current().orElseThrow();
                return context.services().require(ContributionServices.REGISTRAR)
                    .register(context, COMMAND, provider.id(), ID.localName(),
                        new CommandDescriptor("Run"),
                        (invocation, input) -> Mono.just(prefix + input))
                    .then();
            }).require(ContributionServices.REGISTRAR).build();
    }

    private static DesiredGraph graph(String prefix) {
        return new DesiredGraph(List.of(DesiredEntry.builder("command", "command")
            .config(prefix).build()));
    }

    private record CommandDescriptor(String title) {
    }
}
