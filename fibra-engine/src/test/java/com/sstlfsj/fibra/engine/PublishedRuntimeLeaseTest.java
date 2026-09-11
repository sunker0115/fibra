package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublishedRuntimeLeaseTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ContributionKind<Descriptor, String, String> COMMAND =
        ContributionKind.local("command", Descriptor.class, String.class, String.class);
    private static final ContributionId ID = new ContributionId("command", "run");

    @Test
    void publishesReplacementBeforeWaitingForOldInvocationToDrain() throws Exception {
        var response = Sinks.<String>one();
        var repository = new InMemoryDesiredStateRepository(
            graph("old-"));
        try (var engine = engine(repository, response, null)) {
            var first = engine.start().block(TIMEOUT);
            var invocation = engine.published().invoke(
                first.viewRevision(), COMMAND, ID, "value").toFuture();
            var nextView = engine.published().views()
                .filter(view -> !view.generationRevision().equals(
                    first.generationRevision()))
                .next().toFuture();
            var replacement = engine.submit(new ReplaceDesiredGraph(
                first.viewRevision(), first.engine().desiredSource().revision(),
                graph("new-"))).toFuture();

            var second = nextView.get(5, TimeUnit.SECONDS);
            assertFalse(replacement.isDone());
            assertThrows(PublishedRevisionConflictException.class, () ->
                engine.published().invoke(first.viewRevision(), COMMAND, ID, "value")
                    .block(TIMEOUT));

            response.tryEmitValue("release");
            assertEquals("old-value", invocation.get(5, TimeUnit.SECONDS));
            var retired = replacement.get(5, TimeUnit.SECONDS).view();
            assertEquals(second.generationRevision(), retired.generationRevision());
            assertEquals(second.engine().desiredGraph(), retired.engine().desiredGraph());
            assertEquals("new-value", engine.published().invoke(
                retired.viewRevision(), COMMAND, ID, "value").block(TIMEOUT));
        }
    }

    @Test
    void invocationCompletionAndGenerationRetirementAwaitChildScopeCleanup()
        throws Exception {
        var cleanup = Sinks.<Void>one();
        var repository = new InMemoryDesiredStateRepository(
            graph("old-"));
        try (var engine = engine(repository, null, cleanup)) {
            var first = engine.start().block(TIMEOUT);
            var invocation = engine.published().invoke(
                first.viewRevision(), COMMAND, ID, "value").toFuture();

            assertEquals(1, cleanup.currentSubscriberCount());
            assertFalse(invocation.isDone());

            var nextView = engine.published().views()
                .filter(view -> !view.generationRevision().equals(
                    first.generationRevision()))
                .next().toFuture();
            var replacement = engine.submit(new ReplaceDesiredGraph(
                first.viewRevision(), first.engine().desiredSource().revision(),
                graph("new-"))).toFuture();
            nextView.get(5, TimeUnit.SECONDS);
            assertFalse(replacement.isDone());

            cleanup.tryEmitEmpty();
            assertEquals("old-value", invocation.get(5, TimeUnit.SECONDS));
            replacement.get(5, TimeUnit.SECONDS);
        }
    }

    private static FibraEngine engine(InMemoryDesiredStateRepository repository,
                                      Sinks.One<String> response, Sinks.One<Void> cleanup) {
        return FibraEngine.builder(repository)
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(
                definition(response, cleanup), value -> (String) value)))
            .build();
    }

    private static PluginDefinition<String> definition(Sinks.One<String> response,
                                                        Sinks.One<Void> cleanup) {
        return PluginDefinition.builder("command", String.class,
            () -> (context, prefix) -> {
                var provider = context.plugins().current().orElseThrow();
                return context.services().require(ContributionServices.REGISTRAR)
                    .register(context, COMMAND, provider.id(), ID.localName(),
                        new Descriptor("Run"), (invocation, input) -> {
                            if ("old-".equals(prefix) && cleanup != null) {
                                invocation.effects().add(cleanup::asMono);
                            }
                            var result = prefix + input;
                            return !"old-".equals(prefix) || response == null ? Mono.just(result)
                                : response.asMono().thenReturn(result);
                        }).then();
            }).require(ContributionServices.REGISTRAR).build();
    }

    private static DesiredInputGraph graph(String prefix) {
        return new DesiredInputGraph(List.of(DesiredInputEntry.builder("command", "command")
            .config(LiteralValue.of(prefix)).build()));
    }

    private record Descriptor(String title) {
    }
}
