package verification.distribution;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ExecutionTarget;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.config.PublicationRequirement;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredEvaluation;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.engine.CompiledRuntimeSlice;
import com.sstlfsj.fibra.engine.DefinitionBindingPlan;
import com.sstlfsj.fibra.engine.DeploymentTarget;
import com.sstlfsj.fibra.engine.ExecutionObservation;
import com.sstlfsj.fibra.engine.ExecutionUnitKey;
import com.sstlfsj.fibra.engine.ExecutionUnitPlan;
import com.sstlfsj.fibra.engine.FileDeploymentTargetStore;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.PreparedRuntimeGeneration;
import com.sstlfsj.fibra.engine.RuntimeArtifactInspection;
import com.sstlfsj.fibra.engine.RuntimeCandidate;
import com.sstlfsj.fibra.engine.RuntimeDriver;
import com.sstlfsj.fibra.engine.RuntimeDriverSnapshot;
import com.sstlfsj.fibra.engine.RuntimeHostServices;
import com.sstlfsj.fibra.engine.RuntimePlan;
import com.sstlfsj.fibra.engine.RuntimeProvider;
import com.sstlfsj.fibra.engine.RuntimeTargetSlice;
import com.sstlfsj.fibra.engine.RuntimeUnitFence;
import com.sstlfsj.fibra.engine.RuntimeUnitGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeProviderConsumerTest {
    @Test
    void registersAnExternalRuntimeProviderUsingOnlyPublishedSpi(@TempDir Path work) {
        var provider = new ConsumerRuntimeProvider();
        try (var packages = new PluginPackageStore(work.resolve("packages"));
             var targets = new FileDeploymentTargetStore(work.resolve("targets"));
             var engine = FibraEngine.builder(packages, targets)
                 .runtimeProvider(provider)
                 .hostTerminationPort(ignored -> { })
                 .build()) {
            assertEquals(1, provider.createdDrivers);

            var candidate = provider.driver.createCandidate(targetSlice());
            candidate.prepareAsync().block();
            var plan = candidate.preparedPlan();
            var generation = candidate.seal(CompiledRuntimeSlice.of(plan,
                List.copyOf(plan.units().keySet())));
            var unit = generation.units().get(new ExecutionUnitKey("consumer"));

            assertEquals(ConsumerRuntimeProvider.RUNTIME_ID, plan.runtimeId());
            assertEquals(ExecutionObservation.State.ACTIVE,
                unit.reconcileAsync("consumer-activate").block().aggregateState());
            generation.retireAsync().block();
            assertSame(provider.driver, provider.lastDriver());
        }
    }

    private static RuntimeTargetSlice targetSlice() {
        var graph = new DesiredInputGraph(List.of());
        var context = ConfigContextSnapshot.empty();
        var target = DeploymentTarget.of(1, List.of(), graph, context);
        return RuntimeTargetSlice.builder(ConsumerRuntimeProvider.RUNTIME_ID, target)
            .desired(DesiredEvaluation.evaluate(graph, context))
            .capabilities(com.sstlfsj.fibra.engine.HostCapabilitySnapshot.empty())
            .affectedEntryIds(Set.of())
            .unitDependencies(Map.of())
            .build();
    }

    private static final class ConsumerRuntimeProvider implements RuntimeProvider {
        private static final RuntimeId RUNTIME_ID = new RuntimeId("consumer-runtime");
        private int createdDrivers;
        private ConsumerRuntimeDriver driver;

        @Override public RuntimeId id() { return RUNTIME_ID; }
        @Override public String contractIdentity() { return "verification.consumer.runtime/v1"; }
        @Override public List<com.sstlfsj.fibra.engine.BuiltInPluginPackage> builtInPackages() {
            return List.of();
        }
        @Override public RuntimeDriver create(RuntimeHostServices services) {
            createdDrivers++;
            return driver = new ConsumerRuntimeDriver();
        }
        ConsumerRuntimeDriver lastDriver() { return driver; }
    }

    private static final class ConsumerRuntimeDriver implements RuntimeDriver {
        private final RuntimePlan plan = plan();

        @Override public RuntimeId id() { return ConsumerRuntimeProvider.RUNTIME_ID; }
        @Override public Mono<RuntimeArtifactInspection> probe(
            com.sstlfsj.fibra.engine.PluginFacetSource source) {
            return Mono.error(new UnsupportedOperationException("fixture has no package probe"));
        }
        @Override public Mono<RuntimeArtifactInspection> inspect(
            com.sstlfsj.fibra.artifact.ManagedFacet facet) {
            return Mono.error(new UnsupportedOperationException("fixture has no package inspection"));
        }
        @Override public RuntimeCandidate createCandidate(RuntimeTargetSlice target) {
            java.util.Objects.requireNonNull(target, "target");
            return new ConsumerRuntimeCandidate(plan);
        }
        @Override public RuntimeDriverSnapshot snapshot() {
            return new RuntimeDriverSnapshot(id(), Map.of());
        }
        @Override public Mono<Void> closeAsync() { return Mono.empty(); }

        private static RuntimePlan plan() {
            var key = new ExecutionUnitKey("consumer");
            var unit = ExecutionUnitPlan.builder(key, ConsumerRuntimeProvider.RUNTIME_ID,
                    new ExecutionTarget("consumer-host"))
                .artifactId(new ArtifactId("consumer-artifact"))
                .provenance("consumer-plugin", "consumer-facet", "a".repeat(64))
                .build();
            var binding = DefinitionBindingPlan.builder(new PluginDefinitionRef(
                    "consumer-plugin", "consumer-facet", "consumer"), "consumer")
                .unitKey(key)
                .publicationRequirement(PublicationRequirement.ACTIVE_REQUIRED)
                .build();
            return RuntimePlan.of(ConsumerRuntimeProvider.RUNTIME_ID,
                List.of(unit), List.of(binding));
        }
    }

    private static final class ConsumerRuntimeCandidate implements RuntimeCandidate {
        private final RuntimePlan plan;

        private ConsumerRuntimeCandidate(RuntimePlan plan) { this.plan = plan; }
        @Override public Mono<Void> prepareAsync() { return Mono.empty(); }
        @Override public RuntimePlan preparedPlan() { return plan; }
        @Override public PreparedRuntimeGeneration seal(CompiledRuntimeSlice slice) {
            var unit = plan.units().values().iterator().next();
            return new ConsumerRuntimeGeneration(unit);
        }
        @Override public Mono<Void> closeAsync() { return Mono.empty(); }
    }

    private static final class ConsumerRuntimeGeneration implements PreparedRuntimeGeneration {
        private final Map<ExecutionUnitKey, RuntimeUnitGeneration> units;

        private ConsumerRuntimeGeneration(ExecutionUnitPlan plan) {
            units = Map.of(plan.key(), new ConsumerRuntimeUnit(plan));
        }
        @Override public Map<ExecutionUnitKey, RuntimeUnitGeneration> units() { return units; }
        @Override public Mono<Void> abortAsync() { return Mono.empty(); }
        @Override public Mono<Void> retireAsync() { return Mono.empty(); }
    }

    private static final class ConsumerRuntimeUnit implements RuntimeUnitGeneration {
        private final ExecutionUnitPlan plan;

        private ConsumerRuntimeUnit(ExecutionUnitPlan plan) { this.plan = plan; }
        @Override public ExecutionUnitPlan plan() { return plan; }
        @Override public RuntimeUnitFence fence() {
            return RuntimeUnitFence.builder(ConsumerRuntimeProvider.RUNTIME_ID,
                    plan.key())
                .unitTargetRevision(1)
                .runtimeInstanceId("consumer-instance")
                .build();
        }
        @Override public Mono<ExecutionObservation> reconcileAsync(String operationId) {
            return Mono.just(observation(operationId));
        }
        @Override public void closeAdmission() { }
        @Override public Mono<ExecutionObservation> drainAsync(String operationId, Instant deadline) {
            return Mono.just(observation(operationId));
        }
        @Override public Mono<ExecutionObservation> stopAsync(String operationId, Instant deadline) {
            return Mono.just(observation(operationId));
        }
        @Override public ExecutionObservation snapshot() { return observation("consumer-snapshot"); }

        private ExecutionObservation observation(String operationId) {
            var detail = ExecutionObservation.Detail.builder()
                .unitTargetRevision(1)
                .executionId("consumer-execution")
                .runtimeInstanceId("consumer-instance")
                .lifecycleOperationId(operationId)
                .state(ExecutionObservation.State.ACTIVE)
                .build();
            return ExecutionObservation.of(new PluginId("consumer-plugin"),
                new FacetId("consumer-facet"), ConsumerRuntimeProvider.RUNTIME_ID,
                plan.executionTarget(), List.of(detail));
        }
    }
}
