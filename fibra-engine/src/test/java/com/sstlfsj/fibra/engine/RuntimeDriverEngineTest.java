package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.engine.deployment.DeploymentTargetStoreFixture;

import com.sstlfsj.fibra.engine.deployment.BuiltInFacet;
import com.sstlfsj.fibra.engine.deployment.DefinitionCatalogException;
import com.sstlfsj.fibra.engine.deployment.PackageDefinitionCatalog;
import com.sstlfsj.fibra.engine.deployment.PluginSelection;
import com.sstlfsj.fibra.engine.deployment.BuiltInPluginPackage;
import com.sstlfsj.fibra.engine.deployment.DeploymentTarget;
import com.sstlfsj.fibra.engine.deployment.DeploymentTargetStore;
import com.sstlfsj.fibra.engine.deployment.DeploymentTargetStoreException;
import com.sstlfsj.fibra.engine.deployment.DurableTargetToken;
import com.sstlfsj.fibra.engine.deployment.FileDeploymentTargetStore;
import com.sstlfsj.fibra.engine.deployment.HostCapabilitySnapshot;
import com.sstlfsj.fibra.engine.deployment.PluginFacetSource;
import com.sstlfsj.fibra.engine.execution.CompiledRuntimeSlice;
import com.sstlfsj.fibra.engine.execution.DefinitionBindingPlan;
import com.sstlfsj.fibra.engine.execution.ExecutionUnitKey;
import com.sstlfsj.fibra.engine.execution.ExecutionUnitPlan;
import com.sstlfsj.fibra.engine.execution.RuntimePlan;
import com.sstlfsj.fibra.engine.execution.RuntimeUnitFence;
import com.sstlfsj.fibra.engine.observation.CandidatePhase;
import com.sstlfsj.fibra.engine.observation.CurrentAttemptSnapshot;
import com.sstlfsj.fibra.engine.observation.CurrentPhase;
import com.sstlfsj.fibra.engine.observation.DurableTargetState;
import com.sstlfsj.fibra.engine.observation.EngineOperationKind;
import com.sstlfsj.fibra.engine.observation.EngineOperationOutcome;
import com.sstlfsj.fibra.engine.observation.EngineOperationStage;
import com.sstlfsj.fibra.engine.observation.EngineSnapshot;
import com.sstlfsj.fibra.engine.observation.EngineState;
import com.sstlfsj.fibra.engine.observation.ExecutionObservation;
import com.sstlfsj.fibra.engine.observation.FailureStage;
import com.sstlfsj.fibra.engine.observation.FailureSubject;
import com.sstlfsj.fibra.engine.observation.RetirementPhase;
import com.sstlfsj.fibra.engine.observation.RuntimeDriverSnapshot;
import com.sstlfsj.fibra.engine.observation.TargetConvergence;
import com.sstlfsj.fibra.engine.observation.TargetSaveState;
import com.sstlfsj.fibra.engine.publication.PublishedView;
import com.sstlfsj.fibra.engine.runtime.PreparedRuntimeGeneration;
import com.sstlfsj.fibra.engine.runtime.RuntimeArtifactInspection;
import com.sstlfsj.fibra.engine.runtime.RuntimeCandidate;
import com.sstlfsj.fibra.engine.runtime.RuntimeDriver;
import com.sstlfsj.fibra.engine.runtime.RuntimeDefinitionQuery;
import com.sstlfsj.fibra.engine.runtime.RuntimeHostServices;
import com.sstlfsj.fibra.engine.runtime.RuntimeProvider;
import com.sstlfsj.fibra.engine.runtime.RuntimeRecompileReason;
import com.sstlfsj.fibra.engine.runtime.RuntimeTargetSlice;
import com.sstlfsj.fibra.engine.runtime.RuntimeUnitDisableRequest;
import com.sstlfsj.fibra.engine.runtime.RuntimeUnitGeneration;

