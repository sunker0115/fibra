package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactState;
import com.sstlfsj.fibra.artifact.ExecutionTarget;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.FacetRole;
import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.ManagedPluginPackage;
import com.sstlfsj.fibra.artifact.PluginFacet;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.PluginPackageInstallTransaction;
import com.sstlfsj.fibra.artifact.PluginPackageRecord;
import com.sstlfsj.fibra.artifact.RuntimeId;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtifactResourcesTest {
    @Test
    void publishesOnlyAfterEveryFacetWasInspected() {
        var events = new ArrayList<String>();
        var java = new InspectRuntime("java", events);
        var node = new InspectRuntime("node", events);
        var transaction = transaction(managedPackage(), events);
        var resources = new ArtifactResources(Map.of(java.id(), java, node.id(), node));

        var installed = resources.inspectAndSave(transaction).block();

        assertSame(transaction.installed, installed);
        assertEquals(List.of(
            "probe:java:a-host", "inspect:java:a-host",
            "probe:node:z-command", "inspect:node:z-command", "save"), events);
        assertEquals(1, transaction.saveCalls.get());
        assertEquals(0, transaction.rollbackCalls.get());
    }

    @Test
    void inspectionFailureRollsBackWithoutPublishingThePackage() {
        var events = new ArrayList<String>();
        var java = new InspectRuntime("java", events);
        var node = new InspectRuntime("node", events);
        node.inspectFailure = new IllegalStateException("invalid node descriptor");
        var transaction = transaction(managedPackage(), events);
        var resources = new ArtifactResources(Map.of(java.id(), java, node.id(), node));

        var failure = assertThrows(IllegalStateException.class,
            () -> resources.inspectAndSave(transaction).block());

        assertEquals("invalid node descriptor", failure.getMessage());
        assertEquals(List.of(
            "probe:java:a-host", "inspect:java:a-host",
            "probe:node:z-command", "inspect:node:z-command", "rollback"), events);
        assertEquals(0, transaction.saveCalls.get());
        assertEquals(1, transaction.rollbackCalls.get());
    }

    @Test
    void rejectsAnInspectionThatReplacesTheManagedIdentity() {
        var events = new ArrayList<String>();
        var java = new InspectRuntime("java", events);
        java.replaceArtifactIdentity = true;
        var transaction = transaction(singleFacetPackage(), events);
        var resources = new ArtifactResources(Map.of(java.id(), java));

        assertThrows(IllegalArgumentException.class,
            () -> resources.inspectAndSave(transaction).block());

        assertEquals(0, transaction.saveCalls.get());
        assertEquals(1, transaction.rollbackCalls.get());
    }

    @Test
    void cancellationRollsBackWithoutPublishingThePackage() {
        var events = new ArrayList<String>();
        var java = new InspectRuntime("java", events);
        java.probeResult = Mono.never();
        var transaction = transaction(singleFacetPackage(), events);
        var resources = new ArtifactResources(Map.of(java.id(), java));

        var inspection = resources.inspectAndSave(transaction).subscribe();
        inspection.dispose();

        assertEquals(List.of("probe:java:host", "rollback"), events);
        assertEquals(0, transaction.saveCalls.get());
        assertEquals(1, transaction.rollbackCalls.get());
    }

    private static RecordingTransaction transaction(ManagedPluginPackage managedPackage,
                                                    List<String> events) {
        var candidate = record(managedPackage, ArtifactState.STAGED);
        var installed = record(managedPackage, ArtifactState.INSTALLED);
        return new RecordingTransaction(candidate, installed, events);
    }

    private static PluginPackageRecord record(ManagedPluginPackage managedPackage,
                                              ArtifactState state) {
        return PluginPackageRecord.builder()
            .pluginId(managedPackage.pluginId())
            .version(managedPackage.version())
            .packageRevision(managedPackage.packageRevision())
            .location(Path.of("managed", managedPackage.packageRevision()))
            .managedPackage(managedPackage)
            .state(state)
            .updatedAt(Instant.EPOCH)
            .build();
    }

    private static ManagedPluginPackage managedPackage() {
        var revision = "a".repeat(64);
        var pluginId = new PluginId("sample");
        return ManagedPluginPackage.builder()
            .pluginId(pluginId)
            .version("1.0.0")
            .packageRevision(revision)
            .facets(List.of(
                managedFacet(pluginId, revision, "a-host", "java", FacetRole.HOST),
                managedFacet(pluginId, revision, "z-command", "node", FacetRole.COMMAND)))
            .build();
    }

    private static ManagedPluginPackage singleFacetPackage() {
        var revision = "b".repeat(64);
        var pluginId = new PluginId("single");
        return ManagedPluginPackage.builder()
            .pluginId(pluginId)
            .version("1.0.0")
            .packageRevision(revision)
            .facets(List.of(managedFacet(
                pluginId, revision, "host", "java", FacetRole.HOST)))
            .build();
    }

    private static ManagedFacet managedFacet(PluginId pluginId, String revision,
                                             String facetId, String runtimeId,
                                             FacetRole role) {
        var facet = new PluginFacet(new FacetId(facetId), role,
            new RuntimeId(runtimeId), new ExecutionTarget("host"),
            Path.of(facetId), "c".repeat(64), List.of(), List.of());
        return new ManagedFacet(new ArtifactId(revision + ":" + facetId),
            pluginId, revision, facet);
    }

    private static final class RecordingTransaction
        implements PluginPackageInstallTransaction {
        private final PluginPackageRecord candidate;
        private final PluginPackageRecord installed;
        private final AtomicInteger saveCalls = new AtomicInteger();
        private final AtomicInteger rollbackCalls = new AtomicInteger();
        private final List<String> events;

        private RecordingTransaction(PluginPackageRecord candidate,
                                     PluginPackageRecord installed,
                                     List<String> events) {
            this.candidate = candidate;
            this.installed = installed;
            this.events = events;
        }

        @Override public PluginPackageRecord candidate() { return candidate; }

        @Override public PluginPackageRecord save() {
            saveCalls.incrementAndGet();
            events.add("save");
            return installed;
        }

        @Override public void rollback() {
            rollbackCalls.incrementAndGet();
            events.add("rollback");
        }
    }

    private static final class InspectRuntime implements ArtifactRuntime {
        private final RuntimeId id;
        private final List<String> events;
        private RuntimeException inspectFailure;
        private boolean replaceArtifactIdentity;
        private Mono<Void> probeResult = Mono.empty();

        private InspectRuntime(String id, List<String> events) {
            this.id = new RuntimeId(id);
            this.events = events;
        }

        @Override public RuntimeId id() { return id; }

        @Override public Mono<Void> probe(PluginFacet source) {
            return Mono.fromRunnable(() -> events.add(
                "probe:" + id.value() + ":" + source.facetId().value()))
                .then(probeResult);
        }

        @Override public Mono<RuntimeArtifactInspection> inspect(ManagedFacet facet) {
            return Mono.defer(() -> {
                events.add("inspect:" + id.value() + ":"
                    + facet.facet().facetId().value());
                if (inspectFailure != null) return Mono.error(inspectFailure);
                var artifactId = replaceArtifactIdentity
                    ? new ArtifactId("replaced") : facet.artifactId();
                return Mono.just(new RuntimeArtifactInspection(
                    id, artifactId, Map.of()));
            });
        }

        @Override public PreparedArtifactUpdate createUpdate(
            List<DeploymentTargetCompiler.CompiledFacet> target) {
            throw new UnsupportedOperationException();
        }

        @Override public Snapshot snapshot() { return new Snapshot(id, List.of()); }

        @Override public Mono<Void> closeAsync() { return Mono.empty(); }
    }
}
