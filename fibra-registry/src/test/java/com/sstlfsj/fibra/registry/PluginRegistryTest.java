package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.PluginCatalog;
import com.sstlfsj.fibra.engine.PluginCatalogEntry;
import com.sstlfsj.fibra.engine.PluginRuntimeAdapter;
import com.sstlfsj.fibra.engine.RuntimeGeneration;
import com.sstlfsj.fibra.engine.RuntimeArtifactInspection;
import com.sstlfsj.fibra.engine.RuntimeGenerationRequest;
import com.sstlfsj.fibra.engine.RuntimeGenerationSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginRegistryTest {
    @Test
    void exposesArtifactDesiredAndObservedFactsWithoutOwningAnotherStateMachine(
        @TempDir Path work) throws Exception {
        var source = work.resolve("sample.bin");
        Files.writeString(source, "plugin");
        var runtimeId = new RuntimeId("fake");
        var artifactId = new ArtifactId("sample");
        var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(new ArtifactStore(work.resolve("artifacts")))
            .runtimeAdapter(new FakeRuntime(runtimeId)).build();
        engine.start().block();
        var audit = new InMemoryPluginAuditRepository();
        try {
            var registry = new PluginRegistry(engine, audit);
            registry.install(PluginInstallRequest.builder().artifactId(artifactId)
                .runtimeId(runtimeId).version("1.0.0").source(source).build()).block();
            var enabled = registry.enable(PluginEnableRequest.of(
                "sample-one", "sample", com.sstlfsj.fibra.value.LiteralValue.of(
                    Map.of("message", "hello")))).block();

            assertTrue(enabled.artifacts().containsKey(artifactId));
            assertTrue(enabled.desired().get("sample-one").enabled());
            assertEquals(com.sstlfsj.fibra.PluginInstanceState.ACTIVE,
                enabled.observed().get("sample-one").state());

            var disabled = registry.disable("sample-one").block();
            assertFalse(disabled.desired().get("sample-one").enabled());
            assertFalse(disabled.observed().containsKey("sample-one"));
            var removed = registry.uninstall(artifactId).block();
            assertFalse(removed.artifacts().containsKey(artifactId));
            assertEquals(4, registry.history().size());
            assertTrue(registry.history().stream().allMatch(PluginAuditEntry::succeeded));
        } finally {
            engine.close();
        }
    }

    @Test
    void fileAuditIsAppendOnlyAcrossReopen(@TempDir Path work) {
        var file = work.resolve("audit.log");
        try (var audit = new FilePluginAuditRepository(file)) {
            audit.append("install", "sample", true, "1", "accepted");
        }
        try (var audit = new FilePluginAuditRepository(file)) {
            audit.append("disable", "sample-one", false, "1", "conflict");
            assertEquals(2, audit.history().size());
            assertEquals(2, audit.history().get(1).sequence());
        }
    }

    @Test
    void fileAuditHasOneProcessOwner(@TempDir Path work) {
        var file = work.resolve("audit.log");
        try (var audit = new FilePluginAuditRepository(file)) {
            assertThrows(IllegalStateException.class,
                () -> new FilePluginAuditRepository(file));
        }
    }

    @Test
    void deploysArtifactsAndDesiredGraphThroughOneRegistryOperation(
        @TempDir Path work) throws Exception {
        var source = work.resolve("sample.bin");
        Files.writeString(source, "plugin");
        var runtimeId = new RuntimeId("fake");
        var artifactId = new ArtifactId("sample");
        var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(new ArtifactStore(work.resolve("artifacts")))
            .runtimeAdapter(new FakeRuntime(runtimeId)).build();
        engine.start().block();
        var audit = new InMemoryPluginAuditRepository();
        try {
            var registry = new PluginRegistry(engine, audit);
            var installed = PluginInstallRequest.builder().artifactId(artifactId)
                .runtimeId(runtimeId).version("1.0.0").source(source).build();
            var graph = new DesiredInputGraph(List.of(
                DesiredInputEntry.builder("sample-one", "sample").build()));

            var deployed = registry.deploy(
                new PluginDeploymentRequest(List.of(installed), graph)).block();

            assertTrue(deployed.artifacts().containsKey(artifactId));
            assertTrue(deployed.observed().containsKey("sample-one"));
            assertEquals(1, registry.history().size());
            assertEquals("deploy", registry.history().getFirst().operation());
        } finally {
            engine.close();
        }
    }

    private static final class FakeRuntime implements PluginRuntimeAdapter {
        private final RuntimeId id;

        private FakeRuntime(RuntimeId id) {
            this.id = id;
        }

        @Override
        public RuntimeId id() {
            return id;
        }

        @Override
        public Mono<RuntimeArtifactInspection> inspect(ArtifactRecord artifact) {
            return Mono.just(new RuntimeArtifactInspection(id, artifact.id(), Map.of()));
        }

        @Override
        public RuntimeGeneration create(RuntimeGenerationRequest request) {
            var catalog = request.artifacts().isEmpty() ? PluginCatalog.empty()
                : PluginCatalog.of(new PluginCatalogEntry<>(
                    PluginDefinition.builder("sample", Object.class,
                        () -> (context, config) -> Mono.empty()).build(), value -> value));
            var snapshot = new RuntimeGenerationSnapshot(id,
                Integer.toString(request.artifacts().size()),
                request.artifacts().stream().collect(java.util.stream.Collectors.toMap(
                    ArtifactRecord::id, value -> value)),
                catalog.entries().stream().map(entry -> entry.definition().name())
                    .collect(java.util.stream.Collectors.toSet()));
            return new RuntimeGeneration() {
                @Override public Mono<Void> prepareAsync() { return Mono.empty(); }
                @Override
                public RuntimeGenerationSnapshot snapshot() {
                    return snapshot;
                }

                @Override
                public PluginCatalog catalog() {
                    return catalog;
                }

                @Override
                public Mono<Void> closeAsync() { return Mono.empty(); }
            };
        }
    }
}
