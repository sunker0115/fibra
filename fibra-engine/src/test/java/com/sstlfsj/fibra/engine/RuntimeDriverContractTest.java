package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.ScopeView;
import com.sstlfsj.fibra.artifact.ExecutionTarget;
import com.sstlfsj.fibra.artifact.RuntimeId;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeDriverContractTest {
    @Test
    void providerRegistryIsAnEngineInternalCompositionDetail() {
        assertFalse(java.lang.reflect.Modifier.isPublic(
            RuntimeProviderRegistry.class.getModifiers()));
    }

    @Test
    void registryKeysProvidersByRuntimeIdRatherThanExecutionTarget() {
        var java = new ProbeProvider("java");
        var node = new ProbeProvider("node");
        var providers = RuntimeProviderRegistry.of(List.of(java, node));

        var drivers = providers.createDrivers(new ProbeHostServices());

        assertEquals(Set.of(new RuntimeId("java"), new RuntimeId("node")),
            drivers.keySet());
        assertNotSame(drivers.get(new RuntimeId("java")),
            drivers.get(new RuntimeId("node")));
        assertEquals(1, java.creations.get());
        assertEquals(1, node.creations.get());
    }

    @Test
    void duplicateRuntimeIdFailsBeforeAnyDriverIsCreated() {
        var first = new ProbeProvider("java");
        var duplicate = new ProbeProvider("java");

        assertThrows(IllegalArgumentException.class,
            () -> RuntimeProviderRegistry.of(List.of(first, duplicate)));
        assertEquals(0, first.creations.get());
        assertEquals(0, duplicate.creations.get());
    }

    @Test
    void providerIsAReusableFactoryForIndependentHostDrivers() {
        var provider = new ProbeProvider("external");
        var registry = RuntimeProviderRegistry.of(List.of(provider));

        var first = registry.createDrivers(new ProbeHostServices("host-a"))
            .get(provider.id());
        var second = registry.createDrivers(new ProbeHostServices("host-b"))
            .get(provider.id());

        assertNotSame(first, second);
        assertEquals(2, provider.creations.get());
        first.closeAsync().block();
        assertEquals(provider.id(), second.id());
        second.closeAsync().block();
    }

    @Test
    void failedDriverBatchDoesNotConsumeReusableProviders() {
        var reusable = new ProbeProvider("alpha");
        var attempts = new AtomicInteger();
        RuntimeProvider unstable = new RuntimeProvider() {
            private final RuntimeId id = new RuntimeId("zeta");
            @Override public RuntimeId id() { return id; }
            @Override public String contractIdentity() { return "unstable-v1"; }
            @Override public List<BuiltInPluginPackage> builtInPackages() {
                return List.of();
            }
            @Override public RuntimeDriver create(RuntimeHostServices services) {
                if (attempts.getAndIncrement() == 0) {
                    throw new IllegalStateException("injected create failure");
                }
                return new ProbeDriver(id, new AtomicInteger());
            }
        };
        var registry = RuntimeProviderRegistry.of(List.of(reusable, unstable));

        assertThrows(IllegalStateException.class,
            () -> registry.createDrivers(new ProbeHostServices("failed-host")));
        assertEquals(1, reusable.closes.get(),
            "a failed batch must close every driver it already acquired");

        var retried = registry.createDrivers(new ProbeHostServices("retry-host"));
        assertEquals(2, reusable.creations.get());
        retried.values().forEach(driver -> driver.closeAsync().block());
    }

    @Test
    void hostAndRuntimePlanKeepRuntimeIdentitySeparateFromPlacement() {
        var host = new ExecutionTarget("host");
        var javaUnit = ExecutionUnitPlan.builder(new ExecutionUnitKey("java:main"),
                new RuntimeId("java"), host)
            .artifactIdentity("java-artifact")
            .provenance("plugin", "main", "a".repeat(64))
            .dependencies(List.of())
            .build();
        var nodeUnit = ExecutionUnitPlan.builder(new ExecutionUnitKey("node:main"),
                new RuntimeId("node"), host)
            .artifactIdentity("node-artifact")
            .provenance("plugin", "node", "b".repeat(64))
            .dependencies(List.of(javaUnit.key()))
            .build();

        var javaPlan = RuntimePlan.of(new RuntimeId("java"), List.of(javaUnit),
            List.of(binding(javaUnit)));
        var nodePlan = RuntimePlan.of(new RuntimeId("node"), List.of(nodeUnit),
            List.of(binding(nodeUnit)));

        assertEquals(host, javaPlan.units().get(javaUnit.key()).executionTarget());
        assertEquals(host, nodePlan.units().get(nodeUnit.key()).executionTarget());
        assertEquals(new RuntimeId("java"), javaPlan.runtimeId());
        assertEquals(new RuntimeId("node"), nodePlan.runtimeId());
    }

    @Test
    void runtimeUnitFenceRequiresOneCompleteExecutionIdentity() {
        var runtime = new RuntimeId("java");
        var unit = new ExecutionUnitKey("unit");
        var fence = RuntimeUnitFence.builder(runtime, unit)
            .unitTargetRevision(3)
            .runtimeInstanceId("java:unit:7")
            .build();

        assertEquals(runtime, fence.runtimeId());
        assertEquals(unit, fence.unitKey());
        assertEquals(3, fence.unitTargetRevision());
        assertEquals("java:unit:7", fence.runtimeInstanceId());
        assertThrows(IllegalArgumentException.class, () ->
            RuntimeUnitFence.builder(runtime, unit)
                .runtimeInstanceId("java:unit:7").build());
        assertThrows(IllegalArgumentException.class, () ->
            RuntimeUnitFence.builder(runtime, unit)
                .unitTargetRevision(1).runtimeInstanceId(" ").build());
    }

    private static DefinitionBindingPlan binding(ExecutionUnitPlan unit) {
        return DefinitionBindingPlan.builder(new com.sstlfsj.fibra.config.PluginDefinitionRef(
            unit.pluginId().value(), unit.facetId().value(), "definition"), unit.key().value())
            .unitKey(unit.key()).publicationRequirement(com.sstlfsj.fibra.config.PublicationRequirement.ACTIVE_REQUIRED).build();
    }

    private static final class ProbeProvider implements RuntimeProvider {
        private final RuntimeId id;
        private final AtomicInteger creations = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        private ProbeProvider(String id) {
            this.id = new RuntimeId(id);
        }

        @Override
        public RuntimeId id() {
            return id;
        }

        @Override public String contractIdentity() { return "test-v1"; }
        @Override public List<BuiltInPluginPackage> builtInPackages() { return List.of(); }

        @Override
        public RuntimeDriver create(RuntimeHostServices services) {
            creations.incrementAndGet();
            return new ProbeDriver(id, closes);
        }
    }

    private record ProbeDriver(RuntimeId id, AtomicInteger closes)
        implements RuntimeDriver {
        @Override
        public Mono<RuntimeArtifactInspection> probe(PluginFacetSource source) {
            return Mono.error(new UnsupportedOperationException());
        }

        @Override
        public Mono<RuntimeArtifactInspection> inspect(
            com.sstlfsj.fibra.artifact.ManagedFacet facet) {
            return Mono.error(new UnsupportedOperationException());
        }

        @Override
        public RuntimeCandidate createCandidate(RuntimeTargetSlice target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RuntimeDriverSnapshot snapshot() {
            return new RuntimeDriverSnapshot(id, Map.of());
        }

        @Override
        public Mono<Void> closeAsync() {
            return Mono.fromRunnable(closes::incrementAndGet);
        }
    }

    private static final class ProbeHostServices implements RuntimeHostServices {
        private final String hostInstanceId;

        private ProbeHostServices() {
            this("contract-test-host");
        }

        private ProbeHostServices(String hostInstanceId) {
            this.hostInstanceId = hostInstanceId;
        }

        @Override public String hostInstanceId() { return hostInstanceId; }
        @Override public ScopeView scope() { return null; }
        @Override public Mono<Void> releaseScope(com.sstlfsj.fibra.Scope scope) {
            return Mono.defer(scope::closeAsync);
        }
        @Override public com.sstlfsj.fibra.bridge.ContributionKindRegistry contributionKinds() {
            return com.sstlfsj.fibra.bridge.ContributionKindRegistry.empty();
        }
        @Override public com.sstlfsj.fibra.bridge.ContributionAdmission openContributionAdmission(com.sstlfsj.fibra.engine.ExecutionUnitKey key) {
            throw new UnsupportedOperationException();
        }
        @Override public RemoteContributionInvoker remoteContributions() {
            throw new UnsupportedOperationException();
        }
        @Override public String nextIdentity(String namespace) {
            return namespace + "-1";
        }
        @Override public void requestReconcile(Set<RuntimeUnitFence> fences,
                                               String reason) { }
        @Override public void requestObservationRefresh(RuntimeUnitFence fence) { }
        @Override public void requestDisable(RuntimeUnitDisableRequest request) { }
        @Override public void requestRecompile(RuntimeRecompileReason reason) { }
    }
}
