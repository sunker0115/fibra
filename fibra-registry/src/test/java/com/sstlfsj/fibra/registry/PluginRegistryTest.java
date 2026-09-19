package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.artifact.*;
import com.sstlfsj.fibra.config.*;
import com.sstlfsj.fibra.engine.*;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PluginRegistryTest {
    @TempDir Path root;
    private static final PluginId PLUGIN = new PluginId("sample");
    private static final RuntimeId RUNTIME = new RuntimeId("probe");

    @Test
    void installUsesPackageMetadataAndExplicitGateWhileEntryDisableStaysLocal() throws Exception {
        try (var fixture = fixture()) {
            var source = source("one", "1");
            var installed = fixture.registry.install(new PluginInstallRequest(source, false)).block();
            var selection = installed.selections().get(PLUGIN);
            assertFalse(selection.enabled());
            assertEquals(PluginPackage.read(source).packageDigest(), selection.packageRevision());
            assertEquals(1, installed.target().orElseThrow().targetRevision());

            fixture.registry.upsert(null, entry("first", "main")).block();
            fixture.registry.upsert(null, entry("second", "aux")).block();
            assertTrue(fixture.registry.snapshot().observed().isEmpty());
            var enabled = fixture.registry.enablePackage(PLUGIN).block();
            assertEquals(Set.of("first", "second"), enabled.observed().keySet());
            assertTrue(enabled.observed().values().stream().allMatch(value ->
                value.aggregateState() == ExecutionObservation.State.ACTIVE));

            var disabled = fixture.registry.disable("first").block();
            assertTrue(disabled.selections().get(PLUGIN).enabled());
            assertFalse(disabled.desiredGraph().plugins().get("first").enabled());
            assertTrue(disabled.desiredGraph().plugins().get("second").enabled());
            assertEquals(Set.of("second"), disabled.observed().keySet());

            var gated = fixture.registry.disablePackage(PLUGIN).block();
            assertTrue(gated.observed().isEmpty());
            assertEquals(disabled.desiredGraph(), gated.desiredGraph());
            assertEquals(Set.of("second"), fixture.registry.enablePackage(PLUGIN).block().observed().keySet());
            assertTrue(fixture.registry.history().stream().allMatch(PluginAuditEntry::succeeded));
        }
    }

    @Test
    void upgradePreservesGateRawDesiredAndContextAndSameRevisionIsNoop() throws Exception {
        try (var fixture = fixture()) {
            var first = source("one", "1");
            fixture.registry.install(new PluginInstallRequest(first, false)).block();
            var raw = new DesiredInputGraph(List.of(entry("entry", "main")));
            var context = ConfigContextSnapshot.of(new LiteralValue.ObjectValue(Map.of("host", LiteralValue.of("one"))));
            fixture.registry.deploy(new PluginDeploymentRequest(
                List.copyOf(fixture.registry.snapshot().selections().values()), raw, context)).block();
            var before = fixture.registry.snapshot().target().orElseThrow();

            var same = fixture.registry.upgrade(first).block();
            assertEquals(before.targetRevision(), same.target().orElseThrow().targetRevision());
            assertEquals(TargetSaveState.NOT_APPLICABLE, fixture.registry.history().getLast().targetSaveState());

            var upgraded = fixture.registry.upgrade(source("two", "2")).block();
            assertFalse(upgraded.selections().get(PLUGIN).enabled());
            assertNotEquals(before.selections().get(PLUGIN).packageRevision(),
                upgraded.selections().get(PLUGIN).packageRevision());
            assertEquals(raw, upgraded.desiredGraph());
            assertEquals(context, upgraded.target().orElseThrow().configContext());
            assertEquals(2, fixture.packages.history(PLUGIN).size());
        }
    }

    @Test
    void uninstallRejectsEvenDisabledRawReferencesAndNeverDeletesPublishedContent() throws Exception {
        try (var fixture = fixture()) {
            fixture.registry.install(new PluginInstallRequest(source("one", "1"), false)).block();
            fixture.registry.upsert(null, DesiredInputEntry.builder("entry",
                new PluginDefinitionRef("sample", "gone", "gone")).enabled(false).build()).block();
            var before = fixture.registry.snapshot().target().orElseThrow();
            assertThrows(IllegalArgumentException.class, () -> fixture.registry.uninstall(PLUGIN).block());
            assertEquals(before, fixture.registry.snapshot().target().orElseThrow());
            assertFalse(fixture.registry.history().getLast().succeeded());
            assertEquals(TargetSaveState.NOT_SAVED, fixture.registry.history().getLast().targetSaveState());

            fixture.registry.remove("entry").block();
            assertTrue(fixture.registry.uninstall(PLUGIN).block().selections().isEmpty());
            assertEquals(1, fixture.packages.history(PLUGIN).size());
            assertTrue(fixture.packages.find(PLUGIN, before.selections().get(PLUGIN).packageRevision()).isPresent());
        }
    }

    @Test
    void disabledPackageKeepsDormantDefinitionsButEnableRejectsThemBeforeSave() throws Exception {
        try (var fixture = fixture()) {
            fixture.registry.install(new PluginInstallRequest(source("one", "1"), false)).block();
            fixture.registry.upsert(null, DesiredInputEntry.builder("entry",
                new PluginDefinitionRef("sample", "main", "missing")).build()).block();
            var before = fixture.registry.snapshot().target().orElseThrow();
            var failure = assertThrows(EngineChangeException.class,
                () -> fixture.registry.enablePackage(PLUGIN).block());
            assertEquals(TargetSaveState.NOT_SAVED, failure.targetSaveState());
            assertEquals(before, fixture.registry.snapshot().target().orElseThrow());
            assertTrue(fixture.registry.snapshot().observed().isEmpty());
        }
    }

    @Test
    void installAndUpgradeRequireCorrectSelectionPresence() throws Exception {
        try (var fixture = fixture()) {
            var source = source("one", "1");
            assertThrows(IllegalArgumentException.class, () -> fixture.registry.upgrade(source).block());
            fixture.registry.install(new PluginInstallRequest(source, true)).block();
            assertThrows(IllegalArgumentException.class,
                () -> fixture.registry.install(new PluginInstallRequest(source, false)).block());
            assertTrue(fixture.registry.snapshot().selections().get(PLUGIN).enabled());
            assertEquals(1, fixture.packages.history(PLUGIN).size());
        }
    }

    @Test
    void fullDeploymentCarriesSelectionsGraphAndContextInOneTargetTransaction() throws Exception {
        try (var fixture = fixture()) {
            var published = fixture.publish(source("one", "1"));
            assertTrue(fixture.registry.snapshot().target().isEmpty(), "publishing alone never selects a package");
            var graph = new DesiredInputGraph(List.of(entry("entry", "main")));
            var context = ConfigContextSnapshot.of(new LiteralValue.ObjectValue(Map.of("region", LiteralValue.of("test"))));
            var deployed = fixture.registry.deploy(new PluginDeploymentRequest(
                List.of(new PluginSelection(PLUGIN, published.packageRevision(), true)), graph, context)).block();
            assertEquals(1, deployed.target().orElseThrow().targetRevision());
            assertEquals(context, deployed.target().orElseThrow().configContext());
            assertEquals(graph, deployed.desiredGraph());
            assertEquals(1, fixture.registry.history().size());
            assertEquals("deploy", fixture.registry.history().getFirst().operation());

            var empty = fixture.registry.deploy(new PluginDeploymentRequest(
                List.of(), new DesiredInputGraph(List.of()), ConfigContextSnapshot.empty())).block();
            assertTrue(empty.selections().isEmpty());
            assertTrue(empty.desiredGraph().plugins().isEmpty());
            assertTrue(empty.observed().isEmpty());
            assertEquals(1, fixture.packages.history(PLUGIN).size());
        }
    }

    @Test
    void groupOperationsPreserveLocalFlagsAndCompleteEntryData() throws Exception {
        try (var fixture = fixture()) {
            var published = fixture.publish(source("one", "1"));
            var entry = DesiredInputEntry.builder("entry", new PluginDefinitionRef("sample", "main", "run"))
                .config(new LiteralValue.ObjectValue(Map.of("value", LiteralValue.of(1))))
                .publicationRequirement(PublicationRequirement.PENDING_ALLOWED).build();
            var group = DesiredInputGroup.builder("group").children(List.of(entry)).build();
            fixture.registry.deploy(new PluginDeploymentRequest(
                List.of(new PluginSelection(PLUGIN, published.packageRevision(), true)),
                new DesiredInputGraph(List.of(group, DesiredInputGroup.builder("other").build())),
                ConfigContextSnapshot.empty())).block();
            fixture.registry.disable("group").block();
            assertTrue(fixture.registry.snapshot().desiredGraph().plugins().get("entry").enabled());
            fixture.registry.enable("group").block();
            fixture.registry.move("entry", "other", 0).block();
            assertEquals(entry, fixture.registry.get("entry").orElseThrow().desired());
            fixture.registry.disable("entry").block();
            fixture.registry.enable("entry").block();
            assertEquals(entry, fixture.registry.get("entry").orElseThrow().desired());
            fixture.registry.upsert("other", entry("second", "aux")).block();
            assertEquals(List.of("entry", "second"), fixture.registry.list().stream().map(RegistryPluginState::entryId).toList());
            fixture.registry.remove("other").block();
            assertTrue(fixture.registry.list().isEmpty());
        }
    }

    @Test
    void includesUseCompleteDesiredEntryIdsForLookupAndMutation() throws Exception {
        try (var fixture = fixture()) {
            var published = fixture.publish(source("one", "1"));
            var include = DesiredInputInclude.builder("included").content(new DesiredIncludeContent.Collected(
                List.of(entry("entry", "main")))).build();
            fixture.registry.deploy(new PluginDeploymentRequest(
                List.of(new PluginSelection(PLUGIN, published.packageRevision(), true)),
                new DesiredInputGraph(List.of(include)), ConfigContextSnapshot.empty())).block();
            assertTrue(fixture.registry.get("entry").isEmpty());
            assertNotNull(fixture.registry.get("included:entry").orElseThrow().observed());
            fixture.registry.disable("included:entry").block();
            assertFalse(fixture.registry.get("included:entry").orElseThrow().desired().enabled());
            fixture.registry.remove("included:entry").block();
            assertTrue(fixture.registry.list().isEmpty());
        }
    }

    @Test
    void acceptedStartFailureIsAuditedSeparatelyFromObservedAndReconcileNeverSavesTarget() throws Exception {
        try (var fixture = fixture()) {
            fixture.registry.install(new PluginInstallRequest(source("one", "1"), true)).block();
            fixture.provider.activationFailure = true;
            var failed = fixture.registry.upsert(null, entry("entry", "main")).block();
            assertEquals(ExecutionObservation.State.FAILED, failed.observed().get("entry").aggregateState());
            assertFalse(failed.engineDiagnostics().targetSatisfied());
            assertTrue(fixture.registry.history().getLast().succeeded());
            assertEquals(TargetSaveState.SAVED, fixture.registry.history().getLast().targetSaveState());
            var revision = failed.target().orElseThrow().targetRevision();
            fixture.provider.activationFailure = false;
            var reconciled = fixture.registry.reconcileCurrent().block();
            assertEquals(revision, reconciled.target().orElseThrow().targetRevision());
            assertEquals(ExecutionObservation.State.ACTIVE, reconciled.observed().get("entry").aggregateState());
            assertEquals(TargetSaveState.NOT_APPLICABLE, fixture.registry.history().getLast().targetSaveState());
            var identity = reconciled.observed().get("entry").executions().getFirst().runtimeInstanceId();
            var noop = fixture.registry.reconcileCurrent().block();
            assertEquals(identity, noop.observed().get("entry").executions().getFirst().runtimeInstanceId());
        }
    }

    @Test
    void explicitSaveFailureKeepsThePublishedPackageButNotTheSelection() throws Exception {
        var targets = new FailingStore();
        try (var fixture = new Fixture(targets, new InMemoryPluginAuditRepository())) {
            targets.failure = TargetSaveState.NOT_SAVED;
            assertThrows(EngineChangeException.class,
                () -> fixture.registry.install(new PluginInstallRequest(source("one", "1"), false)).block());
            assertTrue(fixture.registry.snapshot().target().isEmpty());
            assertEquals(1, fixture.packages.history(PLUGIN).size());
            assertEquals(TargetSaveState.NOT_SAVED, fixture.registry.history().getLast().targetSaveState());
            targets.failure = null;
            fixture.registry.install(new PluginInstallRequest(source("retry", "1"), false)).block();
            assertEquals(1, fixture.packages.history(PLUGIN).size());
        }
    }

    @Test
    void uncertainSaveIsAuditedWithoutGuessingOrRetrying() throws Exception {
        var targets = new FailingStore();
        try (var fixture = new Fixture(targets, new InMemoryPluginAuditRepository())) {
            targets.failure = TargetSaveState.UNCONFIRMED;
            var failure = assertThrows(EngineChangeException.class,
                () -> fixture.registry.install(new PluginInstallRequest(source("one", "1"), false)).block());
            assertEquals(TargetSaveState.UNCONFIRMED, failure.targetSaveState());
            assertEquals(DurableTargetState.UNCERTAIN, fixture.registry.snapshot().engine().durableState());
            assertEquals(TargetSaveState.UNCONFIRMED, fixture.registry.history().getLast().targetSaveState());
            assertFalse(fixture.registry.history().getLast().succeeded());
            assertEquals(1, targets.saves);
        }
    }

    @Test
    void auditDeliveryFailureIsObservableAndDoesNotReplaceSuccessOrEngineFailure() throws Exception {
        var targets = new FailingStore();
        var audit = new PluginAuditRepository() {
            public PluginAuditEntry append(String operation, String target, boolean succeeded,
                TargetSaveState state, String revision, String detail) { throw new IllegalStateException("audit offline"); }
            public List<PluginAuditEntry> history() { return List.of(); }
        };
        try (var fixture = new Fixture(targets, audit)) {
            var installed = fixture.registry.install(new PluginInstallRequest(source("one", "1"), false)).block();
            assertEquals(1, installed.auditFailures().size());
            assertTrue(installed.auditFailures().getFirst().succeeded());
            targets.failure = TargetSaveState.NOT_SAVED;
            var failure = assertThrows(EngineChangeException.class,
                () -> fixture.registry.enablePackage(PLUGIN).block());
            assertEquals(TargetSaveState.NOT_SAVED, failure.targetSaveState());
            assertEquals(2, fixture.registry.auditFailures().size());
            assertFalse(fixture.registry.auditFailures().getLast().succeeded());
            assertTrue(fixture.registry.auditFailures().getLast().detail().contains("audit offline"));
        }
    }

    @Test
    void watchProjectsEngineFactsWithoutOwningASecondStateMachine() throws Exception {
        try (var fixture = fixture()) {
            var seen = new java.util.concurrent.CopyOnWriteArrayList<RegistrySnapshot>();
            var subscription = fixture.registry.watch().subscribe(seen::add);
            try {
                var installed = fixture.registry.install(new PluginInstallRequest(source("one", "1"), false)).block();
                assertFalse(seen.isEmpty());
                assertEquals(installed.viewRevision(), seen.getLast().viewRevision());
                assertEquals(fixture.engine.snapshot(), fixture.registry.snapshot().engine());
            } finally { subscription.dispose(); }
        }
    }

    private Fixture fixture() { return new Fixture(DeploymentTargetStore.inMemory(), new InMemoryPluginAuditRepository()); }

    private Path source(String directory, String version) throws Exception {
        var source = Files.createDirectory(root.resolve(directory));
        Files.writeString(source.resolve("main.bin"), "main-" + version);
        Files.writeString(source.resolve("aux.bin"), "aux-" + version);
        Files.writeString(source.resolve(PluginPackage.MANIFEST), """
            format: 1
            id: sample
            version: "%s"
            facets:
              - id: main
                role: host
                runtime: probe
                target: host
                payload: main.bin
                dependencies: []
                capabilities: []
              - id: aux
                role: host
                runtime: probe
                target: host
                payload: aux.bin
                dependencies: []
                capabilities: []
            """.formatted(version));
        return source;
    }

    private static DesiredInputEntry entry(String id, String facet) {
        return DesiredInputEntry.builder(id, new PluginDefinitionRef("sample", facet, "run")).build();
    }

    private final class Fixture implements AutoCloseable {
        final PluginPackageStore packages = new PluginPackageStore(root.resolve("packages"));
        final ProbeProvider provider = new ProbeProvider();
        final FibraEngine engine;
        final PluginRegistry registry;
        Fixture(DeploymentTargetStore targets, PluginAuditRepository audit) {
            engine = FibraEngine.builder(packages, targets).runtimeProvider(provider)
                .hostTerminationPort(request -> { }).lifecycleTimeout(Duration.ofSeconds(2)).build();
            engine.startAsync().block();
            registry = new PluginRegistry(engine, packages, audit);
        }
        PluginPackageRecord publish(Path source) {
            try (var transaction = packages.prepareInstall(source)) { return transaction.save(); }
        }
        public void close() { engine.close(); }
    }

    private static final class FailingStore implements DeploymentTargetStore {
        final DeploymentTargetStore delegate = DeploymentTargetStore.inMemory();
        TargetSaveState failure;
        int saves;
        public Optional<StoredTarget> load() { return delegate.load(); }
        public DurableTargetToken save(long expected, DeploymentTarget target) {
            saves++;
            if (failure == TargetSaveState.NOT_SAVED) throw new IllegalStateException("save failed");
            var token = delegate.save(expected, target);
            if (failure == TargetSaveState.UNCONFIRMED) throw new SaveUnconfirmedException(Path.of("target.json"),
                new java.io.IOException("directory fsync failed"));
            return token;
        }
    }

    private static final class ProbeProvider implements RuntimeProvider {
        boolean activationFailure;
        public RuntimeId id() { return RUNTIME; }
        public String contractIdentity() { return "registry-probe-v1"; }
        public List<BuiltInPluginPackage> builtInPackages() { return List.of(); }
        public RuntimeDriver create(RuntimeHostServices services) {
            return new RuntimeDriver() {
                public RuntimeId id() { return RUNTIME; }
                public Mono<RuntimeArtifactInspection> probe(PluginFacetSource source) { return Mono.error(new UnsupportedOperationException()); }
                public Mono<RuntimeArtifactInspection> inspect(ManagedFacet facet) { return Mono.error(new UnsupportedOperationException()); }
                public RuntimeDriverSnapshot snapshot() { return new RuntimeDriverSnapshot(RUNTIME, Map.of()); }
                public Mono<Void> closeAsync() { return Mono.empty(); }
                public RuntimeCandidate createCandidate(RuntimeTargetSlice slice) {
                    return new RuntimeCandidate() {
                        RuntimePlan plan;
                        public Mono<Void> prepareAsync() {
                            return Mono.fromRunnable(() -> {
                                var units = new ArrayList<ExecutionUnitPlan>();
                                var bindings = new ArrayList<DefinitionBindingPlan>();
                                for (var id : new TreeSet<>(slice.affectedEntryIds())) {
                                    var entry = (DesiredInputEntry) slice.desired().require(id).input();
                                    if (!entry.definitionRef().definitionId().equals("run")) throw new IllegalArgumentException("definition does not exist");
                                    var facet = slice.facets().stream().filter(value ->
                                        value.facet().facet().facetId().value().equals(entry.definitionRef().facetId())).findFirst().orElseThrow();
                                    var key = new ExecutionUnitKey(id);
                                    units.add(ExecutionUnitPlan.builder(key, RUNTIME, new ExecutionTarget("host"))
                                        .artifactId(facet.facet().artifactId()).provenance("sample",
                                            entry.definitionRef().facetId(), slice.target().selections().get(PLUGIN).packageRevision())
                                        .dependencies(slice.unitDependencies().get(key)).build());
                                    bindings.add(DefinitionBindingPlan.builder(entry.definitionRef(), id).unitKey(key)
                                        .publicationRequirement(entry.publicationRequirement()).build());
                                }
                                plan = RuntimePlan.of(RUNTIME, units, bindings);
                            });
                        }
                        public RuntimePlan preparedPlan() { return plan; }
                        public PreparedRuntimeGeneration seal(CompiledRuntimeSlice compiled) {
                            var units = new LinkedHashMap<ExecutionUnitKey, RuntimeUnitGeneration>();
                            plan.units().forEach((key, value) -> units.put(key, new ProbeUnit(value,
                                slice.target().targetRevision(), services.nextIdentity("unit"))));
                            return new PreparedRuntimeGeneration() {
                                public Map<ExecutionUnitKey, RuntimeUnitGeneration> units() { return units; }
                                public Mono<Void> abortAsync() { return Mono.empty(); }
                                public Mono<Void> retireAsync() { return Mono.empty(); }
                            };
                        }
                        public Mono<Void> closeAsync() { return Mono.empty(); }
                    };
                }
            };
        }
        private final class ProbeUnit implements RuntimeUnitGeneration {
            final ExecutionUnitPlan plan;
            final long revision;
            final String instance;
            ExecutionObservation observation;
            ProbeUnit(ExecutionUnitPlan plan, long revision, String instance) {
                this.plan = plan; this.revision = revision; this.instance = instance;
                observation = observe("prepared", ExecutionObservation.State.PENDING);
            }
            public ExecutionUnitPlan plan() { return plan; }
            public Mono<ExecutionObservation> reconcileAsync(String operation) {
                return Mono.fromSupplier(() -> observation = observe(operation,
                    activationFailure ? ExecutionObservation.State.FAILED : ExecutionObservation.State.ACTIVE));
            }
            public void closeAdmission() { }
            public Mono<ExecutionObservation> drainAsync(String operation, Instant deadline) {
                return Mono.just(observation);
            }
            public Mono<ExecutionObservation> stopAsync(String operation, Instant deadline) {
                return Mono.fromSupplier(() -> observation = observe(operation, ExecutionObservation.State.PENDING));
            }
            public ExecutionObservation snapshot() { return observation; }
            private ExecutionObservation observe(String operation, ExecutionObservation.State state) {
                var detail = ExecutionObservation.Detail.builder().unitTargetRevision(revision)
                    .executionId(plan.key().value()).runtimeInstanceId(instance).lifecycleOperationId(operation).state(state);
                if (state == ExecutionObservation.State.FAILED) detail.failure(new ExecutionObservation.Failure("START_FAILED", "start failed", Map.of()));
                return ExecutionObservation.of(plan.pluginId(), plan.facetId(), RUNTIME, plan.executionTarget(), List.of(detail.build()));
            }
        }
    }
}
