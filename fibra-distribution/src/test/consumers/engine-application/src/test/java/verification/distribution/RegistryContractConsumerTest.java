package verification.distribution;

import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.deployment.FileDeploymentTargetStore;
import com.sstlfsj.fibra.engine.observation.TargetSaveState;
import com.sstlfsj.fibra.registry.InMemoryPluginAuditRepository;
import com.sstlfsj.fibra.registry.PluginDeploymentRequest;
import com.sstlfsj.fibra.registry.PluginRegistry;
import com.sstlfsj.fibra.registry.RegistrySnapshot;
import com.sstlfsj.fibra.runtime.java.JavaRuntimeProvider;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RegistryContractConsumerTest {
    private static final Duration LIMIT = Duration.ofSeconds(10);
    @TempDir Path work;

    @Test
    void publicReplacementPreservesTheCallersRevisionAcrossEditingAndSubscription() {
        var packages = new PluginPackageStore(work.resolve("packages"));
        var targets = new FileDeploymentTargetStore(work.resolve("targets"));
        try (var engine = FibraEngine.builder(packages, targets)
            .runtimeProvider(new JavaRuntimeProvider(List.of()))
            .hostTerminationPort(value -> { throw new AssertionError(value); }).build()) {
            var registry = new PluginRegistry(engine, new InMemoryPluginAuditRepository());
            engine.startAsync().block(LIMIT);
            var first = registry.deploy(request(0, "first")).block(LIMIT);
            assertEquals(1, first.target().orElseThrow().targetRevision());
            var editedEarlier = registry.deploy(request(1, "stale edit"));
            var second = registry.deploy(request(1, "second")).block(LIMIT);
            assertEquals(2, second.target().orElseThrow().targetRevision());

            var failure = assertThrows(IllegalStateException.class, () -> editedEarlier.block(LIMIT));
            assertTrue(failure.getMessage().contains("deployment target revision conflict"));
            assertEquals(second.target(), registry.snapshot().target());
            assertEquals(second.viewRevision(), registry.snapshot().viewRevision());
            assertEquals(TargetSaveState.NOT_SAVED, registry.history().getLast().targetSaveState());
            assertFalse(registry.history().getLast().succeeded());

            assertThrows(IllegalStateException.class,
                () -> registry.deploy(request(1, "second")).block(LIMIT), "stale equal content still conflicts");
            var unchanged = registry.deploy(request(2, "second")).block(LIMIT);
            assertEquals(2, unchanged.target().orElseThrow().targetRevision());
            assertEquals(TargetSaveState.NOT_APPLICABLE, registry.history().getLast().targetSaveState());

            var replacement = registry.deploy(request(2, "third"));
            assertEquals(3, replacement.block(LIMIT).target().orElseThrow().targetRevision());
            assertThrows(IllegalStateException.class, () -> replacement.block(LIMIT));
            assertEquals(3, registry.snapshot().target().orElseThrow().targetRevision());
            assertThrows(IllegalArgumentException.class, () -> request(-1, "negative"));
        }
    }

    @Test
    void managementAndSupportedDiagnosticsExposeTheSameFactsThroughDifferentReadContracts() {
        var packages = new PluginPackageStore(work.resolve("packages"));
        var targets = new FileDeploymentTargetStore(work.resolve("targets"));
        try (var engine = FibraEngine.builder(packages, targets)
            .runtimeProvider(new JavaRuntimeProvider(List.of()))
            .hostTerminationPort(value -> { throw new AssertionError(value); }).build()) {
            var registry = new PluginRegistry(engine, new InMemoryPluginAuditRepository());
            engine.startAsync().block(LIMIT);
            assertTrue(registry.snapshot().target().isEmpty());
            registry.deploy(request(0, "current")).block(LIMIT);
            var ordinary = registry.snapshot();
            var diagnostics = registry.diagnostics();
            assertEquals(ordinary.viewRevision(), diagnostics.viewRevision());
            assertEquals(ordinary.state(), diagnostics.engine().state());
            assertEquals(ordinary.durableState(), diagnostics.engine().durableState());
            assertEquals(ordinary.targetConvergence(), diagnostics.engine().targetConvergence());
            assertEquals(ordinary.target(), diagnostics.engine().target());
            assertEquals(ordinary.mutationGateOpen(), diagnostics.engineDiagnostics().mutationGateOpen());
            assertEquals(ordinary.contributionAdmissionOpen(), diagnostics.engineDiagnostics().contributionAdmissionOpen());
            assertTrue(ordinary.observed().isEmpty());
            assertTrue(ordinary.selections().isEmpty());
            assertEquals(Set.of("viewRevision", "state", "durableState", "targetConvergence",
                    "mutationGateOpen", "contributionAdmissionOpen", "target", "observed", "auditFailures"),
                Arrays.stream(RegistrySnapshot.class.getRecordComponents())
                    .map(value -> value.getName()).collect(Collectors.toSet()));
            engine.closeAsync().block(LIMIT);
            assertFalse(registry.snapshot().mutationGateOpen());
            assertFalse(registry.diagnostics().engineDiagnostics().contributionAdmissionOpen());
        }
    }

    private static PluginDeploymentRequest request(long revision, String value) {
        return new PluginDeploymentRequest(revision, List.of(), new DesiredInputGraph(List.of()),
            ConfigContextSnapshot.of((LiteralValue.ObjectValue) LiteralValue.of(Map.of("value", value))));
    }
}
