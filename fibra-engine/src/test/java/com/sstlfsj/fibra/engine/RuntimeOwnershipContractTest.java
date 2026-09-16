package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ExecutionTarget;
import com.sstlfsj.fibra.artifact.FacetDependency;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.FacetRole;
import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.ManagedPluginPackage;
import com.sstlfsj.fibra.artifact.PluginFacet;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.RuntimeId;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeOwnershipContractTest {
    @Test
    void updateCreationAndIoPhasesAreSeparateInTheContract() throws Exception {
        assertEquals(PreparedArtifactUpdate.class,
            ArtifactRuntime.class.getMethod("createUpdate", List.class)
                .getReturnType());
        assertEquals(reactor.core.publisher.Mono.class,
            PreparedArtifactUpdate.class.getMethod("prepareAsync").getReturnType());
        assertEquals(ExecutionTargetPlan.class,
            ExecutionRuntime.class.getMethod("compile", DeploymentTarget.class, Map.class)
                .getReturnType());
        assertEquals(ExecutionUpdate.class,
            ExecutionRuntime.class.getMethod("createUpdate", ExecutionTargetPlan.class)
                .getReturnType());
        assertEquals(reactor.core.publisher.Mono.class,
            ExecutionUpdate.class.getMethod("reconcileAsync").getReturnType());
    }

    @Test
    void artifactAdoptionIsExplicitButExecutionUpdateHasNoAdoptShortcut() throws Exception {
        assertEquals(Void.TYPE, PreparedArtifactUpdate.class.getMethod("adopt").getReturnType());
        assertThrows(NoSuchMethodException.class,
            () -> ExecutionUpdate.class.getMethod("adopt"));
        assertFalse(PreparedArtifact.class.isAssignableFrom(ExecutionHandle.class));
        assertEquals(ExecutionTargetPlan.class, ExecutionRuntime.class.getMethod("compile",
            DeploymentTarget.class, Map.class).getReturnType(),
            "compile is synchronous pure computation, not an asynchronous I/O phase");
    }

    @Test
    void pendingObservationKeepsCapabilitySpecificExecutionDetailsSeparate() {
        var pending = ExecutionObservation.of(new PluginId("ui"), new FacetId("web"),
            new RuntimeId("client"), new ExecutionTarget("browser"), List.of());
        assertEquals(ExecutionObservation.State.PENDING, pending.aggregateState());

        var first = ExecutionObservation.Detail.builder()
            .targetRevision(1).executionId("chrome").runtimeInstanceId("one")
            .lifecycleOperationId("activate-one").capabilities(Set.of("dom"))
            .state(ExecutionObservation.State.ACTIVE).build();
        var second = ExecutionObservation.Detail.builder()
            .targetRevision(1).executionId("worker").runtimeInstanceId("two")
            .lifecycleOperationId("prepare-two").capabilities(Set.of("worker"))
            .state(ExecutionObservation.State.PENDING).build();
        var observation = ExecutionObservation.of(new PluginId("ui"), new FacetId("web"),
            new RuntimeId("client"), new ExecutionTarget("browser"),
            List.of(first, second));

        assertEquals(ExecutionObservation.State.PENDING, observation.aggregateState());
        assertEquals(Set.of("dom"), observation.executions().get(0).capabilities());
        assertEquals(Set.of("worker"), observation.executions().get(1).capabilities());

        var facet = new PluginFacet(new FacetId("web"), FacetRole.CLIENT,
            new RuntimeId("client"), new ExecutionTarget("browser"), Path.of("web.js"),
            "a".repeat(64), List.of(), List.of("dom"));
        var matched = ExecutionObservation.matching(new PluginId("ui"), facet, List.of(
            new ExecutionObservation.ExecutionCandidate(new ExecutionTarget("browser"), first),
            new ExecutionObservation.ExecutionCandidate(new ExecutionTarget("browser"), second)));
        assertEquals(ExecutionObservation.State.ACTIVE, matched.aggregateState());
        assertEquals(List.of("chrome"), matched.executions().stream()
            .map(ExecutionObservation.Detail::executionId).toList());

        var failed = ExecutionObservation.Detail.builder()
            .targetRevision(1).executionId("failed").runtimeInstanceId("three")
            .lifecycleOperationId("activate-three").capabilities(Set.of("dom"))
            .state(ExecutionObservation.State.FAILED)
            .failure(new ExecutionObservation.Failure("ACTIVATION_FAILED",
                "activation failed", Map.of("phase", "activate"))).build();
        var withFailure = ExecutionObservation.of(new PluginId("ui"),
            new FacetId("web"), new RuntimeId("client"),
            new ExecutionTarget("browser"), List.of(first, failed));
        assertEquals(ExecutionObservation.State.FAILED, withFailure.aggregateState());
        assertEquals(ExecutionObservation.State.ACTIVE,
            withFailure.executions().stream()
                .filter(detail -> detail.executionId().equals("chrome"))
                .findFirst().orElseThrow().state());
        assertThrows(IllegalArgumentException.class,
            () -> ExecutionObservation.Detail.builder()
                .targetRevision(1).executionId("invalid").runtimeInstanceId("four")
                .lifecycleOperationId("activate-four")
                .state(ExecutionObservation.State.ACTIVE)
                .failure(new ExecutionObservation.Failure("INVALID", "not allowed",
                    Map.of())).build());
    }

    @Test
    void productionArtifactResourcesRegistersBeforeIoAndCancellationClosesTheHandle() {
        var runtime = new ProbeRuntime("java");
        runtime.prepareGate = Sinks.one();
        var resources = new ArtifactResources(Map.of(runtime.id(), runtime));
        var update = resources.createUpdate(compilation(
            managedPackage("sample", "a".repeat(64), "java", "artifact")));

        assertEquals(0, runtime.createdUpdates.get());
        assertEquals(0, runtime.prepareCalls.get());
        var preparing = update.prepareAsync().subscribe();
        assertEquals(1, runtime.createdUpdates.get());
        assertEquals(1, runtime.prepareCalls.get());
        preparing.dispose();
        var closing = update.closeAsync().toFuture();
        assertEquals(0, runtime.closeCalls.get());
        runtime.prepareGate.tryEmitEmpty();
        closing.join();
        assertEquals(1, runtime.closeCalls.get());
    }

    @Test
    void partialPrepareFailureClosesEveryRegisteredRuntimeUpdate() {
        var java = new ProbeRuntime("java");
        var node = new ProbeRuntime("node");
        node.prepareFailure = new IllegalStateException("node prepare failed");
        var resources = new ArtifactResources(Map.of(java.id(), java, node.id(), node));
        var update = resources.createUpdate(compilation(
            managedPackage("java-plugin", "a".repeat(64), "java", "java-artifact"),
            managedPackage("node-plugin", "b".repeat(64), "node", "node-artifact")));

        assertThrows(IllegalStateException.class, () -> update.prepareAsync().block());
        update.closeAsync().block();

        assertEquals(1, java.closeCalls.get());
        assertEquals(1, node.closeCalls.get());
    }

    @Test
    void rejectsPreparedArtifactsThatReplaceCompiledFacetIdentity() {
        var runtime = new ProbeRuntime("java");
        runtime.replaceFacet = true;
        var resources = new ArtifactResources(Map.of(runtime.id(), runtime));
        var update = resources.createUpdate(compilation(
            managedPackage("sample", "a".repeat(64), "java", "artifact")));

        assertThrows(IllegalArgumentException.class,
            () -> update.prepareAsync().block());
        update.closeAsync().block();
    }

    @Test
    void rejectsPreparedArtifactsThatReplaceCompiledDependencyWiring() {
        var runtime = new ProbeRuntime("java");
        runtime.dropDependencies = true;
        var resources = new ArtifactResources(Map.of(runtime.id(), runtime));
        var provider = managedPackage("provider", "a".repeat(64), "java",
            "provider-artifact");
        var consumer = managedPackage("consumer", "b".repeat(64), "java",
            "consumer-artifact", List.of(new FacetDependency(
                new PluginId("provider"), new FacetId("main"))));
        var update = resources.createUpdate(compilation(provider, consumer));

        assertThrows(IllegalArgumentException.class,
            () -> update.prepareAsync().block());
        update.closeAsync().block();
    }

    @Test
    void freezesRuntimeTargetAndReadsPreparedArtifactsOnlyOnce() {
        var runtime = new ProbeRuntime("java");
        runtime.attemptTargetMutation = true;
        var resources = new ArtifactResources(Map.of(runtime.id(), runtime));
        var update = resources.createUpdate(compilation(
            managedPackage("sample", "a".repeat(64), "java", "artifact")));

        update.prepareAsync().block();
        assertTrue(runtime.targetMutationRejected);
        assertEquals(1, runtime.preparedArtifactReads.get());
        update.closeAsync().block();
    }

    @Test
    void partialAdoptKeepsActualOwnershipAndClosesTheUpdateGate() {
        var java = new ProbeRuntime("java");
        var node = new ProbeRuntime("node");
        node.adoptFailure = new IllegalStateException("node adopt failed");
        var resources = new ArtifactResources(Map.of(java.id(), java, node.id(), node));
        var target = compilation(
            managedPackage("java-plugin", "a".repeat(64), "java", "java-artifact"),
            managedPackage("node-plugin", "b".repeat(64), "node", "node-artifact"));
        var update = resources.createUpdate(target);
        update.prepareAsync().block();

        assertThrows(IllegalStateException.class, update::adopt);
        assertEquals(Set.of(new ArtifactId("java-artifact")),
            resources.preparedArtifacts().keySet());
        update.closeAsync().block();
        assertThrows(IllegalStateException.class,
            () -> resources.createUpdate(target));
    }

    @Test
    void rejectsSnapshotsAndResourcesOwnedByAnotherRuntime() {
        var managed = managedPackage("sample", "a".repeat(64), "node",
            "artifact").facets().getFirst();
        assertThrows(IllegalArgumentException.class,
            () -> new ArtifactRuntime.Snapshot(new RuntimeId("java"), List.of(
                new ArtifactRuntime.Resource(managed, "resource",
                    ArtifactRuntime.ResourceState.ACTIVE, null))));
        var javaManaged = managedPackage("sample", "b".repeat(64), "java",
            "java-artifact").facets().getFirst();
        assertThrows(IllegalArgumentException.class,
            () -> new ArtifactRuntime.Resource(javaManaged, "resource",
                ArtifactRuntime.ResourceState.CLOSE_FAILED, " "));

        var runtime = new ProbeRuntime("java");
        runtime.snapshotRuntimeId = new RuntimeId("node");
        var resources = new ArtifactResources(Map.of(runtime.id(), runtime));
        assertThrows(IllegalArgumentException.class, resources::snapshots);
    }

    @Test
    void adoptionBorrowsUnchangedArtifactsAndCleanupFailureKeepsTheGateClosed() {
        var runtime = new ProbeRuntime("java");
        var resources = new ArtifactResources(Map.of(runtime.id(), runtime));
        var provider = managedPackage("provider", "a".repeat(64), "java",
            "provider-artifact");
        var consumer = managedPackage("consumer", "b".repeat(64), "java",
            "consumer-artifact", List.of(new FacetDependency(
                new PluginId("provider"), new FacetId("main"))));
        var firstCompilation = compilation(provider, consumer);
        var first = resources.createUpdate(firstCompilation);
        first.prepareAsync().block();
        first.adopt();
        first.closeAsync().block();
        var active = resources.preparedArtifacts().get(
            new ArtifactId("consumer-artifact"));
        assertEquals(Set.of(ArtifactRuntime.ResourceState.ACTIVE),
            resources.snapshots().get(runtime.id()).resources().stream()
                .map(ArtifactRuntime.Resource::state)
                .collect(java.util.stream.Collectors.toSet()));

        var unchanged = resources.createUpdate(firstCompilation);
        unchanged.prepareAsync().block();
        unchanged.adopt();
        unchanged.closeAsync().block();
        assertSame(active, resources.preparedArtifacts().get(
            new ArtifactId("consumer-artifact")));
        assertEquals(1, runtime.createdUpdates.get(),
            "an unchanged runtime target borrows its active resources");

        var removed = resources.createUpdate(compilation());
        removed.prepareAsync().block();
        removed.adopt();
        runtime.closeFailure = new IllegalStateException("retained cleanup failure");
        var failure = assertThrows(IllegalStateException.class,
            () -> removed.closeAsync().block());
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> removed.closeAsync().block()));
        var retained = resources.snapshots().get(runtime.id()).resources();
        assertEquals(Set.of(new ArtifactId("provider-artifact"),
                new ArtifactId("consumer-artifact")),
            retained.stream().map(value -> value.facet().artifactId())
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(ArtifactRuntime.ResourceState.CLOSE_FAILED),
            retained.stream().map(ArtifactRuntime.Resource::state)
                .collect(java.util.stream.Collectors.toSet()));
        assertThrows(IllegalStateException.class,
            () -> resources.createUpdate(firstCompilation));
    }

    private static DeploymentTargetCompiler.Compilation compilation(
        ManagedPluginPackage... packages) {
        var selections = java.util.Arrays.stream(packages)
            .map(value -> new PluginSelection(value.pluginId(),
                value.packageRevision(), true)).toList();
        var target = DeploymentTarget.of(1, selections,
            new com.sstlfsj.fibra.config.DesiredInputGraph(List.of()));
        return new DeploymentTargetCompiler().compile(target, List.of(packages), List.of());
    }

    private static ManagedPluginPackage managedPackage(String pluginId, String revision,
                                                        String runtimeId, String artifactId) {
        return managedPackage(pluginId, revision, runtimeId, artifactId, List.of());
    }

    private static ManagedPluginPackage managedPackage(String pluginId, String revision,
                                                        String runtimeId, String artifactId,
                                                        List<FacetDependency> dependencies) {
        var plugin = new PluginId(pluginId);
        var facet = new ManagedFacet(new ArtifactId(artifactId), plugin, revision,
            new PluginFacet(new FacetId("main"), FacetRole.HOST,
                new RuntimeId(runtimeId), new ExecutionTarget("host"),
                Path.of(artifactId), "f".repeat(64), dependencies, List.of()));
        return ManagedPluginPackage.builder().pluginId(plugin).version("1.0.0")
            .packageRevision(revision).facets(List.of(facet)).build();
    }

    private static final class ProbeRuntime implements ArtifactRuntime {
        private final RuntimeId id;
        private final AtomicInteger createdUpdates = new AtomicInteger();
        private final AtomicInteger prepareCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicInteger preparedArtifactReads = new AtomicInteger();
        private Map<ArtifactId, PreparedArtifact> active = Map.of();
        private Map<ArtifactId, PreparedArtifact> retained = Map.of();
        private Sinks.One<Void> prepareGate;
        private Throwable prepareFailure;
        private Throwable closeFailure;
        private boolean replaceFacet;
        private boolean dropDependencies;
        private boolean attemptTargetMutation;
        private boolean targetMutationRejected;
        private RuntimeException adoptFailure;
        private RuntimeId snapshotRuntimeId;

        private ProbeRuntime(String id) { this.id = new RuntimeId(id); }
        @Override public RuntimeId id() { return id; }
        @Override public Mono<Void> probe(PluginFacet source) { return Mono.empty(); }
        @Override public Mono<RuntimeArtifactInspection> inspect(ManagedFacet facet) {
            return Mono.just(new RuntimeArtifactInspection(id, facet.artifactId(), Map.of()));
        }
        @Override
        public PreparedArtifactUpdate createUpdate(
            List<DeploymentTargetCompiler.CompiledFacet> target) {
            createdUpdates.incrementAndGet();
            if (attemptTargetMutation) {
                try {
                    target.clear();
                } catch (UnsupportedOperationException expected) {
                    targetMutationRejected = true;
                }
            }
            return new PreparedArtifactUpdate() {
                private Map<ArtifactId, PreparedArtifact> candidate;
                private Map<ArtifactId, PreparedArtifact> previous;
                private boolean adopted;
                private final Mono<Void> preparation = Mono.defer(() -> {
                    prepareCalls.incrementAndGet();
                    if (prepareFailure != null) return Mono.error(prepareFailure);
                    var next = new LinkedHashMap<ArtifactId, PreparedArtifact>();
                    target.forEach(value -> {
                        var existing = active.get(value.facet().artifactId());
                        if (existing != null && existing.facet().equals(value.facet())
                            && existing.dependencies().equals(value.dependencies())) {
                            next.put(value.facet().artifactId(), existing);
                        } else {
                            var preparedFacet = replaceFacet
                                ? new ManagedFacet(value.facet().artifactId(),
                                    new PluginId("forged"),
                                    value.facet().packageRevision(), value.facet().facet())
                                : value.facet();
                            next.put(value.facet().artifactId(),
                                new Prepared(preparedFacet, dropDependencies
                                    ? List.of() : value.dependencies()));
                        }
                    });
                    candidate = Map.copyOf(next);
                    return prepareGate == null ? Mono.empty() : prepareGate.asMono();
                }).cache();
                private final Mono<Void> close = Mono.defer(() -> {
                    closeCalls.incrementAndGet();
                    var closing = adopted ? previous : candidate;
                    if (closeFailure != null) {
                        retained = closing == null ? Map.of() : closing;
                        return Mono.<Void>error(closeFailure);
                    }
                    retained = Map.of();
                    return Mono.<Void>empty();
                }).cache();

                @Override public Mono<Void> prepareAsync() { return preparation; }
                @Override public Map<ArtifactId, PreparedArtifact> preparedArtifacts() {
                    preparedArtifactReads.incrementAndGet();
                    return candidate;
                }
                @Override public Set<ArtifactId> affectedArtifacts() {
                    var result = new java.util.LinkedHashSet<ArtifactId>();
                    result.addAll(active.keySet());
                    if (candidate != null) result.addAll(candidate.keySet());
                    return Set.copyOf(result);
                }
                @Override public void adopt() {
                    if (adoptFailure != null) throw adoptFailure;
                    previous = active;
                    active = candidate;
                    adopted = true;
                }
                @Override public Mono<Void> closeAsync() {
                    return close.doOnSuccess(ignored -> {
                        if (!adopted && candidate != null) candidate = Map.of();
                        if (adopted && previous != null) previous = Map.of();
                    });
                }
            };
        }
        @Override public Snapshot snapshot() {
            var resources = new java.util.ArrayList<ArtifactRuntime.Resource>();
            active.values().forEach(value -> resources.add(
                new ArtifactRuntime.Resource(value.facet(),
                    "active:" + value.facet().artifactId().value(),
                    ArtifactRuntime.ResourceState.ACTIVE, null)));
            retained.values().forEach(value -> resources.add(
                new ArtifactRuntime.Resource(value.facet(),
                    "retained:" + value.facet().artifactId().value(),
                    ArtifactRuntime.ResourceState.CLOSE_FAILED,
                    closeFailure.getMessage())));
            return new Snapshot(snapshotRuntimeId == null ? id : snapshotRuntimeId,
                resources);
        }
        @Override public Mono<Void> closeAsync() { return Mono.empty(); }
    }

    private record Prepared(ManagedFacet facet,
                            List<ResolvedFacetDependency> dependencies)
        implements PreparedArtifact {
        private Prepared {
            dependencies = List.copyOf(dependencies);
        }
    }
}
