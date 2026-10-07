package com.sstlfsj.fibra.verification.host;

import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.bridge.ContributionKindRegistry;
import com.sstlfsj.fibra.bridge.ContributionSnapshotEntry;
import com.sstlfsj.fibra.bridge.ContributionUnavailableException;
import com.sstlfsj.fibra.client.protocol.ClientEnvelope;
import com.sstlfsj.fibra.client.protocol.ClientMessage;
import com.sstlfsj.fibra.client.protocol.ClientProtocolCodec;
import com.sstlfsj.fibra.client.protocol.SessionFence;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.engine.*;
import com.sstlfsj.fibra.value.LiteralValue;
import com.sstlfsj.fibra.verification.external.ExternalFixtureRuntimeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ClientProtocolVerticalVerificationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String PLUGIN = "protocol-fixture";
    @TempDir Path work;

    @Test
    void currentAssignmentsCrossTheJavaCodecWithIndependentConfigAndExactLifecycleFences() throws Exception {
        var store = new PluginPackageStore(work.resolve("packages"));
        final PluginSelection selection;
        try (var transaction = store.prepareInstall(packageSource())) {
            var installed = transaction.save();
            selection = new PluginSelection(installed.pluginId(), installed.packageRevision(), true);
        }
        var provider = new ClientProtocolFixtureProvider();
        var kinds = ContributionKindRegistry.of(ExternalFixtureRuntimeProvider.REMOTE_KIND);
        var output = reactorRoot().resolve("fibra-parity-tests/target/client-protocol-conformance");
        Files.createDirectories(output);
        try (var engine = FibraEngine.builder(store, new FileDeploymentTargetStore(work.resolve("target")))
            .runtimeProvider(provider).contributionKinds(kinds).hostTerminationPort(ignored -> fail("unexpected fatal state"))
            .lifecycleTimeout(TIMEOUT).build()) {
            provider.goOnline();
            engine.startAsync().block(TIMEOUT);
            var initial = apply(engine, selection, 0, "one");
            assertTrue(initial.engine().current().orElseThrow().observations().values().stream()
                .allMatch(observation -> observation.aggregateState() == ExecutionObservation.State.ACTIVE));
            var initialAssignments = write(output, "initial", provider, initial, "session-one");
            assertEquals(List.of("entry-a", "entry-b"), initialAssignments.stream()
                .map(ClientMessage.Assignment::desiredEntryId).toList());
            assertEquals(List.of(config("one"), config("two")), initialAssignments.stream()
                .map(ClientMessage.Assignment::config).toList());
            assertCallable(engine, kinds, "entry-a", "one");
            assertCallable(engine, kinds, "entry-b", "two");
            var oldFence = fence(initial, "entry-a");
            var retainedFence = fence(initial, "entry-b");
            var oldRoute = route(initial, "entry-a");
            var prepare = provider.holdNextPrepare();
            var drain = provider.holdNextDrain();
            try {
                var replacing = engine.submit(command(selection, 1, "updated")).toFuture();
                prepare.awaitEntered(TIMEOUT);
                var candidate = engine.published().current();
                assertTrue(candidate.engine().candidate().isPresent());
                assertEquals(CandidatePhase.PREPARING, candidate.engine().candidate().orElseThrow().phase());
                assertEquals(initialAssignments, write(output, "candidate", provider, candidate, "session-one"));
                assertEquals(oldFence, fence(candidate, "entry-a"));

                prepare.release();
                drain.awaitEntered(TIMEOUT);
                var retiring = engine.published().current();
                assertTrue(retiring.engine().candidate().isEmpty());
                assertTrue(retiring.engine().retirementBatch().isPresent());
                assertEquals(0, provider.stops(), "old execution must still be owned while drain is held");
                assertNotEquals(oldFence, fence(retiring, "entry-a"));
                assertEquals(retainedFence, fence(retiring, "entry-b"));
                var duringRetirement = write(output, "retiring", provider, retiring, "session-one");
                assertTrue(duringRetirement.stream().noneMatch(assignment ->
                    assignment.runtimeInstanceId().equals(oldFence.runtimeInstanceId())));
                assertRejected(engine, kinds, retiring, oldRoute);

                drain.release();
                var replaced = replacing.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).view();
                assertTrue(replaced.engine().retirementBatch().isEmpty());
                assertEquals(1, provider.stops());
                assertEquals(retainedFence, fence(replaced, "entry-b"));
                assertEquals(duringRetirement, write(output, "replaced", provider, replaced, "session-one"));
                assertEquals(duringRetirement, write(output, "reconnected", provider, replaced, "session-two"));
                assertCallable(engine, kinds, "entry-a", "updated");
                assertCallable(engine, kinds, "entry-b", "two");
                assertRejected(engine, kinds, replaced, oldRoute);

                var beforeReplay = engine.published().current();
                provider.replay(oldFence);
                engine.submit(new ReconcileCurrent()).block(TIMEOUT);
                assertEquals(beforeReplay.viewRevision(), engine.published().current().viewRevision(),
                    "retired fence callbacks must not publish or replace the current units");
                assertEquals(retainedFence, fence(engine.published().current(), "entry-b"));
            } finally {
                prepare.release();
                drain.release();
            }
            var removed = engine.submit(ApplyDeployment.builder(new DesiredInputGraph(List.of()))
                .expectedRevision(2).selections(List.of()).configContext(ConfigContextSnapshot.empty()).build())
                .block(TIMEOUT).view();
            assertTrue(write(output, "removed", provider, removed, "session-two").isEmpty());
            assertTrue(removed.contributions().entries().isEmpty());
            assertEquals(3, provider.stops());
        }
    }

    private static PublishedView apply(FibraEngine engine, PluginSelection selection, long revision, String mode) {
        return engine.submit(command(selection, revision, mode)).block(TIMEOUT).view();
    }

    private static ApplyDeployment command(PluginSelection selection, long revision, String mode) {
        var graph = new DesiredInputGraph(List.of(entry("entry-a"), entry("entry-b")));
        var context = ConfigContextSnapshot.of((LiteralValue.ObjectValue) LiteralValue.of(
            Map.of("entry-a", Map.of("mode", mode), "entry-b", Map.of("mode", "two"))));
        return ApplyDeployment.builder(graph).expectedRevision(revision)
            .selections(List.of(selection)).configContext(context).build();
    }

    private static DesiredInputEntry entry(String id) {
        return DesiredInputEntry.builder(id, new PluginDefinitionRef(PLUGIN, "main",
                ExternalFixtureRuntimeProvider.DEFINITION_ID))
            .config(LiteralValue.of(Map.of("$ref", '/' + id))).build();
    }

    private static LiteralValue config(String mode) { return LiteralValue.of(Map.of("mode", mode)); }

    private static RuntimeUnitFence fence(PublishedView view, String id) {
        var observation = view.engine().current().orElseThrow().observations().get(new ExecutionUnitKey(id));
        var detail = observation.executions().getFirst();
        return new RuntimeUnitFence(observation.runtimeId(), new ExecutionUnitKey(id),
            detail.unitTargetRevision(), detail.runtimeInstanceId());
    }

    private static ContributionSnapshotEntry route(PublishedView view, String id) {
        return view.contributions().entries().stream()
            .filter(entry -> entry.id().providerInstanceId().equals(id)).findFirst().orElseThrow();
    }

    private static void assertRejected(FibraEngine engine, ContributionKindRegistry kinds,
                                       PublishedView view, ContributionSnapshotEntry stale) {
        assertThrows(ContributionUnavailableException.class, () -> new RemoteContributionInvoker(kinds, engine.published())
            .invoke(stale.kind(), stale.id(), view.viewRevision(), stale.registrationIdentity(), LiteralValue.of("stale"))
            .block(TIMEOUT));
    }

    private static void assertCallable(FibraEngine engine, ContributionKindRegistry kinds,
                                       String entry, String mode) {
        var view = engine.published().current();
        var route = route(view, entry);
        assertEquals(LiteralValue.of(config(mode).canonicalJson() + ":request"),
            new RemoteContributionInvoker(kinds, engine.published()).invoke(route.kind(), route.id(),
                view.viewRevision(), route.registrationIdentity(), LiteralValue.of("request")).block(TIMEOUT));
    }

    private static List<ClientMessage.Assignment> write(Path output, String name,
            ClientProtocolFixtureProvider provider, PublishedView view, String executionId) throws Exception {
        var target = view.engine().target().orElseThrow();
        var assignments = provider.currentAssignments(view);
        var contributions = view.contributions().entries().stream().map(entry ->
            new ClientMessage.Contribution(entry.kind(), new ClientMessage.ContributionId(
                entry.id().providerInstanceId(), entry.id().localName()), entry.registrationIdentity())).toList();
        var snapshot = new ClientMessage.Snapshot(new SessionFence(view.engine().hostInstanceId(), executionId),
            view.viewRevision(), target.targetRevision(), target.targetDigest(), assignments, contributions);
        var codec = new ClientProtocolCodec();
        var envelope = new ClientEnvelope(ClientProtocolCodec.VERSION, name, snapshot.type(), snapshot);
        var wire = codec.encode(envelope);
        assertEquals(envelope, codec.decode(wire));
        Files.writeString(output.resolve(name + ".json"), wire);
        return assignments;
    }

    private Path packageSource() throws Exception {
        var root = Files.createDirectories(work.resolve("source"));
        var payload = Files.createDirectories(root.resolve("client"));
        Files.writeString(payload.resolve("index.mjs"), "export const definitions = [];\n");
        Files.writeString(root.resolve("fibra-package.yaml"), """
            format: 1
            id: protocol-fixture
            version: 1.0.0
            facets:
              - id: main
                role: host
                runtime: external-fixture
                target: client:web
                payload: client
                dependencies: []
                capabilities: []
            """);
        return root;
    }

    private static Path reactorRoot() {
        var path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("client/packages"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("cannot locate Fibra reactor root");
        return path;
    }
}
