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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishedRuntimeLeaseTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ContributionKind<Descriptor, String, String> COMMAND =
        ContributionKind.local("command", Descriptor.class, String.class, String.class);
    private static final ContributionId ID = new ContributionId("command", "run");

    @Test
    void failedInvocationCleanupBlocksProviderReleaseWithoutHangingTheChange() throws Exception {
        var cleanup = Sinks.<Void>one();
        var providerReleases = new java.util.concurrent.atomic.AtomicInteger();
        var definition = PluginDefinition.builder("command", String.class, () -> (context, config) -> {
            context.effects().add(() -> {
                providerReleases.incrementAndGet();
                return Mono.empty();
            });
            return context.services().require(ContributionServices.REGISTRAR)
                .register(context, COMMAND, "command", "run", new Descriptor("Run"), (invocation, input) -> {
                    invocation.scope().openChild("child").context().effects().add(cleanup::asMono);
                    return Mono.just(input);
                }).then();
        }).require(ContributionServices.REGISTRAR).build();
        var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph("old-")))
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> (String) value))).build();
        try {
            var started = engine.start().block(TIMEOUT);
            var invocation = engine.published().invoke(started.viewRevision(), identity(started),
                COMMAND, ID, "value").toFuture();
            assertEquals(1, cleanup.currentSubscriberCount());
            var reconciling = engine.published().views()
                .filter(view -> view.engineDiagnostics().phase() == ChangePhase.RECONCILING).next().toFuture();
            var changing = engine.submit(new ReplaceDesiredGraph(null,
                started.engine().desiredSource().revision(), graph("new-"))).toFuture();
            reconciling.get(5, TimeUnit.SECONDS);
            assertFalse(changing.isDone());

            cleanup.tryEmitError(new IllegalStateException("invocation child could not release its resource"));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> invocation.get(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> changing.get(5, TimeUnit.SECONDS));
            assertEquals(0, providerReleases.get());
            assertFalse(engine.published().current().engineDiagnostics().mutationGateOpen());
            assertTrue(engine.published().current().diagnostics().cleanupFailures().stream()
                .anyMatch(failure -> failure.failure().contains("invocation child")));
            assertThrows(RuntimeException.class, () -> engine.closeAsync().block(TIMEOUT));
            assertEquals(0, providerReleases.get());
        } finally {
            cleanup.tryEmitEmpty();
            try { engine.closeAsync().block(TIMEOUT); } catch (RuntimeException expectedRetainedResource) { }
        }
    }

    @Test
    void resultCallbackCanWaitForEngineShutdown() throws Exception {
        assertCallbackCanWaitForShutdown(false);
    }

    @Test
    void failureCallbackCanWaitForEngineShutdown() throws Exception {
        assertCallbackCanWaitForShutdown(true);
    }

    private void assertCallbackCanWaitForShutdown(boolean fail) throws Exception {
        var response = Sinks.<String>one();
        var failure = new IllegalStateException("invocation failed");
        var repository = new InMemoryDesiredStateRepository(graph("old-"));
        try (var closer = Executors.newSingleThreadExecutor();
             var engine = engine(repository, response, null)) {
            var first = engine.start().block(TIMEOUT);
            var shutdown = new CompletableFuture<Void>();
            var invocation = engine.published().invoke(
                first.viewRevision(), identity(first), COMMAND, ID, "value")
                .materialize().doOnNext(signal -> {
                    closer.submit(() -> {
                        try {
                            engine.close();
                            shutdown.complete(null);
                        } catch (Throwable closeFailure) {
                            shutdown.completeExceptionally(closeFailure);
                        }
                    });
                    shutdown.orTimeout(500, TimeUnit.MILLISECONDS).join();
                }).toFuture();

            if (fail) {
                response.tryEmitError(failure);
            } else {
                response.tryEmitValue("release");
            }

            var result = invocation.get(5, TimeUnit.SECONDS);
            if (fail) {
                assertSame(failure, result.getThrowable());
            } else {
                assertEquals("old-value", result.get());
            }
            assertEquals(EngineState.CLOSED, engine.published().current().engine().state());
        }
    }

    @Test
    void cancelledInvocationKeepsItsLeaseUntilCleanupAndRejectsNewCallsDuringShutdown()
        throws Exception {
        var cleanup = Sinks.<Void>one();
        var repository = new InMemoryDesiredStateRepository(graph("old-"));
        try (var engine = engine(repository, null, cleanup)) {
            var first = engine.start().block(TIMEOUT);
            var invocation = engine.published().invoke(
                first.viewRevision(), identity(first), COMMAND, ID, "value").subscribe();
            assertEquals(1, cleanup.currentSubscriberCount());
            invocation.dispose();
            var entered = new java.util.concurrent.CountDownLatch(1);
            var closing = CompletableFuture.runAsync(() -> {
                entered.countDown();
                engine.close();
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> closing.get(100, TimeUnit.MILLISECONDS));
                var rejected = assertThrows(IllegalStateException.class, () ->
                    engine.published().invoke(first.viewRevision(), identity(first), COMMAND, ID, "late")
                        .block(TIMEOUT));
                assertEquals("engine is closing", rejected.getMessage());
            } finally {
                cleanup.tryEmitEmpty();
                closing.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void publishesTargetControlBeforeWaitingForAnAffectedInvocationToDrain() throws Exception {
        var response = Sinks.<String>one();
        var repository = new InMemoryDesiredStateRepository(
            graph("old-"));
        try (var engine = engine(repository, response, null)) {
            var first = engine.start().block(TIMEOUT);
            var nextTarget = targetRevision(graph("new-"));
            var invocation = engine.published().invoke(
                first.viewRevision(), identity(first), COMMAND, ID, "value").toFuture();
            var nextView = engine.published().views()
                .filter(view -> view.engineDiagnostics().phase() == ChangePhase.RECONCILING
                    && nextTarget.equals(view.engineDiagnostics().targetRevision()))
                .next().toFuture();
            var replacement = engine.submit(new ReplaceDesiredGraph(
                first.viewRevision(), first.engine().desiredSource().revision(),
                graph("new-"))).toFuture();

            var reconciling = nextView.get(5, TimeUnit.SECONDS);
            assertFalse(replacement.isDone());
            assertEquals(nextTarget, reconciling.engineDiagnostics().targetRevision());
            assertConsistentPluginFacts(reconciling);
            assertThrows(PublishedRevisionConflictException.class, () ->
                engine.published().invoke(first.viewRevision(), identity(first), COMMAND, ID, "value")
                    .block(TIMEOUT));

            response.tryEmitValue("release");
            assertEquals("old-value", invocation.get(5, TimeUnit.SECONDS));
            var retired = replacement.get(5, TimeUnit.SECONDS).view();
            assertEquals(graph("new-"), retired.engine().desiredGraph());
            assertEquals(nextTarget, retired.engineDiagnostics().targetRevision());
            assertEquals(ChangePhase.IDLE, retired.engineDiagnostics().phase());
            assertEquals("new-value", engine.published().invoke(
                retired.viewRevision(), identity(retired), COMMAND, ID, "value").block(TIMEOUT));
        }
    }

    @Test
    void invocationCompletionAndAffectedScopeRetirementAwaitChildScopeCleanup()
        throws Exception {
        var cleanup = Sinks.<Void>one();
        var repository = new InMemoryDesiredStateRepository(
            graph("old-"));
        try (var engine = engine(repository, null, cleanup)) {
            var first = engine.start().block(TIMEOUT);
            var nextTarget = targetRevision(graph("new-"));
            var invocation = engine.published().invoke(
                first.viewRevision(), identity(first), COMMAND, ID, "value").toFuture();

            assertEquals(1, cleanup.currentSubscriberCount());
            assertFalse(invocation.isDone());

            var nextView = engine.published().views()
                .filter(view -> view.engineDiagnostics().phase() == ChangePhase.RECONCILING
                    && nextTarget.equals(view.engineDiagnostics().targetRevision()))
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

    @Test
    void updatingAnUnrelatedEntryDoesNotWaitForAnUnchangedContributionCall() throws Exception {
        var response = Sinks.<String>one();
        var repository = new InMemoryDesiredStateRepository(graphWithOther("old-", "other-old-"));
        try (var engine = engine(repository, response, null)) {
            var first = engine.start().block(TIMEOUT);
            var commandIdentity = first.engine().instances().get("command").identity();
            var invocation = engine.published().invoke(
                first.viewRevision(), identity(first), COMMAND, ID, "value").toFuture();

            var updated = engine.submit(new ReplaceDesiredGraph(first.viewRevision(),
                first.engine().desiredSource().revision(),
                graphWithOther("old-", "other-new-"))).toFuture().get(5, TimeUnit.SECONDS).view();

            assertFalse(invocation.isDone());
            assertEquals(commandIdentity, updated.engine().instances().get("command").identity());
            assertEquals(LiteralValue.of("other-new-"),
                updated.engine().instances().get("other").config());
            assertEquals(ChangePhase.IDLE, updated.engineDiagnostics().phase());

            response.tryEmitValue("release");
            assertEquals("old-value", invocation.get(5, TimeUnit.SECONDS));
        }
    }

    private static FibraEngine engine(InMemoryDesiredStateRepository repository,
                                      Sinks.One<String> response, Sinks.One<Void> cleanup) {
        return FibraEngine.builder(repository)
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(
                definition(response, cleanup), value -> (String) value)))
            .build();
    }

    private static long identity(PublishedView view) {
        return view.contributions().entries().stream()
            .filter(entry -> entry.kind().equals(COMMAND.name()) && entry.id().equals(ID))
            .findFirst().orElseThrow().registrationIdentity();
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

    private static DesiredInputGraph graphWithOther(String commandPrefix, String otherPrefix) {
        return new DesiredInputGraph(List.of(
            DesiredInputEntry.builder("command", "command")
                .config(LiteralValue.of(commandPrefix)).build(),
            DesiredInputEntry.builder("other", "command")
                .config(LiteralValue.of(otherPrefix)).build()));
    }

    private static String targetRevision(DesiredInputGraph graph) {
        return new DeploymentManifest(Map.of(), graph).revision();
    }

    private static void assertConsistentPluginFacts(PublishedView view) {
        view.engine().instances().values().forEach(instance -> {
            var fact = view.diagnostics().plugins().stream()
                .filter(plugin -> plugin.identity() == instance.identity()).findFirst().orElseThrow();
            assertEquals(instance.state(), fact.state());
        });
    }

    private record Descriptor(String title) {
    }
}
