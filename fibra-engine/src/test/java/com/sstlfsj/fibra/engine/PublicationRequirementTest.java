package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.config.PublicationRequirement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicationRequirementTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ServiceKey<String> MISSING =
        ServiceKey.of("missing", String.class);

    @Test
    void activeRequiredSavesItsTargetAndReportsTheActualPendingInstance(@TempDir Path work) {
        var repository = new InMemoryDesiredStateRepository(graph(false));
        var stateRoot = work.resolve("state");
        try (var engine = engine(repository, new FileEngineStateStore(stateRoot))) {
            var failure = assertThrows(EngineChangeException.class,
                () -> engine.start().block(TIMEOUT));
            var failed = failure.view();

            assertTrue(failure.targetSaved());
            assertEquals(EngineState.FAILED, failed.engine().state());
            assertEquals(PluginInstanceState.PENDING,
                failed.engine().instances().get("dependent").state());
            assertEquals(PublicationRequirement.ACTIVE_REQUIRED,
                failed.engine().instances().get("dependent").publicationRequirement());
            assertFalse(failed.engineDiagnostics().targetSatisfied());
            assertEquals(targetRevision(graph(false)),
                failed.engineDiagnostics().targetRevision());
        }
        try (var store = new FileEngineStateStore(stateRoot)) {
            assertEquals(graph(false), store.load().orElseThrow().desiredGraph());
        }
    }

    @Test
    void pendingAllowedPublishesWaitingForDiagnostics() {
        var repository = new InMemoryDesiredStateRepository(graph(true));
        try (var engine = engine(repository, EngineStateStore.inMemory())) {
            var view = engine.start().block(TIMEOUT);

            assertEquals(EngineState.RUNNING, view.engine().state());
            assertEquals(PluginInstanceState.PENDING,
                view.engine().instances().get("dependent").state());
            assertEquals(List.of(MISSING.name()),
                view.diagnostics().plugins().getFirst().waitingFor().stream()
                    .map(value -> value.name()).toList());
            assertEquals(PublicationRequirement.PENDING_ALLOWED,
                view.engine().instances().get("dependent").publicationRequirement());
            assertTrue(view.engine().instances().get("dependent").requirementSatisfied());
            assertTrue(view.engineDiagnostics().targetSatisfied());
            assertEquals(targetRevision(graph(true)),
                view.engineDiagnostics().targetRevision());
        }
    }

    private static FibraEngine engine(InMemoryDesiredStateRepository repository,
                                      EngineStateStore stateStore) {
        var definition = PluginDefinition.builder("dependent", Void.class,
            () -> (context, config) -> Mono.empty()).require(MISSING).build();
        return FibraEngine.builder(repository)
            .stateStore(stateStore)
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> null)))
            .build();
    }

    private static DesiredInputGraph graph(boolean pendingAllowed) {
        var entry = DesiredInputEntry.builder("dependent", "dependent");
        if (pendingAllowed) {
            entry.publicationRequirement(PublicationRequirement.PENDING_ALLOWED);
        }
        return new DesiredInputGraph(List.of(entry.build()));
    }

    private static String targetRevision(DesiredInputGraph graph) {
        return new DeploymentManifest(Map.of(), graph).revision();
    }
}