import com.sstlfsj.fibra.artifact.*;
import com.sstlfsj.fibra.config.*;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeDriverEngineTest {
    @TempDir Path root;
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final Store store = new Store();
    private final ProbeProvider alpha = new ProbeProvider("alpha", "a");
    private final ProbeProvider beta = new ProbeProvider("beta", "b");

    @Test
    void catalogReadsDisabledBuiltInDeclarationsWithoutDynamicSpiOrMutation() {
        try (var engine = engine(request -> fail("unexpected termination"))) {
            engine.submit(ApplyDeployment.builder(graph(1)).expectedRevision(0)
                .selections(List.of(alpha.metadata().selection(false)))
                .configContext(ConfigContextSnapshot.empty()).build()).block();
            var before = engine.published().current();
            int saves = store.saves;
            events.clear();
            var publisher = catalog(engine, "a", 1);
            assertTrue(events.isEmpty());
            var first = publisher.block();
            assertEquals(List.of("definition"), first.facets().getFirst().definitionIds());
            assertEquals(alpha.metadata().packageDigest(), first.packageRevision());
            assertEquals(first, publisher.block());
            assertEquals(before, engine.published().current());
            assertEquals(saves, store.saves);
            assertTrue(events.isEmpty(), "built-in metadata must not invoke dynamic SPI");
            assertCatalogCode(DefinitionCatalogException.Code.STALE_TARGET, catalog(engine, "missing", 0));
            assertCatalogCode(DefinitionCatalogException.Code.NOT_SELECTED, catalog(engine, "missing", 1));
        }
    }

    private static Mono<PackageDefinitionCatalog> catalog(FibraEngine engine, String plugin, long revision) {
        return engine.describeDefinitions(new PluginId(plugin), revision);
    }

    private static DefinitionCatalogException assertCatalogCode(DefinitionCatalogException.Code code,
                                                                 Mono<?> publisher) {
        var completion = assertThrows(CompletionException.class, () -> publisher.toFuture().join());
        var error = assertInstanceOf(DefinitionCatalogException.class, completion.getCause());
        assertEquals(code, error.code());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        return error;
    }

    @Test
    void catalogOwnsAllHandlesBeforeSerialReadsAndReverseCloseAndWaitsForCleanup() throws Exception {
        try (var packages = new PluginPackageStore(root.resolve("catalog-packages"))) {
            var record = catalogPackage(packages, "sample", "1", "z:beta", "a:alpha");
            var a = new CatalogProbe("a", List.of("zeta", "same", "alpha"));
            var z = new CatalogProbe("z", List.of("same"));
            a.readRelease = Sinks.one();
            z.closeRelease = Sinks.one();
            alpha.queryFactory = facet -> a;
            beta.queryFactory = facet -> z;
            try (var engine = catalogEngine(packages, List.of(selection(record, false)))) {
                var before = engine.published().current();
                events.clear();
                var publisher = catalog(engine, "sample", 1);
                assertTrue(events.isEmpty(), "cold publisher must not create a handle");
                var result = publisher.toFuture();
                a.readEntered.asMono().block(Duration.ofSeconds(2));
                assertEquals(List.of("query-create:a", "query-create:z", "query-read:a"), events);
                assertFalse(result.isDone());
                a.readRelease.tryEmitEmpty();
                z.closeEntered.asMono().block(Duration.ofSeconds(2));
                assertEquals(List.of("query-create:a", "query-create:z", "query-read:a",
                    "query-read:z", "query-close:z"), events);
                assertFalse(result.isDone(), "result must wait for actual close completion");
                z.closeRelease.tryEmitEmpty();
                var catalog = result.get(2, TimeUnit.SECONDS);
                assertEquals(List.of("a", "z"), catalog.facets().stream().map(f -> f.facetId().value()).toList());
                assertEquals(List.of("alpha", "same", "zeta"), catalog.facets().getFirst().definitionIds());
                assertThrows(UnsupportedOperationException.class, () -> catalog.facets().clear());
                assertThrows(UnsupportedOperationException.class, () -> catalog.facets().getFirst().definitionIds().clear());
                assertEquals("query-close:a", events.getLast());
                assertEquals(before, engine.published().current());
                assertEquals(0, store.saves);
            }
        }
    }

    @Test
    void catalogRepeatSubscriptionsCreateIndependentAttemptsAndAcceptEmptyFacet() throws Exception {
        try (var packages = new PluginPackageStore(root.resolve("repeat-packages"))) {
            var record = catalogPackage(packages, "sample", "1", "main:alpha");
            var handles = new ArrayList<CatalogProbe>();
            alpha.queryFactory = facet -> {
                var query = new CatalogProbe("main", List.of());
                handles.add(query);
                return query;
            };
            try (var engine = catalogEngine(packages, List.of(selection(record, false)))) {
                events.clear();
                var publisher = catalog(engine, "sample", 1);
                assertEquals(List.of(), publisher.block().facets().getFirst().definitionIds());
                assertEquals(List.of(), publisher.block().facets().getFirst().definitionIds());
                assertEquals(2, handles.size());
                assertNotSame(handles.getFirst(), handles.getLast());
                assertEquals(1, handles.getFirst().closes.get());
                assertEquals(1, handles.getLast().closes.get());
            }
        }
    }

    @Test
    void canceledCatalogObserverCannotCancelReadsOrLetCloseOvertakeAcceptedCommand() throws Exception {
        var packages = new PluginPackageStore(root.resolve("cancel-packages"));
        var record = catalogPackage(packages, "sample", "1", "main:alpha");
        var query = new CatalogProbe("main", List.of("entry"));
        query.readRelease = Sinks.one();
        query.closeRelease = Sinks.one();
        alpha.queryFactory = facet -> query;
        var engine = catalogEngine(packages, List.of(selection(record, false)));
        try {
            events.clear();
            var observer = catalog(engine, "sample", 1).toFuture();
            query.readEntered.asMono().block(Duration.ofSeconds(2));
            observer.cancel(true);
            var closing = engine.closeAsync().toFuture();
            assertCatalogCode(DefinitionCatalogException.Code.ENGINE_UNAVAILABLE, catalog(engine, "sample", 1));
            assertFalse(closing.isDone());
            assertFalse(query.readCancelled.get());
            assertFalse(events.contains("driver-close:alpha"));
            query.readRelease.tryEmitEmpty();
            query.closeEntered.asMono().block(Duration.ofSeconds(2));
            assertFalse(closing.isDone());
            assertFalse(events.contains("driver-close:alpha"));
            query.closeRelease.tryEmitEmpty();
            closing.get(2, TimeUnit.SECONDS);
            assertFalse(query.readCancelled.get());
            assertEquals(1, query.closes.get());
            assertTrue(events.indexOf("query-close:main") < events.indexOf("driver-close:alpha"));
        } finally {
            query.readRelease.tryEmitEmpty();
            query.closeRelease.tryEmitEmpty();
            engine.close();
        }
    }

    @Test
    void catalogRejectsNewEngineAndUsesRevisionPrecedenceForAbsentTarget() {
        var engine = FibraEngine.builder(new PluginPackageStore(root.resolve("new-catalog")), store)
            .runtimeProvider(alpha).hostTerminationPort(request -> { }).build();
        try {
            assertCatalogCode(DefinitionCatalogException.Code.ENGINE_UNAVAILABLE, catalog(engine, "a", 0));
            engine.startAsync().block();
            assertCatalogCode(DefinitionCatalogException.Code.STALE_TARGET, catalog(engine, "a", 1));
            assertCatalogCode(DefinitionCatalogException.Code.NOT_SELECTED, catalog(engine, "a", 0));
        } finally { engine.close(); }
    }

    @Test
    void catalogPreflightsAllRootRuntimesBeforeCreatingAnyQuery() throws Exception {
        try (var packages = new PluginPackageStore(root.resolve("unsupported-packages"))) {
            var record = catalogPackage(packages, "sample", "1", "a:alpha", "z:absent");
            alpha.queryFactory = facet -> fail("preflight must precede all SPI calls");
            try (var engine = catalogEngine(packages, List.of(selection(record, false)))) {
                events.clear();
                assertCatalogCode(DefinitionCatalogException.Code.UNSUPPORTED_RUNTIME, catalog(engine, "sample", 1));
                assertTrue(events.isEmpty());
                assertTrue(engine.published().current().engineDiagnostics().mutationGateOpen());
            }
        }
    }

    @Test
    void catalogCleansRegisteredHandlesAfterCreateFailureAndNormalReadFailureKeepsGateOpen() throws Exception {
        try (var packages = new PluginPackageStore(root.resolve("failure-packages"))) {
            var record = catalogPackage(packages, "sample", "1", "a:alpha", "z:beta");
            var first = new CatalogProbe("a", List.of("entry"));
            alpha.queryFactory = facet -> first;
            beta.queryFactory = facet -> { throw new AssertionError("foreign create failure"); };
            try (var engine = catalogEngine(packages, List.of(selection(record, false)))) {
                var before = engine.published().current();
                events.clear();
                assertCatalogCode(DefinitionCatalogException.Code.READ_FAILED, catalog(engine, "sample", 1));
                assertEquals(List.of("query-create:a", "query-create:z", "query-close:a"), events);
                assertEquals(1, first.closes.get());
                assertEquals(before, engine.published().current());
                alpha.queryFactory = facet -> new CatalogProbe("a", List.of("entry"));
                beta.queryFactory = facet -> new CatalogProbe("z", List.of("entry"));
                assertEquals(2, catalog(engine, "sample", 1).block().facets().size());
                assertTrue(engine.published().current().engineDiagnostics().mutationGateOpen());
            }
        }
    }

    @Test
    void catalogRejectsMalformedAndAbsentReadResultsAndCleansEachHandle() throws Exception {
        try (var packages = new PluginPackageStore(root.resolve("malformed-packages"))) {
            var record = catalogPackage(packages, "sample", "1", "main:alpha");
            try (var engine = catalogEngine(packages, List.of(selection(record, false)))) {
                List<Supplier<Mono<List<String>>>> malformed = List.of(
                    () -> null, Mono::empty, () -> { throw new LinkageError("sync failure"); },
                    () -> { throw new HostileCatalogRuntimeException(); }, () -> Mono.just(null),
                    () -> Mono.error(new HostileCatalogError()),
                    () -> Mono.just(List.of(" ")), () -> Mono.just(List.of("same", "same")),
                    () -> Mono.just(Arrays.asList("valid", null)));
                for (var read : malformed) {
                    var query = new CatalogProbe("main", List.of("entry"));
                    query.read = read;
                    alpha.queryFactory = facet -> query;
                    assertCatalogCode(DefinitionCatalogException.Code.READ_FAILED, catalog(engine, "sample", 1));
                    assertEquals(1, query.closes.get());
                    assertTrue(engine.published().current().engineDiagnostics().mutationGateOpen());
                }
                alpha.queryFactory = facet -> null;
                assertCatalogCode(DefinitionCatalogException.Code.READ_FAILED, catalog(engine, "sample", 1));
            }
        }
    }

    @Test
    void catalogCleanupFailureWinsRetainsOwnerAndStopsShutdownBeforeDependentContexts() throws Exception {
        var packages = new PluginPackageStore(root.resolve("cleanup-packages"));
        var record = catalogPackage(packages, "sample", "1", "a:alpha", "z:beta");
        var first = new CatalogProbe("a", List.of("entry"));
        var last = new CatalogProbe("z", List.of("entry"));
        first.read = () -> Mono.error(new HostileCatalogError());
        last.close = () -> { throw new HostileCatalogError(); };
        alpha.queryFactory = facet -> first;
        beta.queryFactory = facet -> last;
        var termination = Sinks.<com.sstlfsj.fibra.engine.observation.HostTerminationRequest>one();
        var requests = new AtomicInteger();
        var engine = catalogEngine(packages, List.of(selection(record, false)), request -> {
            requests.incrementAndGet(); termination.tryEmitValue(request);
        });
        engine.submit(ApplyDeployment.builder(graph(1, "a1")).expectedRevision(1)
            .selections(List.of(alpha.metadata().selection(true), selection(record, false)))
            .configContext(ConfigContextSnapshot.empty()).build()).block();
        var operation = engine.published().current().engineDiagnostics().operation();
        var current = engine.snapshot().current();
        events.clear();
        alpha.closeAdmissionFailure = new HostileCatalogRuntimeException();
        try {
            var error = assertCatalogCode(DefinitionCatalogException.Code.CLEANUP_FAILED, catalog(engine, "sample", 2));
            assertEquals(List.of("query-create:a", "query-create:z", "query-read:a", "query-close:z", "query-close:a",
                "admission:a1"), events);
            assertEquals(EngineState.FAIL_STOP, engine.snapshot().state());
            assertEquals(operation, engine.published().current().engineDiagnostics().operation());
            assertEquals(current.get().attemptId(), engine.snapshot().current().orElseThrow().attemptId());
            var fact = engine.published().current().engineDiagnostics().failure().orElseThrow();
            assertEquals("CATALOG_CLEANUP_FAILED", fact.reason());
            assertEquals(new FailureSubject.Engine(engine.snapshot().hostInstanceId()), fact.subject());
            assertEquals(FailureStage.RELEASING, fact.stage());
            assertTrue(fact.operationStage().isEmpty());
            assertEquals(2, fact.targetRevision().orElseThrow());
            assertEquals(List.of("definition catalog fail-stop could not close unit admission"),
                engine.published().current().engineDiagnostics().cleanupFailures());
            var request = termination.asMono().block(Duration.ofSeconds(2));
            assertEquals(fact.subject(), request.subject());
            assertEquals(1, requests.get());
            assertCatalogCode(DefinitionCatalogException.Code.ENGINE_UNAVAILABLE, catalog(engine, "sample", 2));
            assertSame(error, assertCatalogCode(DefinitionCatalogException.Code.CLEANUP_FAILED, engine.closeAsync()));
            assertSame(error, assertCatalogCode(DefinitionCatalogException.Code.CLEANUP_FAILED, engine.closeAsync()));
            assertEquals(operation, engine.published().current().engineDiagnostics().operation());
            assertEquals(1, first.closes.get());
            assertEquals(1, last.closes.get());
            assertEquals(0, store.closes);
            assertFalse(events.stream().anyMatch(value -> value.startsWith("driver-close:")));
            assertTrue(packages.find(record.pluginId(), record.packageRevision()).isPresent());
            var field = FibraEngine.class.getDeclaredField("catalogAttempt");
            field.setAccessible(true);
            var attempt = field.get(engine);
            assertNotNull(attempt);
            assertTrue(Arrays.stream(attempt.getClass().getDeclaredFields()).anyMatch(member -> {
                try { member.setAccessible(true); var value = member.get(attempt);
                    return value instanceof Collection<?> owners && owners.contains(last) && !owners.contains(first);
                } catch (IllegalAccessException failure) { throw new AssertionError(failure); }
            }), "only failed query owner must remain reachable");
        } finally { packages.close(); }
    }

    @Test
    void catalogNullThrowingAndAsyncFailedClosersAreCachedAndBlockQueuedQueries() throws Exception {
        List<Supplier<Mono<Void>>> failures = List.of(() -> null,
            () -> { throw new LinkageError("foreign close"); },
            () -> { throw new HostileCatalogRuntimeException(); },
            () -> Mono.error(new HostileCatalogError()));
        for (var closer : failures) {
            var packages = new PluginPackageStore(root.resolve(UUID.randomUUID().toString()));
            var record = catalogPackage(packages, "sample", "1", "main:alpha");
            var query = new CatalogProbe("main", List.of("entry"));
            query.readRelease = Sinks.one();
            query.close = closer;
            alpha.queryFactory = facet -> query;
            store.reopen();
            var engine = catalogEngine(packages, List.of(selection(record, false), alpha.metadata().selection(false)),
                request -> { });
            try {
                events.clear();
                var failed = catalog(engine, "sample", 1).toFuture();
                query.readEntered.asMono().block(Duration.ofSeconds(2));
                var queued = catalog(engine, "a", 1).toFuture();
                query.readRelease.tryEmitEmpty();
                var completion = assertThrows(CompletionException.class, failed::join);
                var error = assertInstanceOf(DefinitionCatalogException.class, completion.getCause());
                assertEquals(DefinitionCatalogException.Code.CLEANUP_FAILED, error.code());
                assertNull(error.getCause());
                assertEquals(0, error.getSuppressed().length);
                var queuedFailure = assertThrows(CompletionException.class, queued::join);
                assertEquals(DefinitionCatalogException.Code.ENGINE_UNAVAILABLE,
                    assertInstanceOf(DefinitionCatalogException.class, queuedFailure.getCause()).code());
                assertSame(error, assertCatalogCode(DefinitionCatalogException.Code.CLEANUP_FAILED, engine.closeAsync()));
                assertSame(error, assertCatalogCode(DefinitionCatalogException.Code.CLEANUP_FAILED, engine.closeAsync()));
                assertEquals(1, query.closes.get());
                assertFalse(events.stream().anyMatch(value -> value.startsWith("driver-close:")));
            } finally { packages.close(); }
        }
    }

    @Test
    void catalogLeavesRealTargetBytesAndPublishedRevisionUntouched() throws Exception {
        var targetsPath = root.resolve("file-target");
        try (var packages = new PluginPackageStore(root.resolve("file-packages"));
             var targets = new FileDeploymentTargetStore(targetsPath)) {
            var record = catalogPackage(packages, "sample", "1", "main:alpha");
            alpha.queryFactory = facet -> new CatalogProbe("main", List.of("entry"));
            try (var engine = FibraEngine.builder(packages, targets).runtimeProvider(alpha)
                .hostTerminationPort(request -> fail("unexpected termination")).build()) {
                engine.startAsync().block();
                engine.submit(ApplyDeployment.builder(graph(1)).expectedRevision(0)
                    .selections(List.of(selection(record, false))).configContext(ConfigContextSnapshot.empty()).build()).block();
                var before = engine.published().current();
                var bytes = Files.readAllBytes(targetsPath.resolve("target.json"));
                assertEquals(1, catalog(engine, "sample", 1).block().targetRevision());
                assertArrayEquals(bytes, Files.readAllBytes(targetsPath.resolve("target.json")));
                assertEquals(before, engine.published().current());
            }
        }
    }

    @Test
    void catalogValidatesOnlyArgumentsBeforeSubscription() {
        try (var engine = engine(request -> { })) {
            assertThrows(NullPointerException.class, () -> engine.describeDefinitions(null, 0));
            assertThrows(IllegalArgumentException.class, () -> engine.describeDefinitions(new PluginId("a"), -1));
            events.clear();
            var publisher = engine.describeDefinitions(new PluginId("a"), 0);
            assertTrue(events.isEmpty());
            assertCatalogCode(DefinitionCatalogException.Code.NOT_SELECTED, publisher);
        }
    }

    @Test
    void catalogCapturesExactSelectedRevisionAndNeverReadsHistoryOrOtherBadSelection() throws Exception {
        var packagesPath = root.resolve("exact-packages");
        try (var packages = new PluginPackageStore(packagesPath)) {
            var r1 = catalogPackage(packages, "sample", "1", "main:alpha");
            var r2 = catalogPackage(packages, "sample", "2", "main:alpha");
            var unrelated = catalogPackage(packages, "unrelated", "1", "main:alpha");
            var corrupt = packagesPath.resolve("records").resolve(java.util.HexFormat.of()
                .formatHex("unrelated".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .resolve(unrelated.packageRevision() + ".properties");
            assertTrue(Files.isRegularFile(corrupt));
            Files.writeString(corrupt, "broken record");
            alpha.queryFactory = facet -> new CatalogProbe("main", List.of(
                facet.packageRevision().equals(r1.packageRevision()) ? "r1" : "r2"));
            try (var engine = catalogEngine(packages,
                List.of(selection(r1, false), selection(unrelated, true)))) {
                assertEquals(TargetConvergence.BLOCKED, engine.snapshot().targetConvergence());
                var before = engine.published().current();
                assertEquals(List.of("r1"), catalog(engine, "sample", 1).block().facets().getFirst().definitionIds());
                assertEquals(before, engine.published().current());
                assertThrows(EngineChangeException.class, () -> engine.submit(ApplyDeployment.builder(graph(1))
                    .expectedRevision(1).selections(List.of(selection(r1, false), selection(unrelated, true)))
                    .configContext(ConfigContextSnapshot.empty()).build()).block());
                engine.submit(ApplyDeployment.builder(graph(1)).expectedRevision(1)
                    .selections(List.of(selection(r2, false))).configContext(ConfigContextSnapshot.empty()).build()).block();
                assertCatalogCode(DefinitionCatalogException.Code.STALE_TARGET, catalog(engine, "sample", 1));
                var catalog = catalog(engine, "sample", 2).block();
                assertEquals(r2.packageRevision(), catalog.packageRevision());
                assertEquals(List.of("r2"), catalog.facets().getFirst().definitionIds());
            }
        }
    }

    @Test
    void catalogDoesNotResolveMissingDisabledOrCyclicDependenciesButApplyStillRejectsThem() throws Exception {
        for (var mode : List.of("missing-selection", "missing-package", "disabled-dependency", "cycle")) {
            try (var packages = new PluginPackageStore(root.resolve(mode))) {
                var source = Files.createDirectories(root.resolve(mode + "-source"));
                Files.writeString(source.resolve("main.bin"), mode);
                Files.writeString(source.resolve(PluginPackage.MANIFEST), """
                    format: 1
                    id: sample
                    version: '1'
                    facets:
                      - id: main
                        role: host
                        runtime: alpha
                        target: host
                        payload: main.bin
                        dependencies:
                          - pluginId: %s
                            facetId: main
                        capabilities: []
                    """.formatted(mode.equals("cycle") ? "sample" : "dependency"));
                PluginPackageRecord record;
                try (var transaction = packages.prepareInstall(source)) { record = transaction.save(); }
                var selections = new ArrayList<PluginSelection>();
                selections.add(selection(record, true));
                if (mode.equals("missing-package")) {
                    selections.add(new PluginSelection(new PluginId("dependency"), "c".repeat(64), true));
                } else if (mode.equals("disabled-dependency")) {
                    selections.add(selection(catalogPackage(packages, "dependency", "1", "main:alpha"), false));
                }
                alpha.queryFactory = facet -> new CatalogProbe("main", List.of("entry"));
                store.reopen();
                try (var engine = catalogEngine(packages, selections)) {
                    assertEquals(TargetConvergence.BLOCKED, engine.snapshot().targetConvergence(), mode);
                    var before = engine.published().current();
                    assertEquals(List.of("entry"), catalog(engine, "sample", 1).block().facets().getFirst().definitionIds());
                    assertEquals(before, engine.published().current());
                    assertThrows(EngineChangeException.class, () -> engine.submit(ApplyDeployment.builder(graph(1))
                        .expectedRevision(1).selections(selections)
                        .configContext(ConfigContextSnapshot.empty()).build()).block(), mode);
                    assertEquals(0, store.saves);
                    assertTrue(engine.published().current().engineDiagnostics().mutationGateOpen());
                }
            }
        }
    }

    @Test
    void catalogMissingCorruptAndConflictingRootArePackageUnavailable() throws Exception {
        var packagesPath = root.resolve("unavailable-packages");
        try (var packages = new PluginPackageStore(packagesPath)) {
            var record = catalogPackage(packages, "sample", "1", "main:alpha");
            var conflicting = catalogPackage(packages, "a", "1", "main:alpha");
            var absent = new PluginSelection(new PluginId("missing"), "c".repeat(64), false);
            try (var engine = catalogEngine(packages,
                List.of(selection(record, false), selection(conflicting, false), absent))) {
                assertCatalogCode(DefinitionCatalogException.Code.PACKAGE_UNAVAILABLE, catalog(engine, "missing", 1));
                assertCatalogCode(DefinitionCatalogException.Code.PACKAGE_UNAVAILABLE, catalog(engine, "a", 1));
                alpha.metadataOverride = List.of(BuiltInPluginPackage.builder().pluginId(new PluginId("a"))
                    .version("1").packageDigest(conflicting.packageRevision()).facets(alpha.metadata().facets()).build());
                assertCatalogCode(DefinitionCatalogException.Code.PACKAGE_UNAVAILABLE, catalog(engine, "a", 1));
                alpha.metadataOverride = null;
                var recordPath = packagesPath.resolve("records").resolve(java.util.HexFormat.of()
                    .formatHex("sample".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .resolve(record.packageRevision() + ".properties");
                var original = Files.readString(recordPath);
                assertTrue(original.contains("pluginId=sample"));
                Files.writeString(recordPath, original.replace("pluginId=sample", "pluginId=other"));
                assertCatalogCode(DefinitionCatalogException.Code.PACKAGE_UNAVAILABLE, catalog(engine, "sample", 1));
                Files.writeString(recordPath, "corrupted");
                assertCatalogCode(DefinitionCatalogException.Code.PACKAGE_UNAVAILABLE, catalog(engine, "sample", 1));
            }
        }
    }

    @Test
    void catalogQueuedBeforeQuiesceExecutesDespiteCloseFlagWhileFollowingQueriesAreRejected() throws Exception {
        var packages = new PluginPackageStore(root.resolve("queued-query"));
        var record = catalogPackage(packages, "sample", "1", "main:alpha");
        var query = new CatalogProbe("main", List.of("entry"));
        query.readRelease = Sinks.one();
        alpha.queryFactory = facet -> query;
        var engine = catalogEngine(packages, List.of(selection(record, false), alpha.metadata().selection(false)));
        try {
            var reading = catalog(engine, "sample", 1).toFuture();
            query.readEntered.asMono().block(Duration.ofSeconds(2));
            var accepted = catalog(engine, "a", 1).toFuture();
            var closing = engine.closeAsync().toFuture();
            assertFalse(accepted.isDone());
            assertFalse(closing.isDone());
            assertCatalogCode(DefinitionCatalogException.Code.ENGINE_UNAVAILABLE, catalog(engine, "a", 1));
            query.readRelease.tryEmitEmpty();
            reading.get(2, TimeUnit.SECONDS);
            assertEquals(1, accepted.get(2, TimeUnit.SECONDS).targetRevision());
            closing.get(2, TimeUnit.SECONDS);
        } finally {
            query.readRelease.tryEmitEmpty();
            engine.close();
        }
    }

    @Test
    void catalogBuiltInMetadataIsFreshAndDuplicateWrongRuntimeAndWrongDigestAreRejected() {
        try (var engine = engine(request -> fail("unexpected termination"))) {
            engine.submit(ApplyDeployment.builder(graph(1)).expectedRevision(0)
                .selections(List.of(alpha.metadata().selection(false)))
                .configContext(ConfigContextSnapshot.empty()).build()).block();
            var fresh = BuiltInPluginPackage.builder().pluginId(new PluginId("a")).version("1")
                .packageDigest("a".repeat(64)).facets(List.of(BuiltInFacet.builder(new FacetId("main"), alpha.id(),
                    new ExecutionTarget("host")).definitionIds(Set.of("new", "entry")).build())).build();
            alpha.metadataOverride = List.of(fresh);
            assertEquals(List.of("entry", "new"), catalog(engine, "a", 1).block().facets().getFirst().definitionIds());
            alpha.metadataOverride = List.of(fresh, fresh);
            assertCatalogCode(DefinitionCatalogException.Code.PACKAGE_UNAVAILABLE, catalog(engine, "a", 1));
            alpha.metadataOverride = List.of(beta.metadata());
            assertCatalogCode(DefinitionCatalogException.Code.PACKAGE_UNAVAILABLE, catalog(engine, "a", 1));
            alpha.metadataOverride = List.of(BuiltInPluginPackage.builder().pluginId(new PluginId("a")).version("1")
                .packageDigest("c".repeat(64)).facets(fresh.facets()).build());
            assertCatalogCode(DefinitionCatalogException.Code.PACKAGE_UNAVAILABLE, catalog(engine, "a", 1));
        } finally { alpha.metadataOverride = null; }
    }

    private static final class HostileCatalogError extends AssertionError {
        @Override public String getMessage() { throw new AssertionError("must not inspect foreign message"); }
        @Override public String toString() { throw new AssertionError("must not stringify foreign error"); }
    }

    private static final class HostileCatalogRuntimeException extends RuntimeException {
        @Override public String getMessage() { throw new AssertionError("must not inspect foreign message"); }
        @Override public String toString() { throw new AssertionError("must not stringify foreign error"); }
    }

    private class CatalogProbe implements RuntimeDefinitionQuery {
        final String facet;
        final List<String> names;
        final AtomicInteger closes = new AtomicInteger();
        final AtomicBoolean readCancelled = new AtomicBoolean();
        final Sinks.One<Void> readEntered = Sinks.one();
        final Sinks.One<Void> closeEntered = Sinks.one();
        Sinks.One<Void> readRelease;
        Sinks.One<Void> closeRelease;
        Supplier<Mono<List<String>>> read;
        Supplier<Mono<Void>> close;
        CatalogProbe(String facet, List<String> names) { this.facet = facet; this.names = names; }
        public Mono<List<String>> readAsync() {
            events.add("query-read:" + facet);
            readEntered.tryEmitEmpty();
            if (read != null) return read.get();
            var result = Mono.just(names);
            return (readRelease == null ? result : readRelease.asMono().then(result))
                .doOnCancel(() -> readCancelled.set(true));
        }
        public Mono<Void> closeAsync() {
            events.add("query-close:" + facet);
            closes.incrementAndGet();
            closeEntered.tryEmitEmpty();
            if (close != null) return close.get();
            return closeRelease == null ? Mono.empty() : closeRelease.asMono();
        }
    }

    private PluginPackageRecord catalogPackage(PluginPackageStore packages, String plugin, String version,
                                               String... facets) throws Exception {
        var source = Files.createDirectories(root.resolve(UUID.randomUUID().toString()));
        var yaml = new StringBuilder("format: 1\nid: " + plugin + "\nversion: '" + version + "'\nfacets:\n");
        for (var value : facets) {
            var parts = value.split(":");
            Files.writeString(source.resolve(parts[0] + ".bin"), version + value);
            yaml.append("  - id: ").append(parts[0]).append("\n    role: host\n    runtime: ")
                .append(parts[1]).append("\n    target: host\n    payload: ").append(parts[0])
                .append(".bin\n    dependencies: []\n    capabilities: []\n");
        }
        Files.writeString(source.resolve(PluginPackage.MANIFEST), yaml);
        try (var transaction = packages.prepareInstall(source)) { return transaction.save(); }
    }

    private static PluginSelection selection(PluginPackageRecord record, boolean enabled) {
        return new PluginSelection(record.pluginId(), record.packageRevision(), enabled);
    }

    private FibraEngine catalogEngine(PluginPackageStore packages, List<PluginSelection> selections) {
        return catalogEngine(packages, selections, request -> fail("unexpected termination"));
    }

    private FibraEngine catalogEngine(PluginPackageStore packages, List<PluginSelection> selections,
                                      HostTerminationPort port) {
        store.current = DeploymentTarget.of(1, selections, graph(1), ConfigContextSnapshot.empty());
        var engine = FibraEngine.builder(packages, store).runtimeProvider(alpha).runtimeProvider(beta)
            .hostTerminationPort(port).build();
        engine.startAsync().block();
        return engine;
    }

    @Test
    void everyRuntimeSealsBeforeSaveAndGlobalDependencyOrderControlsReplacement() {
        beta.dependencies = List.of(new FacetDependency(new PluginId("a"), new FacetId("main")));
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1", "b1"));
            assertEquals(List.of("prepare:alpha", "prepare:beta", "seal:alpha", "seal:beta",
                "save:1", "activate:a1", "activate:b1"), events);
            events.clear();
            apply(engine, 1, graph(2, "a1", "b1"));
            assertEquals(List.of("prepare:alpha", "prepare:beta", "seal:alpha", "seal:beta", "save:2",
                "admission:b1", "admission:a1", "drain:b1", "drain:a1", "stop:b1", "stop:a1",
                "retire:beta", "retire:alpha", "activate:a1", "activate:b1"), events);
        }
    }

    @Test
    void replacementCandidatePhaseDoesNotOverwriteSettledCurrent() throws Exception {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1"));
            var currentAttempt = engine.snapshot().current().orElseThrow().attemptId();
            var prepareEntered = Sinks.<Void>one();
            var prepareRelease = Sinks.<Void>one();
            alpha.prepareEntered = prepareEntered;
            alpha.prepareRelease = prepareRelease;

            var replacement = engine.submit(ApplyDeployment.builder(graph(2, "a1"))
                .expectedRevision(1)
                .selections(List.of(alpha.metadata().selection(true), beta.metadata().selection(true)))
                .configContext(ConfigContextSnapshot.empty()).build()).toFuture();
            PublishedView view;
            try {
                prepareEntered.asMono().block(Duration.ofSeconds(2));
                view = engine.published().current();
            } finally {
                alpha.prepareRelease = null;
                prepareRelease.tryEmitEmpty();
            }
            replacement.get(2, TimeUnit.SECONDS);
            assertEquals(CandidatePhase.PREPARING,
                view.engine().candidate().orElseThrow().phase());
            assertEquals(currentAttempt,
                view.engine().current().orElseThrow().attemptId());
            assertEquals(CurrentPhase.SETTLED,
                view.engine().current().orElseThrow().phase());
            assertEquals(EngineOperationStage.PREPARING,
                view.engineDiagnostics().operation().orElseThrow().stage());
        }
    }

    @Test
    void partialSealAndExplicitSaveFailureAbortWithoutActivatingAnyUnit() {
        beta.sealFailure = true;
        try (var engine = engine(request -> fail("unexpected termination"))) {
            assertThrows(EngineChangeException.class, () -> apply(engine, 0, graph(1, "a1", "b1")));
            assertEquals(0, store.saves);
            assertTrue(events.contains("abort:alpha"));
            assertTrue(events.contains("close:beta"));
            assertFalse(events.stream().anyMatch(value -> value.startsWith("activate:")));
            beta.sealFailure = false;
            store.fail = true;
            events.clear();
            assertThrows(EngineChangeException.class, () -> apply(engine, 0, graph(1, "a1", "b1")));
            assertTrue(events.containsAll(List.of("abort:alpha", "abort:beta")));
            assertFalse(events.stream().anyMatch(value -> value.startsWith("activate:")));
            assertTrue(engine.published().current().engineDiagnostics().mutationGateOpen());
            assertEquals(DurableTargetState.ABSENT, engine.snapshot().durableState());
        }
    }

    @Test
    void sameContentIsNoopButAToBToAKeepsMonotonicRevision() {
        try (var engine = engine(request -> { })) {
            var a = apply(engine, 0, graph(1, "a1"));
            var identity = detail(engine, "a1").runtimeInstanceId();
            events.clear();
            var same = apply(engine, 1, graph(1, "a1"));
            assertEquals(EngineOperationOutcome.SUCCEEDED,
                same.engineDiagnostics().operation().orElseThrow().outcome());
            assertTrue(events.isEmpty());
            assertEquals(identity, detail(engine, "a1").runtimeInstanceId());
            apply(engine, 1, graph(2, "a1"));
            var secondA = apply(engine, 2, graph(1, "a1"));
            assertEquals(a.engine().target().orElseThrow().targetDigest(),
                secondA.engine().target().orElseThrow().targetDigest());
            assertEquals(3, secondA.engine().target().orElseThrow().targetRevision());
        }
    }

    @Test
    void retainedUnitKeepsObjectInstanceAndItsCreationRevision() {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1", "a2"));
            var retained = alpha.units.get("a1");
            var first = detail(engine, "a1");
            var graph = new DesiredInputGraph(List.of(entry("a1", "a", 1), entry("a2", "a", 2)));
            apply(engine, 1, graph);
            assertSame(retained, alpha.units.get("a1"));
            assertEquals(first.runtimeInstanceId(), detail(engine, "a1").runtimeInstanceId());
            assertEquals(1, detail(engine, "a1").unitTargetRevision());
            assertEquals(2, detail(engine, "a2").unitTargetRevision());
            assertEquals(0, alpha.retired.get(), "shared generation is still owned by retained a1");
        }
    }

    @Test
    void concurrentStartupSubscribersReceiveTheExactBootstrapViewBeforeQueuedReplacement()
        throws Exception {
        try (var seed = engine(request -> { })) {
            apply(seed, 0, graph(1, "a1"));
        }
        store.reopen();
        events.clear();
        var activationEntered = Sinks.<Void>one();
        var activationRelease = Sinks.<Void>one();
        alpha.activationEntered = activationEntered;
        alpha.activationRelease = activationRelease;
        var engine = FibraEngine.builder(
                new PluginPackageStore(root.resolve("concurrent-start")), store)
            .runtimeProvider(alpha).runtimeProvider(beta)
            .hostTerminationPort(request -> { }).build();
        try {
            var first = engine.startAsync().toFuture();
            var second = engine.startAsync().toFuture();
            activationEntered.asMono().block(Duration.ofSeconds(2));
            var replacement = engine.submit(ApplyDeployment.builder(graph(2, "a1"))
                .expectedRevision(1)
                .selections(List.of(alpha.metadata().selection(true),
                    beta.metadata().selection(true)))
                .configContext(ConfigContextSnapshot.empty()).build()).toFuture();
            activationRelease.tryEmitEmpty();

            var firstView = first.get(2, TimeUnit.SECONDS);
            var secondView = second.get(2, TimeUnit.SECONDS);
            assertEquals(1, firstView.engine().target().orElseThrow()
                .targetRevision());
            assertEquals(firstView, secondView);
            assertEquals(2, replacement.get(2, TimeUnit.SECONDS).view()
                .engine().target().orElseThrow()
                .targetRevision());
        } finally {
            alpha.activationEntered = null;
            alpha.activationRelease = null;
            engine.close();
        }
    }

    @Test
    void currentUnitDisablePersistsACompleteTargetAndDuplicateIsNoop() {
        try (var engine = engine(request -> { })) {
            var graph = graph(1, "a1", "b1");
            var context = ConfigContextSnapshot.of(new LiteralValue.ObjectValue(
                Map.of("tenant", LiteralValue.of("stable"))));
            engine.submit(ApplyDeployment.builder(graph).expectedRevision(0)
                .selections(List.of(alpha.metadata().selection(true),
                    beta.metadata().selection(true)))
                .configContext(context).build()).block();
            var before = engine.snapshot().target().orElseThrow();
            var alphaDetail = detail(engine, "a1");
            var betaIdentity = detail(engine, "b1").runtimeInstanceId();

            alpha.services.requestDisable(disable(alpha.id(), "a1", alphaDetail));
            flush(engine);

            var disabled = engine.snapshot().target().orElseThrow();
            assertEquals(before.targetRevision() + 1, disabled.targetRevision());
            assertEquals(before.selections(), disabled.selections());
            assertEquals(context, disabled.configContext());
            assertFalse(disabled.desiredGraph().plugins().get("a1").enabled());
            assertTrue(disabled.desiredGraph().plugins().get("b1").enabled());
            assertFalse(currentObservations(engine.snapshot()).containsKey(
                new ExecutionUnitKey("a1")));
            assertEquals(betaIdentity, detail(engine, "b1").runtimeInstanceId());
            var saves = store.saves;

            alpha.services.requestDisable(disable(alpha.id(), "a1", alphaDetail));
            flush(engine);

            assertEquals(saves, store.saves);
            assertEquals(disabled, engine.snapshot().target().orElseThrow());
        }
    }

    @Test
    void staleOrMismatchedUnitDisableCannotAffectCurrentGeneration() {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1"));
            var replaced = detail(engine, "a1");
            apply(engine, 1, graph(2, "a1"));
            var current = detail(engine, "a1");
            var target = engine.snapshot().target().orElseThrow();
            var saves = store.saves;

            alpha.services.requestDisable(disable(beta.id(), "a1", current));
            alpha.services.requestDisable(RuntimeUnitDisableRequest.of(
                RuntimeUnitFence.builder(alpha.id(), new ExecutionUnitKey("a1"))
                .unitTargetRevision(current.unitTargetRevision() + 1)
                .runtimeInstanceId(current.runtimeInstanceId())
                .build(), "wrong-revision"));
            alpha.services.requestDisable(RuntimeUnitDisableRequest.of(
                RuntimeUnitFence.builder(alpha.id(), new ExecutionUnitKey("a1"))
                .unitTargetRevision(current.unitTargetRevision())
                .runtimeInstanceId(current.runtimeInstanceId() + ":stale")
                .build(), "wrong-instance"));
            alpha.services.requestDisable(disable(alpha.id(), "a1", replaced));
            flush(engine);

            assertEquals(saves, store.saves);
            assertEquals(target, engine.snapshot().target().orElseThrow());
            assertEquals(current, detail(engine, "a1"));
        }
    }

    @Test
    void currentFenceRefreshPublishesLiveStateWithoutSavingReconcilingOrReplacing() throws Exception {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1"));
            var unit = alpha.units.get("a1");
            var active = detail(engine, "a1");
            var fence = fence(alpha.id(), "a1", active);
            var saves = store.saves;
            events.clear();

            var pendingView = nextState(engine, "a1",
                ExecutionObservation.State.PENDING);
            unit.observe("dependency-lost", ExecutionObservation.State.PENDING);
            alpha.services.requestObservationRefresh(fence);
            var pending = pendingView.get(2, TimeUnit.SECONDS);
            assertEquals(TargetConvergence.UNSATISFIED,
                pending.engine().targetConvergence());
            assertEquals(active.runtimeInstanceId(), detail(pending, "a1")
                .runtimeInstanceId());

            var failedView = nextState(engine, "a1",
                ExecutionObservation.State.FAILED);
            unit.observe("dependency-failed", ExecutionObservation.State.FAILED);
            alpha.services.requestObservationRefresh(fence);
            var failed = failedView.get(2, TimeUnit.SECONDS);
            assertEquals(TargetConvergence.UNSATISFIED,
                failed.engine().targetConvergence());
            assertNotNull(detail(failed, "a1").failure());

            var recoveredView = nextState(engine, "a1",
                ExecutionObservation.State.ACTIVE);
            unit.observe("dependency-recovered", ExecutionObservation.State.ACTIVE);
            alpha.services.requestObservationRefresh(fence);
            var recovered = recoveredView.get(2, TimeUnit.SECONDS);
            assertEquals(TargetConvergence.SATISFIED,
                recovered.engine().targetConvergence());
            assertEquals(active.runtimeInstanceId(), detail(recovered, "a1")
                .runtimeInstanceId());
            assertSame(unit, alpha.units.get("a1"));
            assertEquals(saves, store.saves);
            assertTrue(events.isEmpty());
        }
    }

    @Test
    void staleObservationFenceIsIgnoredWithoutPublishing() {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1"));
            var retiredFence = fence(alpha.id(), "a1", detail(engine, "a1"));
            apply(engine, 1, graph(2, "a1"));
            var before = engine.published().current();
            var current = detail(before, "a1");
            alpha.units.get("a1").observe("must-not-publish",
                ExecutionObservation.State.ACTIVE);

            alpha.services.requestObservationRefresh(retiredFence);
            alpha.services.requestObservationRefresh(RuntimeUnitFence.builder(
                    beta.id(), new ExecutionUnitKey("a1"))
                .unitTargetRevision(current.unitTargetRevision())
                .runtimeInstanceId(current.runtimeInstanceId()).build());
            alpha.services.requestObservationRefresh(RuntimeUnitFence.builder(
                    alpha.id(), new ExecutionUnitKey("missing"))
                .unitTargetRevision(current.unitTargetRevision())
                .runtimeInstanceId(current.runtimeInstanceId()).build());
            alpha.services.requestObservationRefresh(RuntimeUnitFence.builder(
                    alpha.id(), new ExecutionUnitKey("a1"))
                .unitTargetRevision(current.unitTargetRevision() + 1)
                .runtimeInstanceId(current.runtimeInstanceId()).build());
            alpha.services.requestObservationRefresh(RuntimeUnitFence.builder(
                    alpha.id(), new ExecutionUnitKey("a1"))
                .unitTargetRevision(current.unitTargetRevision())
                .runtimeInstanceId(current.runtimeInstanceId() + ":old").build());
            flush(engine);

            assertEquals(current.lifecycleOperationId(), detail(engine, "a1")
                .lifecycleOperationId());
        }
    }

    @Test
    void staleReconcileFenceCannotReplaceTheCurrentFailedGeneration()
        throws Exception {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1"));
            var stale = fence(alpha.id(), "a1", detail(engine, "a1"));
            apply(engine, 1, graph(2, "a1"));
            var current = detail(engine, "a1");
            var currentFence = fence(alpha.id(), "a1", current);
            var unit = alpha.units.get("a1");
            unit.observe("current-failed", ExecutionObservation.State.FAILED);
            var failedView = nextState(engine, "a1",
                ExecutionObservation.State.FAILED);

            alpha.services.requestReconcile(Set.of(stale,
                RuntimeUnitFence.builder(beta.id(),
                        new ExecutionUnitKey("a1"))
                    .unitTargetRevision(current.unitTargetRevision())
                    .runtimeInstanceId(current.runtimeInstanceId()).build(),
                RuntimeUnitFence.builder(alpha.id(),
                        new ExecutionUnitKey("missing"))
                    .unitTargetRevision(current.unitTargetRevision())
                    .runtimeInstanceId(current.runtimeInstanceId()).build(),
                RuntimeUnitFence.builder(alpha.id(),
                        new ExecutionUnitKey("a1"))
                    .unitTargetRevision(current.unitTargetRevision() + 1)
                    .runtimeInstanceId(current.runtimeInstanceId()).build(),
                RuntimeUnitFence.builder(alpha.id(),
                        new ExecutionUnitKey("a1"))
                    .unitTargetRevision(current.unitTargetRevision())
                    .runtimeInstanceId(current.runtimeInstanceId() + ":old")
                    .build()), "stale-runtime-callback");
            alpha.services.requestObservationRefresh(currentFence);
            failedView.get(2, TimeUnit.SECONDS);

            assertEquals(current.runtimeInstanceId(),
                detail(engine, "a1").runtimeInstanceId());
            assertEquals(ExecutionObservation.State.FAILED,
                detail(engine, "a1").state());
        }
    }

    @Test
    void oneRefreshSamplesEachUnitOnceForSnapshotAndTargetSatisfaction() throws Exception {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1"));
            var current = detail(engine, "a1");
            var unit = alpha.units.get("a1");
            unit.scriptSnapshots(ExecutionObservation.State.PENDING,
                ExecutionObservation.State.ACTIVE);
            var pendingView = nextState(engine, "a1",
                ExecutionObservation.State.PENDING);

            alpha.services.requestObservationRefresh(
                fence(alpha.id(), "a1", current));

            var pending = pendingView.get(2, TimeUnit.SECONDS);
            assertEquals(ExecutionObservation.State.PENDING,
                currentObservations(pending.engine()).get(new ExecutionUnitKey("a1"))
                    .aggregateState());
            assertEquals(TargetConvergence.UNSATISFIED,
                pending.engine().targetConvergence());
            assertEquals(1, unit.scriptedSnapshotCalls.get());
        }
    }

    @Test
    void failedUnitDisableSaveKeepsCurrentAndAllowsTheSameFenceToRetry() {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1", "b1"));
            var target = engine.snapshot().target().orElseThrow();
            var attempt = engine.snapshot().current().orElseThrow().attemptId();
            var current = detail(engine, "a1");
            store.fail = true;

            var failedSave = engine.published().views()
                .filter(view -> view.engineDiagnostics().failure().isPresent())
                .next().toFuture();
            alpha.services.requestDisable(disable(alpha.id(), "a1", current));
            var failedView = failedSave.join();

            assertEquals(target, engine.snapshot().target().orElseThrow());
            assertEquals(attempt,
                engine.snapshot().current().orElseThrow().attemptId());
            assertEquals(current, detail(engine, "a1"));
            assertTrue(engine.snapshot().target().orElseThrow().desiredGraph()
                .plugins().get("a1").enabled());
            assertTrue(failedView.engineDiagnostics()
                .mutationGateOpen());
            assertTrue(failedView.engineDiagnostics().failure().isPresent());

            store.fail = false;
            alpha.services.requestDisable(disable(alpha.id(), "a1", current));
            flush(engine);

            assertEquals(target.targetRevision() + 1,
                engine.snapshot().target().orElseThrow().targetRevision());
            assertFalse(engine.snapshot().target().orElseThrow().desiredGraph()
                .plugins().get("a1").enabled());
            assertFalse(currentObservations(engine.snapshot()).containsKey(
                new ExecutionUnitKey("a1")));
        }
    }

    @Test
    void contextAToBToAIsDurableWhileUnaffectedUnitRemainsOwnedByItsOriginalGeneration() {
        try (var engine = engine(request -> { })) {
            var desired = graph(1, "a1");
            String firstDigest = null;
            String instance = null;
            for (int index = 0; index < 3; index++) {
                var value = index == 1 ? "b" : "a";
                var context = ConfigContextSnapshot.of(new LiteralValue.ObjectValue(Map.of("host", LiteralValue.of(value))));
                engine.submit(ApplyDeployment.builder(desired).expectedRevision(index)
                    .selections(List.of(alpha.metadata().selection(true), beta.metadata().selection(true)))
                    .configContext(context).build()).block();
                assertEquals(index + 1, engine.snapshot().target().orElseThrow().targetRevision());
                if (index == 0) {
                    firstDigest = engine.snapshot().target().orElseThrow().targetDigest();
                    instance = detail(engine, "a1").runtimeInstanceId();
                }
                assertEquals(instance, detail(engine, "a1").runtimeInstanceId());
                assertEquals(1, detail(engine, "a1").unitTargetRevision());
            }
            assertEquals(firstDigest, engine.snapshot().target().orElseThrow().targetDigest());
            assertEquals(3, store.saves);
        }
    }

    @Test
    void changedExpandedEdgesRebuildDependentsOnAddingAndRemovingSiblingEntry() {
        alpha.dependencies = List.of(new FacetDependency(new PluginId("b"), new FacetId("main")));
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1", "b1"));
            var original = detail(engine, "a1").runtimeInstanceId();
            apply(engine, 1, graph(1, "a1", "b1", "b2"));
            assertNotEquals(original, detail(engine, "a1").runtimeInstanceId());
            assertEquals(List.of(new ExecutionUnitKey("b1"), new ExecutionUnitKey("b2")),
                alpha.units.get("a1").plan.dependencies());
            var second = detail(engine, "a1").runtimeInstanceId();
            apply(engine, 2, graph(1, "a1", "b2"));
            assertNotEquals(second, detail(engine, "a1").runtimeInstanceId());
            assertEquals(List.of(new ExecutionUnitKey("b2")), alpha.units.get("a1").plan.dependencies());
            assertFalse(currentObservations(engine.snapshot()).containsKey(new ExecutionUnitKey("b1")));
        }
    }

    @Test
    void packageGateSuppressesRawEnabledEntriesAndEnableRestoresThem() {
        try (var engine = engine(request -> { })) {
            var desired = graph(1, "a1", "a2");
            apply(engine, 0, desired);
            var disabled = ApplyDeployment.builder(desired).expectedRevision(1)
                .selections(List.of(alpha.metadata().selection(false), beta.metadata().selection(true)))
                .configContext(ConfigContextSnapshot.empty()).build();
            engine.submit(disabled).block();
            assertEquals(desired, engine.snapshot().target().orElseThrow().desiredGraph());
            assertTrue(currentObservations(engine.snapshot()).isEmpty());
            apply(engine, 2, desired);
            assertEquals(2, currentObservations(engine.snapshot()).size());
        }
    }

    @Test
    void contractFingerprintChangeRecompilesAllRuntimesWithoutSavingAnotherTarget() {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1", "b1"));
            var firstA = detail(engine, "a1").runtimeInstanceId();
            var firstB = detail(engine, "b1").runtimeInstanceId();
            alpha.contract = "v2";
            apply(engine, 1, graph(1, "a1", "b1"));
            assertEquals(1, store.saves);
            assertEquals(1, engine.snapshot().target().orElseThrow().targetRevision());
            assertNotEquals(firstA, detail(engine, "a1").runtimeInstanceId());
            assertNotEquals(firstB, detail(engine, "b1").runtimeInstanceId());
        }
    }

    @Test
    void failedCurrentUsesSameTokenButFreshUnitsAndCleansOldBeforeRetry() throws Exception {
        alpha.activationFailure = true;
        try (var engine = engine(request -> fail("ordinary activation failure must not terminate Host"))) {
            var rejected = assertThrows(EngineChangeException.class,
                () -> apply(engine, 0, graph(1, "a1")));
            assertEquals(TargetSaveState.SAVED, rejected.targetSaveState());
            assertSame(engine.published().current(), rejected.view());
            assertEquals(EngineState.RUNNING, engine.snapshot().state());
            assertEquals(CurrentPhase.BLOCKED,
                engine.snapshot().current().orElseThrow().phase());
            assertEquals(TargetConvergence.BLOCKED,
                engine.snapshot().targetConvergence());
            var diagnostics = rejected.view().engineDiagnostics();
            assertEquals(EngineOperationStage.RECONCILING,
                diagnostics.operation().orElseThrow().stage());
            assertEquals(EngineOperationOutcome.FAILED,
                diagnostics.operation().orElseThrow().outcome());
            var fact = diagnostics.failure().orElseThrow();
            var subject = assertInstanceOf(FailureSubject.Unit.class, fact.subject());
            assertEquals(engine.snapshot().current().orElseThrow().attemptId(), subject.ownerId());
            assertEquals(alpha.units.get("a1").fence(), subject.fence());
            assertEquals(FailureStage.RECONCILING, fact.stage());
            assertTrue(fact.message().contains("start failed"));
            assertTrue(diagnostics.mutationGateOpen());
            assertEquals(ExecutionObservation.State.FAILED, detail(engine, "a1").state());
            var refreshed = nextState(engine, "a1", ExecutionObservation.State.FAILED);
            alpha.units.get("a1").observe("failed-observation-refresh", ExecutionObservation.State.FAILED);
            alpha.services.requestObservationRefresh(subject.fence());
            var refreshedView = refreshed.get(2, TimeUnit.SECONDS);
            assertEquals(TargetConvergence.BLOCKED, refreshedView.engine().targetConvergence());
            assertEquals(fact, refreshedView.engineDiagnostics().failure().orElseThrow());
            var previous = detail(engine, "a1").runtimeInstanceId();
            engine.startAsync().block();
            assertEquals(1, Collections.frequency(events, "activate:a1"),
                "reading startup again must not automatically rebuild a failed current");
            var retried = assertThrows(EngineChangeException.class,
                () -> engine.submit(new ReconcileCurrent()).block());
            assertEquals(TargetSaveState.NOT_APPLICABLE, retried.targetSaveState());
            assertEquals(CurrentPhase.BLOCKED,
                retried.view().engine().current().orElseThrow().phase());
            assertNotEquals(previous, detail(engine, "a1").runtimeInstanceId());
            previous = detail(engine, "a1").runtimeInstanceId();
            alpha.activationFailure = false;
            events.clear();
            engine.submit(new ReconcileCurrent()).block();
            assertEquals(1, store.saves);
            assertNotEquals(previous, detail(engine, "a1").runtimeInstanceId());
            assertTrue(events.indexOf("stop:a1") < events.indexOf("activate:a1"));
            assertTrue(events.indexOf("retire:alpha") < events.indexOf("activate:a1"));
            assertEquals(TargetConvergence.SATISFIED, engine.snapshot().targetConvergence());
            assertTrue(engine.published().current().engineDiagnostics().failure().isEmpty());
        }
    }

    @Test
    void corruptStoredTargetFailsStartupClosesGatesAndNotifiesOnlyOnce() throws Exception {
        var targetRoot = root.resolve("corrupt-target");
        var targetStore = new FileDeploymentTargetStore(targetRoot);
        Files.writeString(targetRoot.resolve("target.json"), "corrupt-target-json");
        var notifications = new AtomicInteger();
        var notified = new CountDownLatch(1);
        var engine = FibraEngine.builder(new PluginPackageStore(root.resolve("corrupt-packages")), targetStore)
            .runtimeProvider(alpha).hostTerminationPort(request -> {
                notifications.incrementAndGet();
                notified.countDown();
            }).build();
        try {
            assertThrows(DeploymentTargetStoreException.class,
                () -> engine.startAsync().block(Duration.ofSeconds(5)));
            assertTrue(notified.await(2, TimeUnit.SECONDS));
            var view = engine.published().current();
            assertEquals(EngineState.FAIL_STOP, view.engine().state());
            assertEquals(DurableTargetState.UNCERTAIN, view.engine().durableState());
            assertEquals(TargetConvergence.BLOCKED, view.engine().targetConvergence());
            assertTrue(view.engine().target().isEmpty());
            assertTrue(view.engine().candidate().isEmpty());
            assertTrue(view.engine().current().isEmpty());
            var fact = view.engineDiagnostics().failure().orElseThrow();
            assertInstanceOf(FailureSubject.Engine.class, fact.subject());
            assertEquals(FailureStage.BOOTSTRAPPING, fact.stage());
            assertTrue(fact.message().contains("cannot load deployment target"));
            assertFalse(view.engineDiagnostics().mutationGateOpen());
            assertFalse(view.engineDiagnostics().contributionAdmissionOpen());
            assertThrows(RuntimeException.class, () -> engine.startAsync().block(Duration.ofSeconds(5)));
            assertThrows(MutationGateClosedException.class,
                () -> engine.submit(new ReconcileCurrent()).block(Duration.ofSeconds(5)));
            assertEquals(1, notifications.get());
            assertTrue(events.isEmpty(), "untrusted targets must not prepare runtime resources");
            assertSame(view, engine.published().current());
        } finally {
            engine.closeAsync().block(Duration.ofSeconds(5));
        }
        assertEquals(List.of("driver-close:alpha"), events);
        try (var reopened = new FileDeploymentTargetStore(targetRoot)) {
            assertThrows(DeploymentTargetStoreException.class, reopened::load);
        }
    }

    @Test
    void disabledRawEntryStillRequiresASelectedPackageBeforeSaving() {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1"));
            var current = engine.snapshot().current().orElseThrow();
            var target = store.current;
            events.clear();
            var disabled = new DesiredInputGraph(List.of(entry("b1", "b", 1)))
                .withEnabled("b1", false);
            var command = ApplyDeployment.builder(disabled).expectedRevision(1)
                .selections(List.of(alpha.metadata().selection(true)))
                .configContext(ConfigContextSnapshot.empty()).build();
            var rejected = assertThrows(EngineChangeException.class,
                () -> engine.submit(command).block());
            assertEquals(TargetSaveState.NOT_SAVED, rejected.targetSaveState());
            assertSame(target, store.current);
            assertEquals(1, store.saves);
            assertEquals(current, engine.snapshot().current().orElseThrow());
            assertEquals(TargetConvergence.SATISFIED, engine.snapshot().targetConvergence());
            assertTrue(engine.published().current().engineDiagnostics().mutationGateOpen());
            assertTrue(events.isEmpty(), "invalid raw references must fail before prepare/save");
        }
    }

    @Test
    void selectedPackageGateFalsePreservesRawEntryWithoutPreparingRuntime() {
        try (var engine = engine(request -> { })) {
            var desired = graph(1, "a1");
            engine.submit(ApplyDeployment.builder(desired)
                .selections(List.of(alpha.metadata().selection(false)))
                .configContext(ConfigContextSnapshot.empty()).build()).block();
            assertEquals(desired, store.current.desiredGraph());
            assertEquals(List.of("save:1"), events);
            assertTrue(currentObservations(engine.snapshot()).isEmpty());
            assertEquals(TargetConvergence.SATISFIED, engine.snapshot().targetConvergence());
        }
    }

    @Test
    void saveUncertainDoesNotPromoteAndBlockingTerminationCannotBlockCommandLane() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var notifications = new AtomicInteger();
        var engine = engine(request -> {
            notifications.incrementAndGet();
            entered.countDown();
            try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        try {
            apply(engine, 0, graph(1, "a1"));
            var oldAttempt = engine.snapshot().current().orElseThrow().attemptId();
            store.uncertain = true;
            assertThrows(EngineChangeException.class, () -> apply(engine, 1, graph(2, "a1")));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(DurableTargetState.UNCERTAIN, engine.snapshot().durableState());
            assertEquals(CandidatePhase.FAILED,
                engine.snapshot().candidate().orElseThrow().phase());
            assertEquals(oldAttempt, engine.snapshot().current().orElseThrow().attemptId());
            assertEquals(CurrentPhase.SETTLED,
                engine.snapshot().current().orElseThrow().phase());
            assertEquals(EngineOperationOutcome.FAILED,
                engine.published().current().engineDiagnostics().operation()
                    .orElseThrow().outcome());
            assertFalse(engine.published().current().engineDiagnostics().contributionAdmissionOpen());
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThrows(MutationGateClosedException.class, () -> engine.submit(new ReconcileCurrent()).block()));
            assertEquals(1, notifications.get());
        } finally { release.countDown(); engine.close(); }
    }

    @Test
    void throwingOrReentrantTerminationPortCannotReopenGateOrProduceAnotherRequest() throws Exception {
        for (boolean reentrant : List.of(false, true)) {
            var reference = new AtomicReference<FibraEngine>();
            var called = new CountDownLatch(1);
            var calls = new AtomicInteger();
            var packages = new PluginPackageStore(root.resolve("reentry-" + reentrant));
            var engine = FibraEngine.builder(packages, store).runtimeProvider(alpha).runtimeProvider(beta)
                .hostTerminationPort(request -> {
                    calls.incrementAndGet();
                    try {
                        if (reentrant) assertThrows(MutationGateClosedException.class,
                            () -> reference.get().submit(new ReconcileCurrent()).block(Duration.ofSeconds(1)));
                        else throw new IllegalStateException("port failure");
                    } finally { called.countDown(); }
                }).build();
            reference.set(engine);
            store.uncertain = true;
            engine.startAsync().block();
            try {
                assertThrows(EngineChangeException.class, () -> apply(engine, 0, graph(1, "a1")));
                assertTrue(called.await(2, TimeUnit.SECONDS));
                assertThrows(MutationGateClosedException.class, () -> engine.submit(new ReconcileCurrent()).block());
                assertEquals(1, calls.get());
            } finally { engine.close(); store.reset(); }
        }
    }

    @Test
    void abortFailureRetainsCandidateAndClosesBothGates() throws Exception {
        var notified = new CountDownLatch(1);
        alpha.abortFailure = true;
        beta.sealFailure = true;
        var engine = engine(request -> notified.countDown());
        try {
            assertThrows(EngineChangeException.class, () -> apply(engine, 0, graph(1, "a1", "b1")));
            assertTrue(notified.await(2, TimeUnit.SECONDS));
            assertTrue(engine.snapshot().candidate().isPresent());
            assertFalse(engine.published().current().engineDiagnostics().mutationGateOpen());
            assertFalse(engine.published().current().engineDiagnostics().contributionAdmissionOpen());
        } finally { alpha.abortFailure = false; engine.close(); }
    }

    @Test
    void providerConstructionFailureClosesAllAlreadyCreatedDriversInReverseOrder() {
        var packageRoot = root.resolve("providers");
        var targetStore = new Store();
        var capturedScope = new AtomicReference<com.sstlfsj.fibra.ScopeView>();
        var creationFailure = new IllegalStateException("creation failed");
        var third = new ProbeProvider("zeta", "z") {
            public RuntimeDriver create(RuntimeHostServices services) {
                capturedScope.set(services.scope());
                throw creationFailure;
            }
        };
        var thrown = assertThrows(IllegalStateException.class, () -> FibraEngine.builder(
            new PluginPackageStore(packageRoot), targetStore).runtimeProvider(alpha)
            .runtimeProvider(beta).runtimeProvider(third).hostTerminationPort(request -> { }).build());
        assertSame(creationFailure, thrown);
        assertEquals(List.of("driver-close:beta", "driver-close:alpha"), events);
        assertTrue(capturedScope.get().isClosed());
        assertConstructionStoresReleased(packageRoot, targetStore);
    }

    @Test
    void providerRegistryFailureClosesTransferredStores() {
        var packageRoot = root.resolve("duplicate-provider");
        var targetStore = new Store();

        assertThrows(IllegalArgumentException.class, () -> FibraEngine.builder(
                new PluginPackageStore(packageRoot), targetStore)
            .runtimeProvider(alpha).runtimeProvider(alpha)
            .hostTerminationPort(request -> { }).build());

        assertConstructionStoresReleased(packageRoot, targetStore);
        assertTrue(events.isEmpty());
    }

    @Test
    void providerMetadataFailurePreservesOriginalAndSuppressesStoreCloseFailure() {
        var packageRoot = root.resolve("provider-metadata");
        var targetStore = new Store();
        var metadataFailure = new IllegalStateException("metadata failed");
        var storeCloseFailure = new IllegalStateException("target store close failed");
        targetStore.closeFailure = storeCloseFailure;
        var provider = new ProbeProvider("metadata", "m") {
            public List<BuiltInPluginPackage> builtInPackages() {
                throw metadataFailure;
            }
        };

        var thrown = assertThrows(IllegalStateException.class, () -> FibraEngine.builder(
                new PluginPackageStore(packageRoot), targetStore)
            .runtimeProvider(provider).hostTerminationPort(request -> { }).build());

        assertSame(metadataFailure, thrown);
        assertTrue(Arrays.asList(thrown.getSuppressed()).contains(storeCloseFailure));
        assertConstructionStoresReleased(packageRoot, targetStore);
        assertTrue(events.isEmpty());
    }

    @Test
    void hostServicesFreezeAtStartAndAreInheritedByRuntimeScopes() {
        var key = com.sstlfsj.fibra.ServiceKey.of("host-message", String.class);
        var hostServices = new HostServiceRegistry();
        hostServices.register(key, "available");
        try (var engine = FibraEngine.builder(new PluginPackageStore(
                root.resolve("host-services")), store)
            .runtimeProvider(alpha).runtimeProvider(beta)
            .hostServices(hostServices)
            .hostTerminationPort(request -> { }).build()) {
            engine.startAsync().block();

            assertTrue(engine.published().current().diagnostics().services().stream()
                .anyMatch(service -> service.service().name().equals(key.name())
                    && service.service().type().equals(key.type().getName())));
            assertThrows(IllegalStateException.class, () -> hostServices.register(
                com.sstlfsj.fibra.ServiceKey.of("late", String.class), "late"));
            var child = alpha.services.scope().openChild("host-service-consumer");
            try {
                assertEquals("available",
                    child.context().services().require(key));
            } finally {
                child.close();
            }
        }
    }

    @Test
    void healthyReconcilePreservesInstanceAndOperationIdentity() {
        try (var engine = engine(request -> fail("healthy reconcile must not terminate Host"))) {
            apply(engine, 0, graph(1, "a1"));
            var before = detail(engine, "a1");
            events.clear();
            engine.submit(new ReconcileCurrent()).block();
            assertEquals(before, detail(engine, "a1"));
            assertEquals(EngineOperationOutcome.SUCCEEDED,
                engine.published().current().engineDiagnostics().operation()
                    .orElseThrow().outcome());
            assertTrue(events.isEmpty());
            assertTrue(engine.published().current().engineDiagnostics().mutationGateOpen());
        }
    }

    @Test
    void availabilityWakeupStartsPendingDependencyBeforeItsDependent() {
        beta.dependencies = List.of(new FacetDependency(new PluginId("a"), new FacetId("main")));
        alpha.pending = true;
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1", "b1"));
            assertEquals(ExecutionObservation.State.PENDING, detail(engine, "a1").state());
            assertEquals(ExecutionObservation.State.PENDING, detail(engine, "b1").state());
            assertFalse(events.contains("activate:b1"));
            assertEquals(CurrentPhase.SETTLED, engine.snapshot().current().orElseThrow().phase());
            assertEquals(TargetConvergence.UNSATISFIED, engine.snapshot().targetConvergence());
            assertEquals(EngineOperationOutcome.SUCCEEDED,
                engine.published().current().engineDiagnostics().operation().orElseThrow().outcome());
            assertTrue(engine.published().current().engineDiagnostics().failure().isEmpty());
            var instance = detail(engine, "a1").runtimeInstanceId();
            alpha.pending = false;
            events.clear();
            alpha.services.requestReconcile(Set.of(fence(alpha.id(), "a1",
                detail(engine, "a1"))), "online");
            engine.submit(new ReconcileCurrent()).block();
            assertEquals(List.of("activate:a1", "activate:b1"), events);
            assertEquals(instance, detail(engine, "a1").runtimeInstanceId());
            assertEquals(1, store.saves);
        }
    }

    @Test
    void failedExecutionWakeupReplacesTheUnitInsteadOfReconcilingItInPlace() {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1"));
            var failed = alpha.units.get("a1");
            var previousInstance = failed.instance;
            failed.observed = failed.observation("sidecar-exited",
                ExecutionObservation.State.FAILED);
            events.clear();

            alpha.services.requestReconcile(Set.of(fence(alpha.id(), "a1",
                detail(engine, "a1"))), "execution-exited");
            engine.submit(new ReconcileCurrent()).block();

            assertNotEquals(previousInstance,
                detail(engine, "a1").runtimeInstanceId());
            assertTrue(events.indexOf("admission:a1")
                < events.indexOf("stop:a1"));
            assertTrue(events.indexOf("stop:a1")
                < events.indexOf("activate:a1"));
            assertEquals(1, store.saves);
        }
    }

    @Test
    void batchedReconcileReplacesFailedClosureOnceAndThenWakesIndependentPendingUnit() {
        beta.dependencies = List.of(new FacetDependency(new PluginId("a"),
            new FacetId("main")));
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1", "a2", "b1"));
            var failed = detail(engine, "a1");
            var pending = detail(engine, "a2");
            var dependent = detail(engine, "b1");
            alpha.units.get("a1").observe("execution-exited",
                ExecutionObservation.State.FAILED);
            alpha.units.get("a2").observe("dependency-lost",
                ExecutionObservation.State.PENDING);
            events.clear();

            alpha.services.requestReconcile(Set.of(
                fence(alpha.id(), "a1", failed),
                fence(alpha.id(), "a2", pending),
                fence(beta.id(), "b1", dependent),
                RuntimeUnitFence.builder(alpha.id(), new ExecutionUnitKey("a1"))
                    .unitTargetRevision(failed.unitTargetRevision())
                    .runtimeInstanceId(failed.runtimeInstanceId() + ":stale")
                    .build()), "batched-runtime-callback");
            flush(engine);

            assertNotEquals(failed.runtimeInstanceId(),
                detail(engine, "a1").runtimeInstanceId());
            assertNotEquals(dependent.runtimeInstanceId(),
                detail(engine, "b1").runtimeInstanceId());
            assertEquals(pending.runtimeInstanceId(),
                detail(engine, "a2").runtimeInstanceId());
            assertEquals(ExecutionObservation.State.ACTIVE,
                detail(engine, "a2").state());
            assertEquals(ExecutionObservation.State.ACTIVE,
                detail(engine, "a1").state());
            assertEquals(ExecutionObservation.State.ACTIVE,
                detail(engine, "b1").state());
            assertEquals(1, events.stream().filter("stop:a1"::equals).count());
            assertEquals(1, events.stream().filter("stop:b1"::equals).count());
            assertEquals(1, events.stream().filter("activate:a1"::equals).count());
            assertEquals(1, events.stream().filter("activate:b1"::equals).count());
            assertEquals(1, events.stream().filter("activate:a2"::equals).count());
        }
    }

    @Test
    void planAffectingCallbackRecompilesAllUnitsWithSameDurableToken() {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1", "b1"));
            var first = detail(engine, "b1").runtimeInstanceId();
            alpha.contract = "changed";
            alpha.services.requestRecompile(new RuntimeRecompileReason(alpha.id(), "contract", "new contract"));
            engine.submit(new ReconcileCurrent()).block();
            assertNotEquals(first, detail(engine, "b1").runtimeInstanceId());
            assertEquals(1, engine.snapshot().target().orElseThrow().targetRevision());
            assertEquals(1, store.saves);
        }
    }

    @Test
    void builtInDeclarationDriftChangesFingerprintEvenWhenContractAndDigestStayTheSame() {
        try (var engine = engine(request -> { }, () ->
            HostCapabilitySnapshot.of(Map.of("added-capability", true)))) {
            apply(engine, 0, graph(1, "a1", "b1"));
            var original = engine.snapshot().current().orElseThrow().compiledFingerprint();
            var betaInstance = detail(engine, "b1").runtimeInstanceId();
            alpha.requiredCapabilities = Set.of("added-capability");
            apply(engine, 1, graph(1, "a1", "b1"));
            assertNotEquals(original, engine.snapshot().current().orElseThrow().compiledFingerprint());
            assertNotEquals(betaInstance, detail(engine, "b1").runtimeInstanceId());
            assertEquals(1, store.saves);
        }
    }

    @Test
    void activeUnitRequiresItsFacetCapabilitiesBeforeAnyCandidateSideEffect() {
        alpha.requiredCapabilities = Set.of("storage");
        try (var engine = engine(request -> { })) {
            events.clear();

            assertThrows(EngineChangeException.class,
                () -> apply(engine, 0, graph(1, "a1")));

            assertEquals(0, store.saves);
            assertTrue(events.isEmpty());
            assertTrue(engine.snapshot().current().isEmpty());
        }
    }

    @Test
    void capabilityChangesAreFrozenPerCommittedGeneration() {
        alpha.requiredCapabilities = Set.of("storage");
        var source = new AtomicReference<>(HostCapabilitySnapshot.of(
            Map.of("storage", true)));
        try (var engine = engine(request -> { }, source::get)) {
            apply(engine, 0, graph(1, "a1"));
            var previous = detail(engine, "a1");
            assertEquals(Set.of("storage"), previous.capabilities());

            source.set(HostCapabilitySnapshot.empty());
            assertThrows(EngineChangeException.class,
                () -> apply(engine, 1, graph(1, "a1")));
            var preserved = detail(engine, "a1");
            assertEquals(previous.runtimeInstanceId(),
                preserved.runtimeInstanceId());
            assertEquals(Set.of("storage"), preserved.capabilities());
            assertEquals(CurrentPhase.SETTLED,
                engine.snapshot().current().orElseThrow().phase());
            assertEquals(EngineOperationOutcome.FAILED,
                engine.published().current().engineDiagnostics().operation()
                    .orElseThrow().outcome());
            assertEquals(1, store.saves);

            source.set(HostCapabilitySnapshot.of(Map.of(
                "storage", true, "gpu", "available")));
            apply(engine, 1, graph(1, "a1"));
            var replaced = detail(engine, "a1");
            assertNotEquals(previous.runtimeInstanceId(),
                replaced.runtimeInstanceId());
            assertEquals(Set.of("storage", "gpu"),
                replaced.capabilities());
            assertEquals(1, store.saves);
        }
    }

    @Test
    void prepareAndPlanFailureNeverReachSaveAndCloseEveryCandidate() {
        try (var engine = engine(request -> fail("clean preparation failure is recoverable"))) {
            beta.prepareFailure = true;
            assertThrows(EngineChangeException.class, () -> apply(engine, 0, graph(1, "a1", "b1")));
            assertEquals(0, store.saves);
            assertTrue(events.containsAll(List.of("close:alpha", "close:beta")));
            beta.prepareFailure = false;
            beta.omitUnit = true;
            events.clear();
            assertThrows(EngineChangeException.class, () -> apply(engine, 0, graph(1, "a1", "b1")));
            assertEquals(0, store.saves);
            assertFalse(events.stream().anyMatch(value -> value.startsWith("seal:")));
            assertTrue(events.containsAll(List.of("close:alpha", "close:beta")));
        }
    }

    @Test
    void drainStopAndRetireFailureKeepNewTargetAndRetiringOwnership() throws Exception {
        for (var failure : List.of("drain", "stop", "retire")) {
            var notified = new CountDownLatch(1);
            var engine = engine(request -> notified.countDown());
            try {
                apply(engine, 0, graph(1, "a1"));
                alpha.lifecycleFailure = failure;
                events.clear();
                assertThrows(EngineChangeException.class, () -> apply(engine, 1, graph(2, "a1")));
                assertTrue(notified.await(2, TimeUnit.SECONDS));
                assertEquals(2, engine.snapshot().target().orElseThrow().targetRevision());
                assertFalse(engine.snapshot().retirementBatch().isEmpty());
                assertEquals(RetirementPhase.FAILED,
                    engine.snapshot().retirementBatch().orElseThrow().phase());
                assertEquals(CurrentPhase.BLOCKED,
                    engine.snapshot().current().orElseThrow().phase());
                var fact = engine.published().current().engineDiagnostics().failure().orElseThrow();
                assertEquals(switch (failure) {
                    case "drain" -> FailureStage.DRAINING;
                    case "stop" -> FailureStage.STOPPING;
                    default -> FailureStage.RELEASING;
                }, fact.stage());
                assertEquals(EngineOperationStage.RETIRING, fact.operationStage().orElseThrow());
                var owner = assertInstanceOf(FailureSubject.Retirement.class, fact.subject());
                assertEquals(engine.snapshot().retirementBatch().orElseThrow().batchId(), owner.batchId());
                assertFalse(engine.published().current().engineDiagnostics().mutationGateOpen());
                assertFalse(events.contains("activate:a1"));
                if (failure.equals("drain")) assertFalse(events.contains("stop:a1"));
                var beforeClose = engine.published().current();
                var retained = beforeClose.engine().retirementBatch().orElseThrow();
                var retainedFailure = beforeClose.engineDiagnostics().failure().orElseThrow();
                events.clear();

                var first = assertThrows(IllegalStateException.class,
                    () -> engine.closeAsync().block(Duration.ofSeconds(5)));
                var firstEvents = List.copyOf(events);
                var second = assertThrows(IllegalStateException.class,
                    () -> engine.closeAsync().block(Duration.ofSeconds(5)));
                var afterClose = engine.published().current();

                assertAll("Host close after " + failure + " failure",
                    () -> assertSame(first, second),
                    () -> assertEquals(firstEvents, events, "cached close must not retry retained cleanup"),
                    () -> assertEquals(EngineState.FAIL_STOP, afterClose.engine().state()),
                    () -> assertEquals(retained, afterClose.engine().retirementBatch().orElseThrow()),
                    () -> assertEquals(beforeClose.engine().current(), afterClose.engine().current()),
                    () -> assertEquals(retainedFailure, afterClose.engineDiagnostics().failure().orElseThrow()),
                    () -> assertEquals(EngineOperationKind.SHUTDOWN,
                        afterClose.engineDiagnostics().operation().orElseThrow().kind()),
                    () -> assertEquals(EngineOperationOutcome.FAILED,
                        afterClose.engineDiagnostics().operation().orElseThrow().outcome()),
                    () -> assertFalse(afterClose.engineDiagnostics().mutationGateOpen()),
                    () -> assertFalse(afterClose.engineDiagnostics().contributionAdmissionOpen()),
                    () -> assertTrue(events.isEmpty(), "retained ownership must not be cleaned up again"),
                    () -> assertEquals(0, store.closes),
                    () -> assertFalse(alpha.services.scope().isClosed())
                );
            } finally {
                assertThrows(IllegalStateException.class, engine::close);
                alpha.lifecycleFailure = null;
                store.reset();
            }
        }
    }

    @Test
    void drainTimeoutDoesNotStopOrRetireStillLeasedUnit() throws Exception {
        var notified = new CountDownLatch(1);
        var engine = FibraEngine.builder(new PluginPackageStore(root.resolve("timeout")), store)
            .runtimeProvider(alpha).runtimeProvider(beta).hostTerminationPort(request -> notified.countDown())
            .lifecycleTimeout(Duration.ofMillis(30)).build();
        engine.startAsync().block();
        try {
            apply(engine, 0, graph(1, "a1"));
            alpha.lifecycleFailure = "timeout";
            events.clear();
            assertThrows(EngineChangeException.class, () -> apply(engine, 1, graph(2, "a1")));
            assertTrue(notified.await(2, TimeUnit.SECONDS));
            assertFalse(events.contains("stop:a1"));
            assertFalse(events.contains("retire:alpha"));
            assertFalse(events.contains("activate:a1"));
        } finally { assertThrows(IllegalStateException.class, engine::close); }
    }

    @Test
    void restartRebuildsOnlyDurableTargetAndMissingProviderLeavesPresentWithoutCurrent() {
        try (var first = engine(request -> { })) { apply(first, 0, graph(1, "a1")); }
        store.reopen();
        var original = alpha.units.get("a1").instance;
        try (var second = engine(request -> { })) {
            assertEquals(1, store.saves);
            assertNotEquals(original, detail(second, "a1").runtimeInstanceId());
            assertEquals(1, second.snapshot().target().orElseThrow().targetRevision());
        }
        store.reopen();
        var broken = FibraEngine.builder(new PluginPackageStore(root.resolve("missing-provider")), store)
            .runtimeProvider(beta).hostTerminationPort(request -> { }).build();
        try {
            broken.startAsync().block();
            assertEquals(EngineState.RUNNING, broken.snapshot().state());
            assertEquals(TargetConvergence.BLOCKED,
                broken.snapshot().targetConvergence());
            assertEquals(DurableTargetState.PRESENT, broken.snapshot().durableState());
            assertTrue(broken.snapshot().current().isEmpty());
            assertEquals(1, store.saves);
            assertTrue(broken.published().current().engineDiagnostics().mutationGateOpen());
            assertEquals(EngineOperationOutcome.FAILED,
                broken.published().current().engineDiagnostics().operation()
                    .orElseThrow().outcome());
        } finally { broken.close(); }
    }

    @Test
    void snapshotContractViolationStillPublishesFatalAndNotifiesHostOnce() throws Exception {
        var notifications = new AtomicInteger();
        var notified = new CountDownLatch(1);
        try (var engine = engine(request -> { notifications.incrementAndGet(); notified.countDown(); })) {
            apply(engine, 0, graph(1, "a1"));
            var previous = detail(engine, "a1");
            alpha.snapshotFailure = true;
            assertThrows(IllegalStateException.class, () -> engine.submit(new ReconcileCurrent()).block());
            assertTrue(notified.await(2, TimeUnit.SECONDS));
            assertEquals(previous, detail(engine, "a1"), "last proven observation remains available");
            assertFalse(engine.published().current().engineDiagnostics().mutationGateOpen());
            assertEquals(1, notifications.get());
            alpha.snapshotFailure = false;
        }
    }

    @Test
    void storeReturningADifferentDurableTokenIsUncertainAndNeverPromotes() {
        try (var engine = engine(request -> { })) {
            apply(engine, 0, graph(1, "a1"));
            var attempt = engine.snapshot().current().orElseThrow().attemptId();
            store.wrongToken = true;
            assertThrows(EngineChangeException.class, () -> apply(engine, 1, graph(2, "a1")));
            assertEquals(DurableTargetState.UNCERTAIN, engine.snapshot().durableState());
            assertEquals(attempt, engine.snapshot().current().orElseThrow().attemptId());
            assertFalse(engine.published().current().engineDiagnostics().mutationGateOpen());
        }
    }

    @Test
    void hostCloseFailurePublishesClosingFactFromNew() {
        verifyHostCloseFailure(0);
    }

    @Test
    void hostCloseFailurePublishesClosingFactFromRunningWithoutCurrent() {
        verifyHostCloseFailure(1);
    }

    @Test
    void hostCloseFailurePublishesClosingFactFromRunningWithCurrent() {
        verifyHostCloseFailure(2);
    }

    @Test
    void closeAdmissionFailureRetainsOnlyRetirementAndStillReleasesCommandLoop() {
        var admissionFailure = new IllegalStateException("closeAdmission contract failed");
        var constructorThread = Thread.currentThread();
        var decorated = new AtomicInteger();
        var loopShutdowns = new AtomicInteger();
        var decoratorKey = "engine-close-admission-" + UUID.randomUUID();
        Schedulers.addExecutorServiceDecorator(decoratorKey, (scheduler, executor) -> {
            var commandLoopConstruction = Thread.currentThread() == constructorThread
                && StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                    frame.getClassName().equals(EngineCommandLoop.class.getName())
                        && frame.getMethodName().equals("<init>")));
            if (!commandLoopConstruction) return executor;
            decorated.incrementAndGet();
            return new CloseFailureExecutor(executor, loopShutdowns::incrementAndGet);
        });
        try {
            var engine = engine(request -> { });
            apply(engine, 0, graph(1, "a1"));
            var previous = engine.snapshot().current().orElseThrow();
            var unitFence = alpha.units.get("a1").fence();
            alpha.closeAdmissionFailure = admissionFailure;
            events.clear();

            var first = assertThrows(RuntimeException.class,
                () -> engine.closeAsync().block(Duration.ofSeconds(5)));
            var firstEvents = List.copyOf(events);
            var second = assertThrows(RuntimeException.class,
                () -> engine.closeAsync().block(Duration.ofSeconds(5)));
            var view = engine.published().current();
            var retained = view.engine().retirementBatch().orElseThrow();

            assertAll(
                () -> assertSame(first, second),
                () -> assertTrue(containsFailure(first, admissionFailure)),
                () -> assertEquals(firstEvents, events, "cached close must not re-enter cleanup"),
                () -> assertEquals(1, decorated.get()),
                () -> assertEquals(1, loopShutdowns.get(), "independent loop shutdown must still run once"),
                () -> assertEquals(EngineState.FAIL_STOP, view.engine().state()),
                () -> assertTrue(view.engine().current().isEmpty(), "current ownership must already be transferred"),
                () -> assertEquals(previous.attemptId(), retained.sourceAttemptId()),
                () -> assertEquals(RetirementPhase.FAILED, retained.phase()),
                () -> assertEquals(previous.observations(), retained.observations()),
                () -> assertFalse(view.engineDiagnostics().mutationGateOpen()),
                () -> assertFalse(view.engineDiagnostics().contributionAdmissionOpen()),
                () -> assertEquals(EngineOperationKind.SHUTDOWN,
                    view.engineDiagnostics().operation().orElseThrow().kind()),
                () -> assertEquals(EngineOperationOutcome.FAILED,
                    view.engineDiagnostics().operation().orElseThrow().outcome()),
                () -> {
                    var failure = view.engineDiagnostics().failure().orElseThrow();
                    assertEquals(FailureStage.CLOSING, failure.stage());
                    switch (failure.subject()) {
                        case FailureSubject.Retirement subject -> {
                            assertEquals(retained.batchId(), subject.batchId());
                            assertEquals(previous.attemptId(), subject.sourceAttemptId());
                        }
                        case FailureSubject.Unit subject -> {
                            assertEquals(retained.batchId(), subject.ownerId());
                            assertEquals(unitFence, subject.fence());
                        }
                        default -> fail("closeAdmission must retain its exact retirement or unit owner");
                    }
                },
                () -> assertFalse(events.stream().anyMatch(value -> value.startsWith("drain:")
                    || value.startsWith("stop:") || value.startsWith("retire:")
                    || value.startsWith("driver-close:")), "unproven generation must keep its resources"),
                () -> assertEquals(0, store.closes),
                () -> assertFalse(alpha.services.scope().isClosed())
            );
        } finally {
            Schedulers.removeExecutorServiceDecorator(decoratorKey);
        }
    }

    @Test
    void candidateAbortFailureDuringHostCloseRetainsItsOwnerAndSkipsSharedRelease() {
        alpha.abortFailure = true;
        beta.sealFailure = true;
        var engine = engine(request -> { });
        assertThrows(EngineChangeException.class,
            () -> apply(engine, 0, graph(1, "a1", "b1")));
        var candidate = engine.snapshot().candidate().orElseThrow();
        events.clear();

        var first = assertThrows(RuntimeException.class,
            () -> engine.closeAsync().block(Duration.ofSeconds(5)));
        var firstEvents = List.copyOf(events);
        var second = assertThrows(RuntimeException.class,
            () -> engine.closeAsync().block(Duration.ofSeconds(5)));
        var view = engine.published().current();
        var retained = view.engine().candidate().orElseThrow();
        var failure = view.engineDiagnostics().failure().orElseThrow();

        assertSame(first, second);
        assertEquals("abort failed", first.getCause().getMessage());
        assertEquals(firstEvents, events, "cached close must not retry candidate cleanup");
        assertTrue(events.contains("abort:alpha"));
        assertTrue(events.contains("close:beta"));
        assertEquals(EngineState.FAIL_STOP, view.engine().state());
        assertEquals(candidate.attemptId(), retained.attemptId());
        assertEquals(CandidatePhase.FAILED, retained.phase());
        assertEquals(candidate.unitKeys(), retained.unitKeys());
        assertEquals(candidate.attemptId(),
            assertInstanceOf(FailureSubject.Candidate.class, failure.subject()).attemptId());
        assertEquals(FailureStage.CLOSING, failure.stage());
        assertFalse(view.engineDiagnostics().mutationGateOpen());
        assertFalse(view.engineDiagnostics().contributionAdmissionOpen());
        assertEquals(EngineOperationKind.SHUTDOWN,
            view.engineDiagnostics().operation().orElseThrow().kind());
        assertEquals(EngineOperationOutcome.FAILED,
            view.engineDiagnostics().operation().orElseThrow().outcome());
        assertFalse(events.stream().anyMatch(value -> value.startsWith("driver-close:")));
        assertEquals(0, store.closes);
        assertFalse(alpha.services.scope().isClosed());
    }

    @Test
    void recoveredCandidateCleanupDoesNotReclassifyLaterStoreCloseFailure() {
        var packageRoot = root.resolve("candidate-cleanup-then-store-failure");
        var storeFailure = new IllegalStateException("store close failed after candidate cleanup");
        alpha.abortFailure = true;
        beta.sealFailure = true;
        var engine = FibraEngine.builder(new PluginPackageStore(packageRoot), store)
            .runtimeProvider(alpha).runtimeProvider(beta)
            .hostTerminationPort(request -> { }).build();
        engine.startAsync().block(Duration.ofSeconds(5));
        assertThrows(EngineChangeException.class,
            () -> apply(engine, 0, graph(1, "a1", "b1")));
        var failedCandidate = engine.snapshot().candidate().orElseThrow();
        alpha.abortFailure = false;
        store.closeFailure = storeFailure;
        events.clear();

        var first = assertThrows(RuntimeException.class,
            () -> engine.closeAsync().block(Duration.ofSeconds(5)));
        var firstEvents = List.copyOf(events);
        var second = assertThrows(RuntimeException.class,
            () -> engine.closeAsync().block(Duration.ofSeconds(5)));

        assertAll(
            () -> assertSame(first, second),
            () -> assertTrue(containsFailure(first, storeFailure),
                "later shared failure must not be replaced by a missing-candidate error"),
            () -> assertTrue(first == storeFailure || first.getCause() == storeFailure,
                "candidate cleanup catch must preserve the original shared failure as primary"),
            () -> assertTrue(engine.snapshot().candidate().isEmpty(),
                "cleaned candidate " + failedCandidate.attemptId() + " must be removed"),
            () -> assertClosingFailure(engine, "shared store failure after recovered candidate cleanup"),
            () -> assertEquals(firstEvents, events, "cached close must not re-enter cleanup"),
            () -> assertTrue(events.containsAll(List.of("abort:alpha", "close:beta",
                "driver-close:beta", "driver-close:alpha"))),
            () -> assertTrue(alpha.services.scope().isClosed()),
            () -> assertConstructionStoresReleased(packageRoot, store)
        );
    }

    @Test
    void retirementSnapshotFailureDuringHostCloseKeepsExactUnitFence() {
        var engine = engine(request -> { });
        apply(engine, 0, graph(1, "a1"));
        var previous = engine.snapshot().current().orElseThrow();
        var originalFence = alpha.units.get("a1").fence();
        alpha.snapshotFailure = true;
        events.clear();

        var first = assertThrows(IllegalStateException.class,
            () -> engine.closeAsync().block(Duration.ofSeconds(5)));
        var firstEvents = List.copyOf(events);
        var second = assertThrows(IllegalStateException.class,
            () -> engine.closeAsync().block(Duration.ofSeconds(5)));
        var view = engine.published().current();
        var retirement = view.engine().retirementBatch().orElseThrow();
        var failure = view.engineDiagnostics().failure().orElseThrow();

        assertAll(
            () -> assertSame(first, second),
            () -> assertEquals("snapshot contract violated", first.getMessage()),
            () -> assertEquals(firstEvents, events, "cached close must not resample or clean retained resources"),
            () -> assertEquals(EngineState.FAIL_STOP, view.engine().state()),
            () -> assertTrue(view.engine().current().isEmpty()),
            () -> assertEquals(previous.attemptId(), retirement.sourceAttemptId()),
            () -> assertEquals(RetirementPhase.FAILED, retirement.phase()),
            () -> assertEquals(previous.observations(), retirement.observations()),
            () -> {
                var unit = assertInstanceOf(FailureSubject.Unit.class, failure.subject());
                assertEquals(retirement.batchId(), unit.ownerId());
                assertEquals(originalFence, unit.fence());
            },
            () -> assertEquals("RUNTIME_SNAPSHOT_CONTRACT_VIOLATION", failure.reason()),
            () -> assertEquals(FailureStage.OBSERVING, failure.stage()),
            () -> assertFalse(view.engineDiagnostics().mutationGateOpen()),
            () -> assertFalse(view.engineDiagnostics().contributionAdmissionOpen()),
            () -> assertEquals(EngineOperationKind.SHUTDOWN,
                view.engineDiagnostics().operation().orElseThrow().kind()),
            () -> assertEquals(EngineOperationOutcome.FAILED,
                view.engineDiagnostics().operation().orElseThrow().outcome()),
            () -> assertTrue(events.contains("drain:a1")),
            () -> assertFalse(events.stream().anyMatch(value -> value.startsWith("stop:")
                || value.startsWith("retire:") || value.startsWith("driver-close:"))),
            () -> assertEquals(0, store.closes),
            () -> assertFalse(alpha.services.scope().isClosed())
        );
    }

    @Test
    void closeWaitsForAcceptedBootstrapAndDoesNotReopenAfterFirstClose() throws Exception {
        try (var seed = engine(request -> { })) {
            apply(seed, 0, graph(1, "a1"));
        }
        store.reopen();
        var closesBeforeRestart = store.closes;
        var targetBeforeRestart = store.current;
        events.clear();
        var entered = Sinks.<Void>one();
        var release = Sinks.<Void>one();
        alpha.prepareEntered = entered;
        alpha.prepareRelease = release;
        var engine = FibraEngine.builder(new PluginPackageStore(root.resolve("bootstrap-close")), store)
            .runtimeProvider(alpha).runtimeProvider(beta)
            .hostTerminationPort(request -> { }).build();
        try {
            var receiptEvents = new AtomicReference<List<String>>();
            var bootstrap = engine.startAsync().doOnNext(view -> {
                events.add("bootstrap-receipt");
                receiptEvents.set(List.copyOf(events));
            }).toFuture();
            entered.asMono().block(Duration.ofSeconds(5));
            var acceptedOperation = engine.published().current().engineDiagnostics()
                .operation().orElseThrow();
            var sameBootstrap = engine.startAsync().toFuture();
            var close = engine.closeAsync().toFuture();

            assertFalse(bootstrap.isDone());
            assertFalse(close.isDone(), "close must wait for the accepted bootstrap owner");
            assertEquals(closesBeforeRestart, store.closes);
            assertFalse(events.stream().anyMatch(value -> value.startsWith("driver-close:")));
            assertThrows(IllegalStateException.class,
                () -> engine.submit(new ReconcileCurrent()).block(Duration.ofSeconds(5)));
            release.tryEmitEmpty();

            var bootstrapView = bootstrap.get(5, TimeUnit.SECONDS);
            assertSame(bootstrapView, sameBootstrap.get(5, TimeUnit.SECONDS));
            var completedBootstrap = bootstrapView.engineDiagnostics().operation().orElseThrow();
            close.get(5, TimeUnit.SECONDS);
            var finalView = engine.published().current();
            var completedEvents = List.copyOf(events);
            assertEquals(EngineState.CLOSED, finalView.engine().state());
            assertEquals(EngineOperationKind.SHUTDOWN,
                finalView.engineDiagnostics().operation().orElseThrow().kind());
            assertEquals(EngineOperationOutcome.SUCCEEDED,
                finalView.engineDiagnostics().operation().orElseThrow().outcome());
            assertSame(finalView, engine.startAsync().block(Duration.ofSeconds(5)));
            engine.closeAsync().block(Duration.ofSeconds(5));
            assertEquals(completedEvents, events);
            assertEquals(1, Collections.frequency(events, "prepare:alpha"));
            assertEquals(1, Collections.frequency(events, "driver-close:alpha"));
            assertEquals(1, Collections.frequency(events, "driver-close:beta"));
            assertEquals(closesBeforeRestart + 1, store.closes);
            assertEquals(1, store.saves);
            assertAll("accepted prepared bootstrap must finish its own candidate before its receipt",
                () -> assertEquals(acceptedOperation.operationId(), completedBootstrap.operationId()),
                () -> assertEquals(EngineOperationKind.BOOTSTRAP, completedBootstrap.kind()),
                () -> assertEquals(EngineOperationOutcome.FAILED, completedBootstrap.outcome()),
                () -> assertEquals(EngineOperationStage.PREPARING, completedBootstrap.stage()),
                () -> assertEquals(TargetSaveState.NOT_APPLICABLE, completedBootstrap.targetSaveState()),
                () -> assertEquals(DurableTargetState.PRESENT, bootstrapView.engine().durableState()),
                () -> assertEquals(TargetConvergence.BLOCKED, bootstrapView.engine().targetConvergence()),
                () -> assertTrue(bootstrapView.engine().candidate().isEmpty()),
                () -> assertTrue(bootstrapView.engine().current().isEmpty()),
                () -> assertTrue(bootstrapView.engine().retirementBatch().isEmpty()),
                () -> {
                    assertTrue(bootstrapView.engineDiagnostics().failure().isPresent(),
                        "failed bootstrap receipt must retain its DurableTarget failure");
                    var failure = bootstrapView.engineDiagnostics().failure().orElseThrow();
                    assertEquals("BOOTSTRAP_TARGET_BLOCKED", failure.reason());
                    assertEquals(FailureStage.PREPARING, failure.stage());
                    var subject = assertInstanceOf(FailureSubject.DurableTarget.class, failure.subject());
                    assertEquals(targetBeforeRestart.targetRevision(), subject.targetRevision());
                    assertEquals(targetBeforeRestart.targetDigest(), subject.targetDigest());
                },
                () -> assertFalse(events.stream().anyMatch(value -> value.startsWith("seal:")
                    || value.startsWith("activate:") || value.startsWith("save:"))),
                () -> assertEquals(1, Collections.frequency(events, "close:alpha")),
                () -> assertTrue(receiptEvents.get().indexOf("close:alpha") >= 0
                    && receiptEvents.get().indexOf("close:alpha")
                        < receiptEvents.get().indexOf("bootstrap-receipt"),
                    "candidate cleanup must precede the bootstrap receipt")
            );
        } finally {
            release.tryEmitEmpty();
            alpha.prepareEntered = null;
            alpha.prepareRelease = null;
            engine.closeAsync().block(Duration.ofSeconds(5));
        }

        var unopenedStore = new Store();
        var neverStarted = FibraEngine.builder(new PluginPackageStore(root.resolve("close-before-start")), unopenedStore)
            .runtimeProvider(alpha).runtimeProvider(beta)
            .hostTerminationPort(request -> { }).build();
        neverStarted.closeAsync().block(Duration.ofSeconds(5));
        var closedEvents = List.copyOf(events);
        assertThrows(IllegalStateException.class,
            () -> neverStarted.startAsync().block(Duration.ofSeconds(5)));
        assertEquals(EngineState.CLOSED, neverStarted.snapshot().state());
        assertEquals(closedEvents, events, "first start after close cannot reopen resources");
        assertEquals(1, unopenedStore.closes);
    }

    @Test
    void closeWaitsForAcceptedObservationBeforeFreezingItsFinalView() throws Exception {
        var engine = engine(request -> { });
        apply(engine, 0, graph(1, "a1"));
        var unit = alpha.units.get("a1");
        var fence = unit.fence();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        unit.snapshotEntered = entered;
        unit.snapshotRelease = release;
        var samplesBefore = unit.snapshotCalls.get();
        events.clear();
        try {
            alpha.services.requestObservationRefresh(fence);
            assertTrue(entered.await(5, TimeUnit.SECONDS), "real observer must enter snapshot before close");
            var close = engine.closeAsync().toFuture();
            assertFalse(close.isDone(), "close must not pass the accepted observer writer");
            assertEquals(0, store.closes);
            assertFalse(events.stream().anyMatch(value -> value.startsWith("driver-close:")));
            alpha.services.requestObservationRefresh(fence);
            assertEquals(samplesBefore + 1, unit.snapshotCalls.get());
            release.countDown();
            close.get(5, TimeUnit.SECONDS);

            var finalView = engine.published().current();
            var completedEvents = List.copyOf(events);
            assertEquals(EngineState.CLOSED, finalView.engine().state());
            assertEquals(EngineOperationOutcome.SUCCEEDED,
                finalView.engineDiagnostics().operation().orElseThrow().outcome());
            assertFalse(finalView.engineDiagnostics().mutationGateOpen());
            assertFalse(finalView.engineDiagnostics().contributionAdmissionOpen());
            // One accepted observer plus the existing post-drain and post-stop retirement samples.
            assertEquals(samplesBefore + 3, unit.snapshotCalls.get());
            alpha.services.requestObservationRefresh(fence);
            alpha.services.requestReconcile(Set.of(fence), "late-after-close");
            engine.closeAsync().block(Duration.ofSeconds(5));
            assertSame(finalView, engine.published().current());
            assertEquals(samplesBefore + 3, unit.snapshotCalls.get());
            assertEquals(completedEvents, events);
            assertEquals(1, Collections.frequency(events, "driver-close:alpha"));
            assertEquals(1, Collections.frequency(events, "driver-close:beta"));
            assertEquals(1, store.closes);
        } finally {
            release.countDown();
            engine.closeAsync().block(Duration.ofSeconds(5));
            unit.snapshotEntered = null;
            unit.snapshotRelease = null;
        }
    }

    @Test
    void closeDuringTargetLoadRejectsBootstrapBeforeCreatingItsOperation() throws Exception {
        try (var seed = engine(request -> { })) {
            apply(seed, 0, graph(1, "a1"));
        }
        store.reopen();
        var loadsBeforeRestart = store.loads;
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        store.loadEntered = entered;
        store.loadRelease = release;
        var engine = FibraEngine.builder(new PluginPackageStore(root.resolve("load-before-bootstrap-close")), store)
            .runtimeProvider(alpha).runtimeProvider(beta)
            .hostTerminationPort(request -> { }).build();
        events.clear();
        try {
            var bootstrap = engine.startAsync().toFuture();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var sameBootstrap = engine.startAsync().toFuture();
            var beforeClose = engine.published().current();
            assertEquals(EngineState.NEW, beforeClose.engine().state());
            assertTrue(beforeClose.engineDiagnostics().operation().isEmpty());
            var close = engine.closeAsync().toFuture();
            assertFalse(close.isDone());
            release.countDown();

            PublishedView returned = null;
            Throwable rejected = null;
            try { returned = bootstrap.get(5, TimeUnit.SECONDS); }
            catch (ExecutionException error) { rejected = error.getCause(); }
            PublishedView sameReturned = null;
            Throwable sameRejected = null;
            try { sameReturned = sameBootstrap.get(5, TimeUnit.SECONDS); }
            catch (ExecutionException error) { sameRejected = error.getCause(); }
            close.get(5, TimeUnit.SECONDS);
            var completedEvents = List.copyOf(events);
            PublishedView cachedReturned = null;
            Throwable cachedRejected = null;
            try { cachedReturned = engine.startAsync().block(Duration.ofSeconds(5)); }
            catch (RuntimeException error) { cachedRejected = error; }
            org.slf4j.LoggerFactory.getLogger(RuntimeDriverEngineTest.class).info(
                "load-close qualification: startError={}, returnedOperation={}, returnedState={}, finalState={}, concurrentError={}, cachedError={}, loads={}",
                rejected, returned == null ? "no view" : returned.engineDiagnostics().operation(),
                returned == null ? "no view" : returned.engine().state(), engine.snapshot().state(),
                sameRejected, cachedRejected, store.loads);

            assertEquals(EngineState.CLOSED, engine.snapshot().state());
            assertFalse(events.stream().anyMatch(value -> value.startsWith("prepare:")
                || value.startsWith("seal:") || value.startsWith("activate:")));
            assertTrue(engine.published().current().engineDiagnostics().terminationRequest().isEmpty());
            assertEquals(2, store.closes);
            assertEquals(1, store.saves);
            assertEquals(loadsBeforeRestart + 1, store.loads);
            engine.closeAsync().block(Duration.ofSeconds(5));
            assertEquals(completedEvents, events);
            var firstError = rejected;
            var concurrentError = sameRejected;
            var cachedError = cachedRejected;
            var firstView = returned;
            var concurrentView = sameReturned;
            var cachedView = cachedReturned;
            assertAll("pre-operation refusal must keep the existing start error cache",
                () -> assertInstanceOf(MutationGateClosedException.class, firstError,
                    "pre-operation bootstrap refusal must be returned as the original control error"),
                () -> assertInstanceOf(MutationGateClosedException.class, concurrentError),
                () -> assertSame(firstError, concurrentError),
                () -> assertInstanceOf(IllegalStateException.class, cachedError),
                () -> assertNull(firstView),
                () -> assertNull(concurrentView),
                () -> assertNull(cachedView)
            );
        } finally {
            release.countDown();
            engine.closeAsync().block(Duration.ofSeconds(5));
            store.loadRelease = null;
            store.loadEntered = null;
        }
    }

    @Test
    void closeBeforeReconcileDeploymentDoesNotReusePreviousOperationOrFailCurrent() throws Exception {
        var engine = engine(request -> { });
        apply(engine, 0, graph(1, "a1"));
        var previous = engine.published().current();
        var unit = alpha.units.get("a1");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        unit.scriptSnapshots(ExecutionObservation.State.FAILED);
        unit.snapshotEntered = entered;
        unit.snapshotRelease = release;
        events.clear();
        try {
            var reconcile = engine.submit(new ReconcileCurrent()).toFuture();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(previous.engineDiagnostics().operation(),
                engine.published().current().engineDiagnostics().operation());
            var close = engine.closeAsync().toFuture();
            assertFalse(close.isDone());
            release.countDown();

            var rejected = assertThrows(ExecutionException.class,
                () -> reconcile.get(5, TimeUnit.SECONDS)).getCause();
            close.get(5, TimeUnit.SECONDS);
            var finalView = engine.published().current();
            org.slf4j.LoggerFactory.getLogger(RuntimeDriverEngineTest.class).info(
                "reconcile-close qualification: priorOperation={}, commandError={}, finalState={}, terminationRequest={}",
                previous.engineDiagnostics().operation(), rejected, finalView.engine().state(),
                finalView.engineDiagnostics().terminationRequest());

            assertInstanceOf(MutationGateClosedException.class, rejected);
            assertEquals(1, unit.scriptedSnapshotCalls.get());
            assertFalse(events.stream().anyMatch(value -> value.startsWith("prepare:")
                || value.startsWith("seal:") || value.startsWith("activate:")));
            assertEquals(EngineState.CLOSED, finalView.engine().state());
            assertEquals(EngineOperationKind.SHUTDOWN,
                finalView.engineDiagnostics().operation().orElseThrow().kind());
            assertEquals(EngineOperationOutcome.SUCCEEDED,
                finalView.engineDiagnostics().operation().orElseThrow().outcome());
            assertTrue(finalView.engineDiagnostics().terminationRequest().isEmpty());
            assertTrue(finalView.engineDiagnostics().failure().isEmpty());
            assertEquals(1, store.saves);
            assertEquals(1, store.closes);
        } finally {
            release.countDown();
            engine.closeAsync().block(Duration.ofSeconds(5));
            unit.snapshotRelease = null;
            unit.snapshotEntered = null;
        }
    }

    @Test
    void closeDuringApplyPrepareKeepsPriorCurrentUntilShutdown() throws Exception {
        verifyCloseDuringCommandPrepare(false);
    }

    @Test
    void closeDuringReconcilePrepareKeepsPriorCurrentUntilShutdown() throws Exception {
        verifyCloseDuringCommandPrepare(true);
    }

    private void verifyCloseDuringCommandPrepare(boolean reconcile) throws Exception {
        var engine = engine(request -> { });
        apply(engine, 0, graph(1, "a1"));
        var prior = engine.snapshot();
        var priorCurrent = prior.current().orElseThrow();
        var priorTarget = store.current;
        var unit = alpha.units.get("a1");
        var priorFence = unit.fence();
        var loadsBefore = store.loads;
        var entered = Sinks.<Void>one();
        var release = Sinks.<Void>one();
        alpha.prepareEntered = entered;
        alpha.prepareRelease = release;
        if (reconcile) unit.scriptSnapshots(ExecutionObservation.State.FAILED);
        events.clear();
        try {
            EngineCommand command = reconcile ? new ReconcileCurrent()
                : ApplyDeployment.builder(graph(2, "a1")).expectedRevision(1)
                    .selections(List.of(alpha.metadata().selection(true), beta.metadata().selection(true)))
                    .configContext(ConfigContextSnapshot.empty()).build();
            var receiptEvents = new AtomicReference<List<String>>();
            var submitted = engine.submit(command).doOnError(error -> {
                events.add("command-receipt");
                receiptEvents.set(List.copyOf(events));
            }).toFuture();
            entered.asMono().block(Duration.ofSeconds(5));
            var preparing = engine.published().current();
            var acceptedOperation = preparing.engineDiagnostics().operation().orElseThrow();
            var acceptedCandidate = preparing.engine().candidate().orElseThrow();
            assertEquals(priorCurrent.attemptId(), preparing.engine().current().orElseThrow().attemptId());
            var close = engine.closeAsync().toFuture();
            assertFalse(submitted.isDone());
            assertFalse(close.isDone());
            assertEquals(0, store.closes);
            release.tryEmitEmpty();

            var rejected = assertThrows(ExecutionException.class,
                () -> submitted.get(5, TimeUnit.SECONDS)).getCause();
            close.get(5, TimeUnit.SECONDS);
            var finalView = engine.published().current();
            var completedEvents = List.copyOf(events);
            var change = assertInstanceOf(EngineChangeException.class, rejected);
            assertInstanceOf(MutationGateClosedException.class, change.getCause());
            var failed = change.view();
            var failedOperation = failed.engineDiagnostics().operation().orElseThrow();
            var failure = failed.engineDiagnostics().failure().orElseThrow();

            assertEquals(reconcile ? EngineOperationKind.RECONCILE : EngineOperationKind.APPLY,
                failedOperation.kind());
            assertEquals(acceptedOperation.operationId(), failedOperation.operationId());
            assertEquals(EngineOperationOutcome.FAILED, failedOperation.outcome());
            assertEquals(EngineOperationStage.PREPARING, failedOperation.stage());
            assertEquals(reconcile ? TargetSaveState.NOT_APPLICABLE : TargetSaveState.NOT_SAVED,
                change.targetSaveState());
            assertEquals(change.targetSaveState(), failedOperation.targetSaveState());
            assertEquals("CANDIDATE_FAILED", failure.reason());
            assertEquals(FailureStage.PREPARING, failure.stage());
            assertEquals(acceptedCandidate.attemptId(),
                assertInstanceOf(FailureSubject.Candidate.class, failure.subject()).attemptId());
            assertTrue(failed.engine().candidate().isEmpty());
            assertTrue(failed.engine().retirementBatch().isEmpty());
            assertEquals(priorCurrent, failed.engine().current().orElseThrow());
            assertEquals(priorFence, alpha.units.get("a1").fence());
            assertSame(priorTarget, failed.engine().target().orElseThrow());
            assertSame(priorTarget, store.current);
            assertEquals(DurableTargetState.PRESENT, failed.engine().durableState());
            assertEquals(loadsBefore, store.loads);
            assertEquals(1, store.saves);
            assertFalse(events.stream().anyMatch(value -> value.startsWith("seal:")
                || value.startsWith("activate:") || value.startsWith("save:")));
            assertEquals(1, Collections.frequency(events, "prepare:alpha"));
            assertEquals(1, Collections.frequency(events, "close:alpha"));
            assertTrue(receiptEvents.get().indexOf("close:alpha") >= 0
                && receiptEvents.get().indexOf("close:alpha") < receiptEvents.get().indexOf("command-receipt"));
            assertEquals(EngineState.CLOSED, finalView.engine().state());
            assertTrue(finalView.engine().current().isEmpty());
            assertTrue(finalView.engine().retirementBatch().isEmpty());
            assertEquals(EngineOperationKind.SHUTDOWN,
                finalView.engineDiagnostics().operation().orElseThrow().kind());
            assertEquals(EngineOperationOutcome.SUCCEEDED,
                finalView.engineDiagnostics().operation().orElseThrow().outcome());
            assertTrue(finalView.engineDiagnostics().terminationRequest().isEmpty());
            assertEquals(1, Collections.frequency(events, "drain:a1"));
            assertEquals(1, Collections.frequency(events, "stop:a1"));
            assertEquals(1, Collections.frequency(events, "retire:alpha"));
            assertEquals(1, Collections.frequency(events, "driver-close:alpha"));
            assertEquals(1, Collections.frequency(events, "driver-close:beta"));
            assertEquals(1, store.closes);
            engine.closeAsync().block(Duration.ofSeconds(5));
            assertSame(finalView, engine.published().current());
            assertEquals(completedEvents, events);
        } finally {
            release.tryEmitEmpty();
            alpha.prepareEntered = null;
            alpha.prepareRelease = null;
            engine.closeAsync().block(Duration.ofSeconds(5));
        }
    }

    @Test
    void closeDuringBootstrapPrepareKeepsFailedCandidateCleanupAndOriginalRefusal() throws Exception {
        try (var seed = engine(request -> { })) {
            apply(seed, 0, graph(1, "a1"));
        }
        store.reopen();
        var closesBefore = store.closes;
        var loadsBefore = store.loads;
        var cleanupFailure = new IllegalStateException("prepared candidate close failed");
        alpha.candidateCloseFailure = cleanupFailure;
        var entered = Sinks.<Void>one();
        var release = Sinks.<Void>one();
        alpha.prepareEntered = entered;
        alpha.prepareRelease = release;
        var engine = FibraEngine.builder(new PluginPackageStore(root.resolve("bootstrap-cleanup-failure")), store)
            .runtimeProvider(alpha).runtimeProvider(beta)
            .hostTerminationPort(request -> { }).build();
        events.clear();
        try {
            var bootstrap = engine.startAsync().toFuture();
            entered.asMono().block(Duration.ofSeconds(5));
            var preparing = engine.published().current();
            var candidate = preparing.engine().candidate().orElseThrow();
            var operation = preparing.engineDiagnostics().operation().orElseThrow();
            var sameBootstrap = engine.startAsync().toFuture();
            var close = engine.closeAsync().toFuture();
            assertFalse(bootstrap.isDone());
            assertFalse(close.isDone());
            release.tryEmitEmpty();

            var first = assertThrows(ExecutionException.class,
                () -> bootstrap.get(5, TimeUnit.SECONDS)).getCause();
            assertSame(first, assertThrows(ExecutionException.class,
                () -> sameBootstrap.get(5, TimeUnit.SECONDS)).getCause());
            var change = assertInstanceOf(EngineChangeException.class, first);
            var refusal = assertInstanceOf(MutationGateClosedException.class, change.getCause());
            assertEquals(1, refusal.getSuppressed().length);
            assertSame(cleanupFailure, refusal.getSuppressed()[0].getCause());
            var failed = change.view();
            var retained = failed.engine().candidate().orElseThrow();
            var failure = failed.engineDiagnostics().failure().orElseThrow();
            assertEquals(EngineState.FAIL_STOP, failed.engine().state());
            assertEquals(candidate.attemptId(), retained.attemptId());
            assertEquals(CandidatePhase.FAILED, retained.phase());
            assertTrue(failed.engine().current().isEmpty());
            assertTrue(failed.engine().retirementBatch().isEmpty());
            assertEquals("CANDIDATE_CLEANUP_FAILED", failure.reason());
            assertEquals(FailureStage.CLOSING, failure.stage());
            assertEquals(candidate.attemptId(),
                assertInstanceOf(FailureSubject.Candidate.class, failure.subject()).attemptId());
            assertEquals(operation.operationId(), failed.engineDiagnostics().operation().orElseThrow().operationId());
            assertEquals(EngineOperationKind.BOOTSTRAP, failed.engineDiagnostics().operation().orElseThrow().kind());
            assertEquals(EngineOperationOutcome.FAILED, failed.engineDiagnostics().operation().orElseThrow().outcome());
            assertEquals(EngineOperationStage.PREPARING, failed.engineDiagnostics().operation().orElseThrow().stage());
            assertEquals(TargetSaveState.NOT_APPLICABLE, change.targetSaveState());
            assertFalse(failed.engineDiagnostics().mutationGateOpen());
            assertFalse(failed.engineDiagnostics().contributionAdmissionOpen());
            assertEquals(failure.subject(), failed.engineDiagnostics().terminationRequest().orElseThrow().subject());

            var closeError = assertThrows(ExecutionException.class,
                () -> close.get(5, TimeUnit.SECONDS)).getCause();
            assertSame(cleanupFailure, closeError.getCause());
            var finalView = engine.published().current();
            var completedEvents = List.copyOf(events);
            var cachedStart = assertThrows(EngineChangeException.class,
                () -> engine.startAsync().block(Duration.ofSeconds(5)));
            assertSame(finalView, cachedStart.view());
            assertEquals(TargetSaveState.NOT_APPLICABLE, cachedStart.targetSaveState());
            assertSame(closeError, assertThrows(RuntimeException.class,
                () -> engine.closeAsync().block(Duration.ofSeconds(5))));
            assertEquals(completedEvents, events);
            assertEquals(EngineState.FAIL_STOP, finalView.engine().state());
            assertEquals(candidate.attemptId(), finalView.engine().candidate().orElseThrow().attemptId());
            assertEquals(CandidatePhase.FAILED, finalView.engine().candidate().orElseThrow().phase());
            var closeFailure = finalView.engineDiagnostics().failure().orElseThrow();
            assertEquals(candidate.attemptId(),
                assertInstanceOf(FailureSubject.Candidate.class, closeFailure.subject()).attemptId());
            assertEquals("HOST_CLOSE_FAILED", closeFailure.reason());
            assertEquals(FailureStage.CLOSING, closeFailure.stage());
            assertEquals(EngineOperationKind.SHUTDOWN, finalView.engineDiagnostics().operation().orElseThrow().kind());
            assertEquals(EngineOperationOutcome.FAILED, finalView.engineDiagnostics().operation().orElseThrow().outcome());
            assertFalse(finalView.engineDiagnostics().mutationGateOpen());
            assertFalse(finalView.engineDiagnostics().contributionAdmissionOpen());
            assertEquals(1, Collections.frequency(events, "prepare:alpha"));
            assertEquals(1, Collections.frequency(events, "close:alpha"), "candidate failure is an idempotent terminal result");
            assertFalse(events.stream().anyMatch(value -> value.startsWith("seal:")
                || value.startsWith("activate:") || value.startsWith("save:")
                || value.startsWith("driver-close:") || value.startsWith("drain:")
                || value.startsWith("stop:") || value.startsWith("retire:")));
            assertEquals(loadsBefore + 1, store.loads);
            assertEquals(closesBefore, store.closes);
            assertEquals(1, store.saves);
            assertFalse(alpha.services.scope().isClosed());
        } finally {
            release.tryEmitEmpty();
            alpha.prepareEntered = null;
            alpha.prepareRelease = null;
            try { engine.closeAsync().block(Duration.ofSeconds(5)); }
            catch (RuntimeException expectedCleanupFailure) { /* The candidate retains its failed close result. */ }
        }
    }

    private void verifyHostCloseFailure(int mode) {
        var packageRoot = root.resolve("host-close-failure-" + mode);
        var targetStore = new Store();
        var storeFailure = new IllegalStateException("target close failed " + mode);
        targetStore.closeFailure = storeFailure;
        var engine = FibraEngine.builder(new PluginPackageStore(packageRoot), targetStore)
            .runtimeProvider(alpha).runtimeProvider(beta)
            .hostTerminationPort(request -> { }).build();
        if (mode > 0) engine.startAsync().block(Duration.ofSeconds(5));
        if (mode > 1) apply(engine, 0, graph(1, "a1"));

        var first = assertThrows(RuntimeException.class,
            () -> engine.closeAsync().block(Duration.ofSeconds(5)));
        var second = assertThrows(RuntimeException.class,
            () -> engine.closeAsync().block(Duration.ofSeconds(5)));

        assertSame(first, second, "close result must be cached for mode " + mode);
        assertTrue(containsFailure(first, storeFailure));
        assertConstructionStoresReleased(packageRoot, targetStore);
        assertClosingFailure(engine, "mode " + mode);
    }

    @Test
    void hostCloseKeepsFirstDriverFailureAndReleasesIndependentLaterResources() {
        var packageRoot = root.resolve("driver-close-failures");
        var firstFailure = new IllegalStateException("beta close failed first");
        var secondFailure = new IllegalStateException("alpha close failed second");
        var storeFailure = new IllegalStateException("target close failed third");
        beta.driverCloseFailure = firstFailure;
        alpha.driverCloseFailure = secondFailure;
        store.closeFailure = storeFailure;
        var engine = FibraEngine.builder(new PluginPackageStore(packageRoot), store)
            .runtimeProvider(alpha).runtimeProvider(beta)
            .hostTerminationPort(request -> { }).build();
        engine.startAsync().block(Duration.ofSeconds(5));

        var thrown = assertThrows(RuntimeException.class,
            () -> engine.closeAsync().block(Duration.ofSeconds(5)));

        assertEquals(List.of("driver-close:beta", "driver-close:alpha"), events);
        assertTrue(thrown == firstFailure || thrown.getCause() == firstFailure,
            "later cleanup must not replace the first resource failure");
        assertTrue(containsFailure(thrown, secondFailure));
        assertTrue(containsFailure(thrown, storeFailure));
        assertTrue(alpha.services.scope().isClosed());
        assertTrue(beta.services.scope().isClosed());
        assertConstructionStoresReleased(packageRoot, store);
        assertClosingFailure(engine, "independent host resources");
    }

    @Test
    void hostClosePublishesClosingBeforeWaitingAndSurvivesWaiterCancellation() {
        for (boolean started : List.of(false, true)) {
            events.clear();
            var targetStore = new Store();
            var entered = Sinks.<Void>one();
            var release = Sinks.<Void>one();
            beta.driverCloseEntered = entered;
            beta.driverCloseRelease = release;
            var engine = FibraEngine.builder(new PluginPackageStore(
                    root.resolve("close-wait-" + started)), targetStore)
                .runtimeProvider(alpha).runtimeProvider(beta)
                .hostTerminationPort(request -> { }).build();
            if (started) engine.startAsync().block(Duration.ofSeconds(5));
            var waiter = engine.closeAsync().subscribe();
            PublishedView duringClose;
            try {
                entered.asMono().block(Duration.ofSeconds(5));
                duringClose = engine.published().current();
                waiter.dispose();
            } finally {
                release.tryEmitEmpty();
                engine.closeAsync().block(Duration.ofSeconds(5));
                beta.driverCloseEntered = null;
                beta.driverCloseRelease = null;
            }

            assertEquals(EngineState.CLOSING, duringClose.engine().state());
            assertFalse(duringClose.engineDiagnostics().mutationGateOpen());
            assertFalse(duringClose.engineDiagnostics().contributionAdmissionOpen());
            assertEquals(EngineOperationKind.SHUTDOWN,
                duringClose.engineDiagnostics().operation().orElseThrow().kind());
            assertEquals(EngineState.CLOSED, engine.snapshot().state());
            assertEquals(EngineOperationOutcome.SUCCEEDED,
                engine.published().current().engineDiagnostics().operation().orElseThrow().outcome());
            assertEquals(List.of("driver-close:beta", "driver-close:alpha"), events);
            assertEquals(1, targetStore.closes);
        }
    }

    private static void assertClosingFailure(FibraEngine engine, String context) {
        var view = engine.published().current();
        assertEquals(EngineState.CLOSING, view.engine().state(), context);
        assertFalse(view.engineDiagnostics().mutationGateOpen(), context);
        assertFalse(view.engineDiagnostics().contributionAdmissionOpen(), context);
        var operation = view.engineDiagnostics().operation().orElseThrow();
        assertEquals(EngineOperationKind.SHUTDOWN, operation.kind(), context);
        assertEquals(EngineOperationOutcome.FAILED, operation.outcome(), context);
        var failure = view.engineDiagnostics().failure().orElseThrow();
        assertInstanceOf(FailureSubject.Engine.class, failure.subject(), context);
        assertEquals(FailureStage.CLOSING, failure.stage(), context);
    }

    @Test
    void hostCloseReleaseFailureNeverPublishesClosed() {
        verifyCommandLoopReleaseFailure(false);
    }

    @Test
    void hostClosePreservesResourceFailureWhenCommandLoopReleaseAlsoFails() {
        verifyCommandLoopReleaseFailure(true);
    }

    private void verifyCommandLoopReleaseFailure(boolean failStore) {
        var loopFailure = new IllegalStateException("command loop release failed");
        var storeFailure = new IllegalStateException("target store close failed first");
        if (failStore) store.closeFailure = storeFailure;
        var constructorThread = Thread.currentThread();
        var shutdowns = new AtomicInteger();
        var decorated = new AtomicInteger();
        var engineReference = new AtomicReference<FibraEngine>();
        var viewAtRelease = new AtomicReference<PublishedView>();
        var decoratorKey = "engine-close-release-" + UUID.randomUUID();
        Schedulers.addExecutorServiceDecorator(decoratorKey, (scheduler, executor) -> {
            var commandLoopConstruction = Thread.currentThread() == constructorThread
                && StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                    frame.getClassName().equals(EngineCommandLoop.class.getName())
                        && frame.getMethodName().equals("<init>")));
            if (!commandLoopConstruction) return executor;
            decorated.incrementAndGet();
            return new CloseFailureExecutor(executor, () -> {
                shutdowns.incrementAndGet();
                viewAtRelease.set(engineReference.get().published().current());
                throw loopFailure;
            });
        });
        try {
            var engine = engine(request -> { });
            engineReference.set(engine);
            var first = assertThrows(RuntimeException.class,
                () -> engine.closeAsync().block(Duration.ofSeconds(5)));
            var second = assertThrows(RuntimeException.class,
                () -> engine.closeAsync().block(Duration.ofSeconds(5)));

            assertEquals(1, decorated.get());
            assertEquals(1, shutdowns.get());
            assertSame(first, second);
            assertTrue(containsFailure(first, loopFailure));
            if (failStore) {
                assertTrue(first == storeFailure || first.getCause() == storeFailure,
                    "loop release failure must not replace the earlier resource failure");
            }
            assertEquals(EngineState.CLOSING, viewAtRelease.get().engine().state(),
                "CLOSED cannot be published before the final release request succeeds");
            assertClosingFailure(engine, "command loop release failure");
        } finally {
            Schedulers.removeExecutorServiceDecorator(decoratorKey);
        }
    }

    private static final class CloseFailureExecutor extends AbstractExecutorService
        implements ScheduledExecutorService {
        private final ScheduledExecutorService delegate;
        private final Runnable afterShutdown;
        CloseFailureExecutor(ScheduledExecutorService delegate, Runnable afterShutdown) {
            this.delegate = delegate;
            this.afterShutdown = afterShutdown;
        }
        public void shutdown() { delegate.shutdown(); }
        public List<Runnable> shutdownNow() {
            var pending = delegate.shutdownNow();
            afterShutdown.run();
            return pending;
        }
        public boolean isShutdown() { return delegate.isShutdown(); }
        public boolean isTerminated() { return delegate.isTerminated(); }
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
        public void execute(Runnable command) { delegate.execute(command); }
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return delegate.schedule(command, delay, unit);
        }
        public <V> ScheduledFuture<V> schedule(Callable<V> command, long delay, TimeUnit unit) {
            return delegate.schedule(command, delay, unit);
        }
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initial, long period, TimeUnit unit) {
            return delegate.scheduleAtFixedRate(command, initial, period, unit);
        }
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initial, long delay, TimeUnit unit) {
            return delegate.scheduleWithFixedDelay(command, initial, delay, unit);
        }
    }

    private static boolean containsFailure(Throwable thrown, Throwable expected) {
        if (thrown == expected) return true;
        if (thrown.getCause() != null && containsFailure(thrown.getCause(), expected)) return true;
        return Arrays.stream(thrown.getSuppressed()).anyMatch(value -> containsFailure(value, expected));
    }

    private FibraEngine engine(HostTerminationPort port) {
        return engine(port, HostCapabilitySnapshot::empty);
    }

    private FibraEngine engine(HostTerminationPort port,
                               Supplier<HostCapabilitySnapshot> capabilities) {
        var engine = FibraEngine.builder(new PluginPackageStore(root.resolve(UUID.randomUUID().toString())), store)
            .runtimeProvider(alpha).runtimeProvider(beta).hostTerminationPort(port)
            .capabilities(capabilities).build();
        engine.startAsync().block();
        return engine;
    }
    private PublishedView apply(FibraEngine engine, long expected, DesiredInputGraph graph) {
        return engine.submit(ApplyDeployment.builder(graph).expectedRevision(expected)
            .selections(List.of(alpha.metadata().selection(true), beta.metadata().selection(true)))
            .configContext(ConfigContextSnapshot.empty()).build()).block().view();
    }
    private static DesiredInputGraph graph(int config, String... ids) {
        return new DesiredInputGraph(Arrays.stream(ids).map(id -> entry(id, id.substring(0, 1), config))
            .map(DesiredInputNode.class::cast).toList());
    }
    private static DesiredInputEntry entry(String id, String plugin, int config) {
        return DesiredInputEntry.builder(id, new PluginDefinitionRef(plugin, "main", "definition"))
            .config(LiteralValue.of(config)).build();
    }
    private static ExecutionObservation.Detail detail(FibraEngine engine, String key) {
        return currentObservations(engine.snapshot()).get(new ExecutionUnitKey(key))
            .executions().getFirst();
    }
    private static RuntimeUnitDisableRequest disable(RuntimeId runtimeId,
                                                     String key,
                                                     ExecutionObservation.Detail detail) {
        return RuntimeUnitDisableRequest.of(fence(runtimeId, key, detail),
            "test-disable");
    }
    private static RuntimeUnitFence fence(RuntimeId runtimeId, String key,
                                          ExecutionObservation.Detail detail) {
        return RuntimeUnitFence.builder(runtimeId, new ExecutionUnitKey(key))
            .unitTargetRevision(detail.unitTargetRevision())
            .runtimeInstanceId(detail.runtimeInstanceId()).build();
    }
    private static CompletableFuture<PublishedView> nextState(
        FibraEngine engine, String key, ExecutionObservation.State state) {
        return engine.published().views().filter(view -> {
            var observation = currentObservations(view.engine()).get(
                new ExecutionUnitKey(key));
            return observation != null && observation.aggregateState() == state;
        }).next().toFuture();
    }
    private static ExecutionObservation.Detail detail(PublishedView view,
                                                       String key) {
        return currentObservations(view.engine()).get(new ExecutionUnitKey(key))
            .executions().getFirst();
    }
    private static Map<ExecutionUnitKey, ExecutionObservation> currentObservations(
        EngineSnapshot snapshot
    ) {
        return snapshot.current().map(CurrentAttemptSnapshot::observations)
            .orElseGet(Map::of);
    }
    private static void flush(FibraEngine engine) {
        engine.submit(new ReconcileCurrent()).block();
    }

    private static void assertConstructionStoresReleased(Path packageRoot,
                                                          Store targetStore) {
        assertEquals(1, targetStore.closes);
        assertThrows(IllegalStateException.class, targetStore::load);
        try (var reopened = new PluginPackageStore(packageRoot)) {
            assertNotNull(reopened);
        }
    }

    private class Store implements DeploymentTargetStore {
        DeploymentTarget current;
        boolean closed;
        boolean fail;
        boolean uncertain;
        boolean wrongToken;
        RuntimeException closeFailure;
        volatile CountDownLatch loadEntered;
        volatile CountDownLatch loadRelease;
        int loads;
        int saves;
        int closes;
        public Optional<StoredTarget> load() {
            loads++;
            var release = loadRelease;
            if (release != null) {
                loadEntered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test load gate was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test load gate was interrupted", interrupted);
                }
            }
            ensureOpen();
            return Optional.ofNullable(current).map(DeploymentTargetStoreFixture::confirmed);
        }
        public DurableTargetToken save(long expected, DeploymentTarget target) {
            ensureOpen();
            events.add("save:" + target.targetRevision());
            saves++;
            if (fail) throw new IllegalStateException("save failed");
            if (uncertain) throw new SaveUnconfirmedException(root, new IllegalStateException("fsync uncertain"));
            if (wrongToken) return DeploymentTargetStoreFixture.confirmed(
                Objects.requireNonNull(current, "current")).token();
            DeploymentTargetStore.checkRevision(expected,
                current == null ? 0 : current.targetRevision(), target);
            current = target;
            return DeploymentTargetStoreFixture.confirmed(target).token();
        }
        public void close() {
            closes++;
            closed = true;
            if (closeFailure != null) throw closeFailure;
        }
        void reopen() { closed = false; }
        void reset() { current = null; closed = false; }
        private void ensureOpen() {
            if (closed) throw new IllegalStateException(
                "deployment target store is closed");
        }
    }

    private class ProbeProvider implements RuntimeProvider {
        java.util.function.Function<ManagedFacet, RuntimeDefinitionQuery> queryFactory;
        List<BuiltInPluginPackage> metadataOverride;
        final RuntimeId runtimeId;
        final String plugin;
        List<FacetDependency> dependencies = List.of();
        Set<String> requiredCapabilities = Set.of();
        final Map<String, ProbeUnit> units = new ConcurrentHashMap<>();
        final AtomicInteger retired = new AtomicInteger();
        String contract = "v1";
        boolean sealFailure;
        boolean abortFailure;
        boolean activationFailure;
        boolean pending;
        boolean prepareFailure;
        boolean omitUnit;
        boolean snapshotFailure;
        String lifecycleFailure;
        RuntimeException driverCloseFailure;
        RuntimeException candidateCloseFailure;
        RuntimeException closeAdmissionFailure;
        volatile Sinks.One<Void> driverCloseEntered;
        volatile Sinks.One<Void> driverCloseRelease;
        volatile Sinks.One<Void> activationEntered;
        volatile Sinks.One<Void> activationRelease;
        volatile Sinks.One<Void> prepareEntered;
        volatile Sinks.One<Void> prepareRelease;
        RuntimeHostServices services;
        ProbeProvider(String runtime, String plugin) { runtimeId = new RuntimeId(runtime); this.plugin = plugin; }
        public RuntimeId id() { return runtimeId; }
        public String contractIdentity() { return contract; }
        public List<BuiltInPluginPackage> builtInPackages() {
            return metadataOverride == null ? List.of(metadata()) : metadataOverride;
        }
        BuiltInPluginPackage metadata() {
            return BuiltInPluginPackage.builder().pluginId(new PluginId(plugin)).version("1")
                .packageDigest(plugin.equals("a") ? "a".repeat(64) : "b".repeat(64))
                .facets(List.of(BuiltInFacet.builder(new FacetId("main"), runtimeId, new ExecutionTarget("host"))
                    .definitionIds(Set.of("definition")).dependencies(dependencies)
                    .requiredCapabilities(requiredCapabilities).build())).build();
        }
        public RuntimeDriver create(RuntimeHostServices services) {
            this.services = services;
            return new RuntimeDriver() {
                public RuntimeId id() { return runtimeId; }
                public Mono<RuntimeArtifactInspection> probe(PluginFacetSource source) { return Mono.error(new UnsupportedOperationException()); }
                public Mono<RuntimeArtifactInspection> inspect(ManagedFacet facet) { return Mono.error(new UnsupportedOperationException()); }
                @Override public RuntimeDefinitionQuery createDefinitionQuery(ManagedFacet facet) {
                    if (queryFactory == null) throw new AssertionError("pure built-in provider must not use dynamic SPI");
                    events.add("query-create:" + facet.facet().facetId().value());
                    return queryFactory.apply(facet);
                }
                public RuntimeDriverSnapshot snapshot() { return new RuntimeDriverSnapshot(runtimeId, Map.of()); }
                public Mono<Void> closeAsync() {
                    Mono<Void> close = Mono.fromRunnable(() -> {
                        events.add("driver-close:" + runtimeId.value());
                        if (driverCloseFailure != null) throw driverCloseFailure;
                    });
                    var release = driverCloseRelease;
                    if (release == null) return close;
                    driverCloseEntered.tryEmitEmpty();
                    return release.asMono().then(close);
                }
                public RuntimeCandidate createCandidate(RuntimeTargetSlice slice) {
                    return new RuntimeCandidate() {
                        RuntimePlan plan;
                        Mono<Void> failedClose;
                        public Mono<Void> prepareAsync() {
                            Mono<Void> prepare = Mono.fromRunnable(() -> {
                                events.add("prepare:" + runtimeId.value());
                                if (prepareFailure) throw new IllegalStateException("prepare failed");
                                var plans = new ArrayList<ExecutionUnitPlan>();
                                var bindings = new ArrayList<DefinitionBindingPlan>();
                                slice.affectedEntryIds().stream().sorted().forEach(id -> {
                                    if (omitUnit) return;
                                    var key = new ExecutionUnitKey(id);
                                    var entry = (DesiredInputEntry) slice.desired().require(id).input();
                                    plans.add(ExecutionUnitPlan.builder(key, runtimeId, new ExecutionTarget("host"))
                                        .artifactId(metadata().artifactId(new FacetId("main")))
                                        .provenance(plugin, "main", metadata().packageDigest())
                                        .dependencies(slice.unitDependencies().get(key)).build());
                                    bindings.add(DefinitionBindingPlan.builder(entry.definitionRef(), id).unitKey(key)
                                        .publicationRequirement(entry.publicationRequirement()).build());
                                });
                                plan = RuntimePlan.of(runtimeId, plans, bindings);
                            });
                            var release = prepareRelease;
                            if (release == null) return prepare;
                            prepareEntered.tryEmitEmpty();
                            return release.asMono().then(prepare);
                        }
                        public RuntimePlan preparedPlan() { return plan; }
                        public PreparedRuntimeGeneration seal(CompiledRuntimeSlice compiled) {
                            events.add("seal:" + runtimeId.value());
                            if (sealFailure) throw new IllegalStateException("seal failed");
                            var created = new LinkedHashMap<ExecutionUnitKey, RuntimeUnitGeneration>();
                            plan.units().forEach((key, value) -> created.put(key, new ProbeUnit(value,
                                slice.target().targetRevision(), services.nextIdentity("unit"),
                                slice.capabilities().availableNames())));
                            return new PreparedRuntimeGeneration() {
                                public Map<ExecutionUnitKey, RuntimeUnitGeneration> units() { return created; }
                                public Mono<Void> abortAsync() {
                                    return Mono.fromRunnable(() -> {
                                        events.add("abort:" + runtimeId.value());
                                        if (abortFailure) throw new IllegalStateException("abort failed");
                                    });
                                }
                                public Mono<Void> retireAsync() {
                                    return Mono.fromRunnable(() -> {
                                        events.add("retire:" + runtimeId.value());
                                        if ("retire".equals(lifecycleFailure)) throw new IllegalStateException("retire failed");
                                        retired.incrementAndGet();
                                    });
                                }
                            };
                        }
                        public synchronized Mono<Void> closeAsync() {
                            if (failedClose != null) return failedClose;
                            var failure = candidateCloseFailure;
                            if (failure == null) return Mono.fromRunnable(() -> events.add("close:" + runtimeId.value()));
                            return failedClose = Mono.<Void>fromRunnable(() -> {
                                events.add("close:" + runtimeId.value());
                                throw failure;
                            }).cache();
                        }
                    };
                }
            };
        }

        private final class ProbeUnit implements RuntimeUnitGeneration {
            final ExecutionUnitPlan plan;
            final long revision;
            final String instance;
            final Set<String> capabilities;
            volatile ExecutionObservation observed;
            final AtomicInteger scriptedSnapshotCalls = new AtomicInteger();
            final AtomicInteger snapshotCalls = new AtomicInteger();
            volatile CountDownLatch snapshotEntered;
            volatile CountDownLatch snapshotRelease;
            private final Deque<ExecutionObservation.State> scriptedSnapshots =
                new ArrayDeque<>();
            ProbeUnit(ExecutionUnitPlan plan, long revision, String instance,
                      Set<String> capabilities) {
                this.plan = plan; this.revision = revision; this.instance = instance;
                this.capabilities = Set.copyOf(capabilities);
                observed = observation("prepared", ExecutionObservation.State.PENDING);
            }
            public ExecutionUnitPlan plan() { return plan; }
            public RuntimeUnitFence fence() {
                return RuntimeUnitFence.builder(runtimeId, plan.key())
                    .unitTargetRevision(revision).runtimeInstanceId(instance)
                    .build();
            }
            public Mono<ExecutionObservation> reconcileAsync(String operation) {
                var activation = Mono.fromSupplier(() -> {
                    events.add("activate:" + plan.key().value());
                    units.put(plan.key().value(), this);
                    return observed = observation(operation, activationFailure ? ExecutionObservation.State.FAILED
                        : pending ? ExecutionObservation.State.PENDING : ExecutionObservation.State.ACTIVE);
                });
                var release = activationRelease;
                if (release == null) return activation;
                activationRelease = null;
                activationEntered.tryEmitEmpty();
                return release.asMono().then(activation);
            }
            public void closeAdmission() {
                events.add("admission:" + plan.key().value());
                if (closeAdmissionFailure != null) throw closeAdmissionFailure;
            }
            public Mono<ExecutionObservation> drainAsync(String operation, Instant deadline) {
                if ("timeout".equals(lifecycleFailure)) return Mono.never();
                return Mono.fromSupplier(() -> {
                    events.add("drain:" + plan.key().value());
                    if ("drain".equals(lifecycleFailure)) throw new IllegalStateException("drain failed");
                    return observation(operation, observed.aggregateState());
                });
            }
            public Mono<ExecutionObservation> stopAsync(String operation, Instant deadline) {
                return Mono.fromSupplier(() -> {
                    events.add("stop:" + plan.key().value());
                    if ("stop".equals(lifecycleFailure)) throw new IllegalStateException("stop failed");
                    return observed = observation(operation, ExecutionObservation.State.PENDING);
                });
            }
            public ExecutionObservation snapshot() {
                snapshotCalls.incrementAndGet();
                var release = snapshotRelease;
                if (release != null) {
                    snapshotEntered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test snapshot gate was not released");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("test snapshot gate was interrupted", interrupted);
                    }
                }
                if (snapshotFailure) throw new IllegalStateException("snapshot contract violated");
                synchronized (scriptedSnapshots) {
                    if (!scriptedSnapshots.isEmpty()) {
                        var state = scriptedSnapshots.removeFirst();
                        return observation("scripted-" +
                            scriptedSnapshotCalls.incrementAndGet(), state);
                    }
                }
                return observed;
            }
            void observe(String operation, ExecutionObservation.State state) {
                observed = observation(operation, state);
            }
            void scriptSnapshots(ExecutionObservation.State... states) {
                synchronized (scriptedSnapshots) {
                    scriptedSnapshots.clear();
                    scriptedSnapshots.addAll(List.of(states));
                    scriptedSnapshotCalls.set(0);
                }
            }
            private ExecutionObservation observation(String operation, ExecutionObservation.State state) {
                var builder = ExecutionObservation.Detail.builder().unitTargetRevision(revision)
                    .executionId(plan.key().value()).runtimeInstanceId(instance)
                    .lifecycleOperationId(operation).capabilities(capabilities)
                    .state(state);
                if (state == ExecutionObservation.State.FAILED) builder.failure(new ExecutionObservation.Failure("START_FAILED", "start failed", Map.of()));
                return ExecutionObservation.of(plan.pluginId(), plan.facetId(), runtimeId, plan.executionTarget(), List.of(builder.build()));
            }
        }
    }
}
