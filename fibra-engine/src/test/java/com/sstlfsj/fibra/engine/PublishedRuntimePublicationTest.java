package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.bridge.ContributionUnavailableException;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishedRuntimePublicationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ContributionKind<CommandDescriptor, String, String> COMMAND =
        ContributionKind.local("command", CommandDescriptor.class,
            String.class, String.class);
    private static final ContributionId ID = new ContributionId("command", "run");

    @Test
    void removingTheLastDeclarationIsNotSatisfiedWhileItsOldScopeStillExists() throws Exception {
        var cleanupStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseCleanup = reactor.core.publisher.Sinks.<Void>one();
        var definition = PluginDefinition.builder("sample", Void.class, () -> (context, config) -> {
            context.effects().add(() -> {
                cleanupStarted.countDown();
                return releaseCleanup.asMono();
            });
            return Mono.empty();
        }).build();
        var initial = new DesiredInputGraph(List.of(DesiredInputEntry.builder("sample", "sample").build()));
        var empty = new DesiredInputGraph(List.of());
        var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(initial))
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition, ignored -> null))).build();
        try {
            var started = engine.start().block(TIMEOUT);
            var savedView = engine.published().views().filter(view ->
                targetRevision(empty).equals(view.engineDiagnostics().targetRevision())
                    && view.engineDiagnostics().phase() == ChangePhase.RECONCILING).next().toFuture();
            var changing = engine.submit(new ReplaceDesiredGraph(null,
                started.engine().desiredSource().revision(), empty)).toFuture();
            assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS));
            var transitional = savedView.get(5, TimeUnit.SECONDS);
            assertFalse(transitional.engineDiagnostics().targetSatisfied());
            assertFalse(changing.isDone());
            releaseCleanup.tryEmitEmpty();
            assertTrue(changing.get(5, TimeUnit.SECONDS).view().engineDiagnostics().targetSatisfied());
        } finally {
            releaseCleanup.tryEmitEmpty();
            engine.closeAsync().block(TIMEOUT);
        }
    }

    @Test
    void publishesStateAndRoutesAsOneViewAndRejectsAStaleRevision() throws Exception {
        var repository = new InMemoryDesiredStateRepository(graph("old-"));
        try (var engine = FibraEngine.builder(repository)
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition(),
                value -> (String) value))).build()) {
            var first = engine.start().block(TIMEOUT);
            var firstTarget = targetRevision(graph("old-"));
            var nextTarget = targetRevision(graph("new-"));

            assertEquals(EngineState.RUNNING, first.engine().state());
            assertEquals(1, first.contributions().entries().size());
            assertEquals(firstTarget, first.engineDiagnostics().targetRevision());
            assertEquals(ChangePhase.IDLE, first.engineDiagnostics().phase());
            assertEquals("old-value", engine.published().invoke(
                first.viewRevision(), identity(first), COMMAND, ID, "value").block(TIMEOUT));

            var reconcilingSignal = engine.published().views()
                .filter(view -> view.engineDiagnostics().phase() == ChangePhase.RECONCILING
                    && nextTarget.equals(view.engineDiagnostics().targetRevision()))
                .next().toFuture();
            var second = engine.submit(new ReplaceDesiredGraph(
                first.viewRevision(), first.engine().desiredSource().revision(),
                graph("new-"))).block(TIMEOUT).view();
            var reconciling = reconcilingSignal.get(5, TimeUnit.SECONDS);

            assertEquals(nextTarget, reconciling.engineDiagnostics().targetRevision());
            assertConsistentPluginFacts(reconciling);
            assertNotEquals(first.viewRevision(), second.viewRevision());
            assertEquals(nextTarget, second.engineDiagnostics().targetRevision());
            assertEquals(ChangePhase.IDLE, second.engineDiagnostics().phase());
            assertEquals(graph("new-"), second.engine().desiredGraph());
            assertEquals(second.engine().instances().get("command").identity(),
                second.diagnostics().plugins().getFirst().identity());
            assertThrows(PublishedRevisionConflictException.class, () ->
                engine.published().invoke(first.viewRevision(), identity(first), COMMAND, ID, "value")
                    .block(TIMEOUT));
            assertEquals("new-value", engine.published().invoke(
                second.viewRevision(), identity(second), COMMAND, ID, "value").block(TIMEOUT));
            assertEquals(second, engine.published().current());
        }
    }

    @Test
    void replacedContributionRejectsItsOldIdentityEvenWithTheCurrentRevision() {
        var oldCalls = new AtomicInteger();
        var newCalls = new AtomicInteger();
        var definition = PluginDefinition.builder("command", String.class, () -> (context, prefix) ->
            context.services().require(ContributionServices.REGISTRAR)
                .register(context, COMMAND, "command", ID.localName(), new CommandDescriptor("Run"),
                    (invocation, input) -> {
                        ("old-".equals(prefix) ? oldCalls : newCalls).incrementAndGet();
                        return Mono.just(prefix + input);
                    }).then()).require(ContributionServices.REGISTRAR).build();
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph("old-")))
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> (String) value)))
            .build()) {
            var first = engine.start().block(TIMEOUT);
            var second = engine.submit(new ReplaceDesiredGraph(first.viewRevision(),
                first.engine().desiredSource().revision(), graph("new-"))).block(TIMEOUT).view();

            assertNotEquals(identity(first), identity(second));
            assertThrows(PublishedRevisionConflictException.class, () -> engine.published()
                .invoke(first.viewRevision(), identity(first), COMMAND, ID, "value").block(TIMEOUT));
            assertThrows(ContributionUnavailableException.class, () -> engine.published()
                .invoke(second.viewRevision(), identity(first), COMMAND, ID, "value").block(TIMEOUT));
            assertEquals(0, oldCalls.get());
            assertEquals(0, newCalls.get());

            assertEquals("new-value", engine.published().invoke(second.viewRevision(), identity(second),
                COMMAND, ID, "value").block(TIMEOUT));
            assertEquals(0, oldCalls.get());
            assertEquals(1, newCalls.get());
        }
    }

    @Test
    void publishedAdmissionRejectsTheWrongRegistrationIdentity() {
        var repository = new InMemoryDesiredStateRepository(graph("value-"));
        try (var engine = FibraEngine.builder(repository)
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition(),
                value -> (String) value))).build()) {
            var view = engine.start().block(TIMEOUT);
            var entry = view.contributions().entries().getFirst();

            assertThrows(com.sstlfsj.fibra.bridge.ContributionUnavailableException.class, () ->
                engine.published().invoke(view.viewRevision(), entry.registrationIdentity() + 1,
                    COMMAND, ID, "value").block(TIMEOUT));
            assertEquals("value-value", engine.published().invoke(view.viewRevision(),
                entry.registrationIdentity(), COMMAND, ID, "value").block(TIMEOUT));
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

    private static DesiredInputGraph graph(String prefix) {
        return new DesiredInputGraph(List.of(DesiredInputEntry.builder("command", "command")
            .config(LiteralValue.of(prefix)).build()));
    }

    private static String targetRevision(DesiredInputGraph graph) {
        return new DeploymentManifest(Map.of(), graph).revision();
    }

    private static long identity(PublishedView view) {
        return view.contributions().entries().stream()
            .filter(entry -> entry.kind().equals(COMMAND.name()) && entry.id().equals(ID))
            .findFirst().orElseThrow().registrationIdentity();
    }

    private static void assertConsistentPluginFacts(PublishedView view) {
        view.engine().instances().values().forEach(instance -> {
            var fact = view.diagnostics().plugins().stream()
                .filter(plugin -> plugin.identity() == instance.identity()).findFirst().orElseThrow();
            assertEquals(instance.state(), fact.state());
        });
    }

    private record CommandDescriptor(String title) {
    }
}
