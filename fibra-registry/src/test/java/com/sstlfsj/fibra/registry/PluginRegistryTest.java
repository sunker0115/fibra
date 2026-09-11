package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.DesiredInputGroup;
import com.sstlfsj.fibra.config.DesiredInputInclude;
import com.sstlfsj.fibra.config.DesiredIncludeContent;
import com.sstlfsj.fibra.config.PublicationRequirement;
import com.sstlfsj.fibra.engine.EngineChangeException;
import com.sstlfsj.fibra.engine.EngineStateStore;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.PluginCatalog;
import com.sstlfsj.fibra.engine.PluginCatalogEntry;
import com.sstlfsj.fibra.engine.PluginRuntimeAdapter;
import com.sstlfsj.fibra.engine.RuntimeArtifactInspection;
import com.sstlfsj.fibra.engine.RuntimeCatalog;
import com.sstlfsj.fibra.engine.RuntimeResourceOwner;
import com.sstlfsj.fibra.engine.RuntimeResourceSnapshot;
import com.sstlfsj.fibra.engine.RuntimeResourceUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
            assertTrue(enabled.desiredGraph().plugins().get("sample-one").enabled());
            assertEquals(com.sstlfsj.fibra.PluginInstanceState.ACTIVE,
                enabled.observed().get("sample-one").state());

            var disabled = registry.disable("sample-one").block();
            assertFalse(disabled.desiredGraph().plugins().get("sample-one").enabled());
            assertFalse(disabled.observed().containsKey("sample-one"));
            var removed = registry.uninstall(artifactId).block();
            assertFalse(removed.artifacts().containsKey(artifactId));
            assertEquals(4, registry.history().size());
            assertTrue(registry.history().stream().allMatch(PluginAuditEntry::succeeded));
            assertTrue(registry.history().stream().allMatch(entry ->
                entry.targetSaveState() == TargetSaveState.SAVED));
        } finally {
            engine.close();
        }
    }

    @Test
    void fileAuditIsAppendOnlyAcrossReopen(@TempDir Path work) {
        var file = work.resolve("audit.log");
        try (var audit = new FilePluginAuditRepository(file)) {
            audit.append("install", "sample", true, TargetSaveState.SAVED,
                "1", "accepted");
        }
        try (var audit = new FilePluginAuditRepository(file)) {
            audit.append("disable", "sample-one", false,
                TargetSaveState.UNCONFIRMED, "1", "conflict");
            assertEquals(2, audit.history().size());
            assertEquals(2, audit.history().get(1).sequence());
            assertEquals(TargetSaveState.UNCONFIRMED,
                audit.history().get(1).targetSaveState());
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
    void fileAuditRejectsThePreviousSevenColumnFormatAndReleasesItsLock(
        @TempDir Path work) throws Exception {
        var file = work.resolve("audit.log");
        Files.writeString(file, "1\t2026-09-11T00:00:00Z\taW5zdGFsbA\tc2FtcGxl\ttrue\tMQ\tYWNjZXB0ZWQ\n");

        assertThrows(IllegalStateException.class, () -> new FilePluginAuditRepository(file));

        Files.writeString(file, "");
        try (var audit = new FilePluginAuditRepository(file)) {
            assertTrue(audit.history().isEmpty());
        }
    }

    @Test
    void auditDeliveryFailureDoesNotChangeASuccessfulDeploymentResult() {
        var graph = new DesiredInputGraph(List.of(
            DesiredInputEntry.builder("disabled", "absent").enabled(false).build()));
        var auditFailure = new IllegalStateException("audit unavailable");
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph)).build()) {
            engine.start().block();
            var registry = new PluginRegistry(engine, failingAudit(auditFailure));

            var result = registry.disable("disabled").block();

            assertTrue(result.desiredGraph().plugins().containsKey("disabled"));
            var diagnostic = registry.auditFailures().getFirst();
            assertEquals("disable", diagnostic.operation());
            assertEquals("disabled", diagnostic.target());
            assertTrue(diagnostic.succeeded());
            assertEquals(TargetSaveState.SAVED, diagnostic.targetSaveState());
            assertEquals(result.viewRevision(), diagnostic.viewRevision());
            assertEquals(auditFailure.toString(), diagnostic.detail());
            assertEquals(List.of(diagnostic), result.auditFailures());
        }
    }

    @Test
    void auditDeliveryFailureKeepsTheOriginalSavedTargetFailure() {
        var startupFailure = new IllegalStateException("plugin start failed");
        var definition = PluginDefinition.builder("failing", Void.class,
            () -> (context, config) -> Mono.error(startupFailure)).build();
        var auditFailure = new IllegalStateException("audit unavailable");
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition, ignored -> null)))
            .build()) {
            engine.start().block();
            var registry = new PluginRegistry(engine, failingAudit(auditFailure));
            var observed = new AtomicReference<Throwable>();

            registry.enable(PluginEnableRequest.of("failing", "failing",
                    com.sstlfsj.fibra.value.LiteralValue.of(null)))
                .doOnError(observed::set).onErrorComplete().block();

            var failure = assertInstanceOf(EngineChangeException.class, observed.get());
            assertTrue(failure.targetSaved());
            assertTrue(containsThrowable(failure, startupFailure));
            assertFalse(containsThrowable(failure, auditFailure));
            var diagnostic = registry.auditFailures().getFirst();
            assertFalse(diagnostic.succeeded());
            assertEquals(TargetSaveState.SAVED, diagnostic.targetSaveState());
            assertEquals(failure.view().viewRevision(), diagnostic.viewRevision());
            assertEquals(auditFailure.toString(), diagnostic.detail());
        }
    }

    @Test
    void auditKeepsSavedTargetSeparateFromAConvergedRequest() {
        var startupFailure = new IllegalStateException("plugin start failed");
        var definition = PluginDefinition.builder("failing", Void.class,
            () -> (context, config) -> Mono.error(startupFailure)).build();
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition, ignored -> null)))
            .build()) {
            engine.start().block();
            var audit = new InMemoryPluginAuditRepository();
            var registry = new PluginRegistry(engine, audit);
            var observed = new AtomicReference<Throwable>();

            registry.enable(PluginEnableRequest.of("failing", "failing",
                    com.sstlfsj.fibra.value.LiteralValue.of(null)))
                .doOnError(observed::set).onErrorComplete().block();

            var failure = assertInstanceOf(EngineChangeException.class, observed.get());
            var entry = audit.history().getFirst();
            assertFalse(entry.succeeded());
            assertEquals(TargetSaveState.SAVED, entry.targetSaveState());
            assertEquals(failure.view().viewRevision(), entry.viewRevision());
        }
    }

    @Test
    void auditMarksBindingRejectionAsNotSaved() {
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty()).build()) {
            engine.start().block();
            var audit = new InMemoryPluginAuditRepository();
            var registry = new PluginRegistry(engine, audit);
            var observed = new AtomicReference<Throwable>();

            registry.enable(PluginEnableRequest.of("missing", "missing",
                    com.sstlfsj.fibra.value.LiteralValue.of(null)))
                .doOnError(observed::set).onErrorComplete().block();

            assertInstanceOf(EngineChangeException.class, observed.get());
            assertFalse(audit.history().getFirst().succeeded());
            assertEquals(TargetSaveState.NOT_SAVED,
                audit.history().getFirst().targetSaveState());
        }
    }

    @Test
    void auditRecordsAnUnconfirmedTargetSaveSeparatelyFromNotSaved() {
        var store = new UnconfirmedSaveStore();
        var definition = PluginDefinition.builder("sample", Void.class,
            () -> (context, config) -> Mono.empty()).build();
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition, ignored -> null)))
            .stateStore(store).build()) {
            engine.start().block();
            store.unconfirmed = true;
            var audit = new InMemoryPluginAuditRepository();
            var registry = new PluginRegistry(engine, audit);
            var observed = new AtomicReference<Throwable>();

            registry.enable(PluginEnableRequest.of("sample", "sample",
                    com.sstlfsj.fibra.value.LiteralValue.of(null)))
                .doOnError(observed::set).onErrorComplete().block();

            var failure = assertInstanceOf(EngineChangeException.class, observed.get());
            assertFalse(failure.targetSaved());
            assertInstanceOf(EngineStateStore.SaveUnconfirmedException.class,
                failure.getCause());
            assertEquals(TargetSaveState.UNCONFIRMED,
                audit.history().getFirst().targetSaveState());
            assertFalse(audit.history().getFirst().succeeded());
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
                DesiredInputGroup.builder("plugins").children(List.of(
                    DesiredInputEntry.builder("sample-one", "sample").build())).build()));

            var deployed = registry.deploy(
                new PluginDeploymentRequest(List.of(installed), graph)).block();

            assertTrue(deployed.artifacts().containsKey(artifactId));
            assertTrue(deployed.observed().containsKey("sample-one"));
            assertInstanceOf(DesiredInputGroup.class,
                deployed.desiredGraph().roots().getFirst());
            assertTrue(deployed.desiredGraph().plugins().containsKey("sample-one"));
            assertEquals(1, registry.history().size());
            assertEquals("deploy", registry.history().getFirst().operation());
        } finally {
            engine.close();
        }
    }

    @Test
    void disablingAndEnablingAGroupPreservesEachChildsLocalEnabledState(
        @TempDir Path work) throws Exception {
        try (var harness = registry(work)) {
            harness.deploy(new DesiredInputGraph(List.of(
                DesiredInputGroup.builder("plugins").children(List.of(
                    DesiredInputEntry.builder("disabled-child", "sample")
                        .enabled(false).build())).build())));

            var disabled = harness.registry().disable("plugins").block();
            assertFalse(assertInstanceOf(DesiredInputGroup.class,
                disabled.desiredGraph().require("plugins")).enabled());
            assertFalse(disabled.desiredGraph().plugins().get("disabled-child").enabled());

            var enabled = harness.registry().enable("plugins").block();
            assertTrue(assertInstanceOf(DesiredInputGroup.class,
                enabled.desiredGraph().require("plugins")).enabled());
            assertFalse(enabled.desiredGraph().plugins().get("disabled-child").enabled());
        }
    }

    @Test
    void enablingAnExistingEntryKeepsItsConfigurationAndPublicationRequirement(
        @TempDir Path work) throws Exception {
        var config = com.sstlfsj.fibra.value.LiteralValue.of(Map.of("message", "kept"));
        try (var harness = registry(work)) {
            harness.deploy(new DesiredInputGraph(List.of(
                DesiredInputEntry.builder("sample-one", "sample").enabled(false)
                    .publicationRequirement(PublicationRequirement.PENDING_ALLOWED)
                    .config(config).build())));

            var enabled = harness.registry().enable("sample-one").block();
            var entry = enabled.desiredGraph().plugins().get("sample-one");
            assertTrue(entry.enabled());
            assertEquals(config, entry.config());
            assertEquals(PublicationRequirement.PENDING_ALLOWED,
                entry.publicationRequirement());
        }
    }

    @Test
    void enablingARequestUnderAParentAddsTheEntryToThatParentsChildren(
        @TempDir Path work) throws Exception {
        try (var harness = registry(work)) {
            harness.deploy(new DesiredInputGraph(List.of(
                DesiredInputGroup.builder("plugins").children(List.of()).build())));

            var snapshot = harness.registry().enable(PluginEnableRequest
                .builder("sample-child", "sample").parentId("plugins").build()).block();

            var parent = assertInstanceOf(DesiredInputGroup.class,
                snapshot.desiredGraph().require("plugins"));
            assertEquals(List.of("sample-child"), parent.children().stream()
                .map(node -> node.id()).toList());
            assertTrue(snapshot.desiredGraph().plugins().containsKey("sample-child"));
        }
    }

    @Test
    void movingAGroupChildKeepsItsFullIdentityAndLocalEntry(
        @TempDir Path work) throws Exception {
        var child = DesiredInputEntry.builder("sample-child", "sample")
            .enabled(false).build();
        try (var harness = registry(work)) {
            harness.deploy(new DesiredInputGraph(List.of(
                DesiredInputGroup.builder("left").children(List.of(child)).build(),
                DesiredInputGroup.builder("right").children(List.of()).build())));

            var moved = harness.registry().move("sample-child", "right", 0).block();
            var right = assertInstanceOf(DesiredInputGroup.class,
                moved.desiredGraph().require("right"));
            assertEquals(List.of("sample-child"), right.children().stream()
                .map(node -> node.id()).toList());
            assertEquals(child, moved.desiredGraph().plugins().get("sample-child"));
        }
    }

    @Test
    void getAndListUseTheGraphsFullPluginIdsForIncludedEntries(@TempDir Path work)
        throws Exception {
        try (var harness = registry(work)) {
            harness.deploy(new DesiredInputGraph(List.of(
                DesiredInputInclude.builder("included")
                    .content(new DesiredIncludeContent.Collected(List.of(
                        DesiredInputEntry.builder("sample-child", "sample")
                            .enabled(false).build())))
                    .build())));

            assertTrue(harness.registry().get("included:sample-child").isPresent());
            assertEquals(List.of("included:sample-child"), harness.registry().list()
                .stream().map(RegistryPluginState::instanceId).toList());
        }
    }

    private static RegistryHarness registry(Path work) throws Exception {
        var source = work.resolve("sample.bin");
        Files.writeString(source, "plugin");
        var runtimeId = new RuntimeId("fake");
        var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(new ArtifactStore(work.resolve("artifacts")))
            .runtimeAdapter(new FakeRuntime(runtimeId)).build();
        engine.start().block();
        var installation = PluginInstallRequest.builder().artifactId(new ArtifactId("sample"))
            .runtimeId(runtimeId).version("1.0.0").source(source).build();
        return new RegistryHarness(engine, new PluginRegistry(engine,
            new InMemoryPluginAuditRepository()), installation);
    }

    private record RegistryHarness(FibraEngine engine, PluginRegistry registry,
                                   PluginInstallRequest installation) implements AutoCloseable {
        void deploy(DesiredInputGraph graph) {
            registry.deploy(new PluginDeploymentRequest(List.of(installation), graph)).block();
        }

        @Override
        public void close() {
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
        public RuntimeResourceOwner create() {
            var definition = PluginDefinition.builder("sample", Void.class,
                () -> (context, config) -> Mono.empty()).build();
            return new RuntimeResourceOwner() {
                private List<ArtifactRecord> active = List.of();
                private RuntimeCatalog catalog = RuntimeCatalog.empty();

                @Override
                public RuntimeResourceUpdate createUpdate(List<ArtifactRecord> target) {
                    var next = List.copyOf(target);
                    var nextCatalog = next.isEmpty() ? RuntimeCatalog.empty()
                        : new RuntimeCatalog(PluginCatalog.of(new PluginCatalogEntry<>(
                            definition, ignored -> null)),
                            Map.of(definition.name(), next.getFirst().id()));
                    return new RuntimeResourceUpdate() {
                        @Override public Mono<Void> prepareAsync() { return Mono.empty(); }
                        @Override public Set<ArtifactId> affectedArtifacts() {
                            return next.stream().map(ArtifactRecord::id)
                                .collect(java.util.stream.Collectors.toSet());
                        }
                        @Override public RuntimeCatalog catalog() { return nextCatalog; }
                        @Override public RuntimeResourceSnapshot snapshot() {
                            return FakeRuntime.this.snapshot(next,
                                RuntimeResourceSnapshot.State.PREPARED);
                        }
                        @Override public void adopt() { active = next; catalog = nextCatalog; }
                        @Override public Mono<Void> closeAsync() { return Mono.empty(); }
                    };
                }

                @Override public RuntimeCatalog catalog() { return catalog; }
                @Override public RuntimeResourceSnapshot snapshot() {
                    return FakeRuntime.this.snapshot(active,
                        RuntimeResourceSnapshot.State.ACTIVE);
                }
                @Override public Mono<Void> closeAsync() { return Mono.empty(); }
            };
        }

        private RuntimeResourceSnapshot snapshot(List<ArtifactRecord> artifacts,
                                                 RuntimeResourceSnapshot.State state) {
            return new RuntimeResourceSnapshot(id, artifacts.stream().map(artifact ->
                RuntimeResourceSnapshot.Resource.builder().artifact(artifact)
                    .identity(artifact.revision()).state(state).build()).toList());
        }
    }

    private static PluginAuditRepository failingAudit(RuntimeException failure) {
        return new PluginAuditRepository() {
            @Override
            public PluginAuditEntry append(String operation, String target,
                                           boolean succeeded, TargetSaveState targetSaveState,
                                           String viewRevision,
                                           String detail) {
                throw failure;
            }

            @Override
            public List<PluginAuditEntry> history() {
                return List.of();
            }
        };
    }

    private static boolean containsThrowable(Throwable failure, Throwable expected) {
        if (failure == expected) {
            return true;
        }
        if (failure.getCause() != null && containsThrowable(failure.getCause(), expected)) {
            return true;
        }
        return java.util.Arrays.stream(failure.getSuppressed())
            .anyMatch(candidate -> containsThrowable(candidate, expected));
    }

    private static final class UnconfirmedSaveStore implements EngineStateStore {
        private com.sstlfsj.fibra.engine.DeploymentManifest manifest;
        private boolean unconfirmed;

        @Override
        public Optional<com.sstlfsj.fibra.engine.DeploymentManifest> load() {
            return Optional.ofNullable(manifest);
        }

        @Override
        public void save(com.sstlfsj.fibra.engine.DeploymentManifest value) {
            if (unconfirmed) {
                throw new EngineStateStore.SaveUnconfirmedException(Path.of("unconfirmed"),
                    new IllegalStateException("state-store confirmation failed"));
            }
            manifest = value;
        }
    }
}
