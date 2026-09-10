package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.config.DesiredEntry;
import com.sstlfsj.fibra.config.DesiredGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.config.PublicationRequirement;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicationRequirementTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ServiceKey<String> MISSING =
        ServiceKey.of("missing", String.class);

    @Test
    void activeRequiredRejectsPendingCandidateWithoutChangingPublishedView() {
        var repository = new InMemoryDesiredStateRepository(graph(false));
        try (var engine = engine(repository)) {
            var before = engine.published().current();

            assertThrows(ChangeSetException.class, () -> engine.start().block(TIMEOUT));

            var after = engine.published().current();
            assertEquals(before.generationRevision(), after.generationRevision());
            assertEquals(before.engine(), after.engine());
            assertEquals(before.contributions(), after.contributions());
            assertEquals(before.diagnostics(), after.diagnostics());
            assertEquals(null,
                after.engineDiagnostics().candidateGenerationRevision());
            assertEquals(EngineState.NEW, after.engine().state());
        }
    }

    @Test
    void pendingAllowedPublishesWaitingForDiagnostics() {
        var repository = new InMemoryDesiredStateRepository(graph(true));
        try (var engine = engine(repository)) {
            var view = engine.start().block(TIMEOUT);

            assertEquals(EngineState.RUNNING, view.engine().state());
            assertEquals(PluginInstanceState.PENDING,
                view.engine().instances().get("dependent").state());
            assertEquals(List.of(MISSING.name()),
                view.diagnostics().plugins().getFirst().waitingFor().stream()
                    .map(value -> value.name()).toList());
            assertEquals(PublicationRequirement.PENDING_ALLOWED,
                view.diagnostics().plugins().getFirst().publicationRequirement());
            assertEquals(RuntimeDiagnostics.PublicationImpact.NONE,
                view.diagnostics().plugins().getFirst().publicationImpact());
        }
    }

    private static FibraEngine engine(InMemoryDesiredStateRepository repository) {
        var definition = PluginDefinition.builder("dependent", Void.class,
            () -> (context, config) -> Mono.empty()).require(MISSING).build();
        return FibraEngine.builder(repository)
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> null)))
            .build();
    }

    private static DesiredGraph graph(boolean pendingAllowed) {
        var entry = DesiredEntry.builder("dependent", "dependent");
        if (pendingAllowed) {
            entry.publicationRequirement(PublicationRequirement.PENDING_ALLOWED);
        }
        return new DesiredGraph(List.of(entry.build()));
    }
}
