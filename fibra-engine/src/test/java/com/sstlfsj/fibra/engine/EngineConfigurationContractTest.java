package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.*;
import com.sstlfsj.fibra.config.*;
import com.sstlfsj.fibra.engine.deployment.*;
import com.sstlfsj.fibra.engine.execution.*;
import com.sstlfsj.fibra.engine.observation.*;
import com.sstlfsj.fibra.engine.runtime.*;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.sstlfsj.fibra.engine.deployment.ConfigContractException.Code.*;

class EngineConfigurationContractTest {
    @TempDir Path root;
    static final RuntimeId RUNTIME = new RuntimeId("probe");
    static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
    static final String SECRET = "secret-canary-unknown-key-and-value";
    static final PluginDefinitionRef REF = new PluginDefinitionRef("sample", "main", "run");

    @Test void queryIsColdFencedDisabledAndPureAndUsesExactFacet() throws Exception {
        try (var f = new Fixture()) {
            var record = f.publish("sample", Map.of("main", Map.of("run", schema("integer")),
                "aux", Map.of("run", schema("string"))));
            f.start(); f.apply(List.of(select(record, false)), graph(), ConfigContextSnapshot.empty());
            var before = f.engine.published().current();
            var query = f.engine.describeConfiguration(REF, 1);
            assertEquals(0, f.reads.get());
            var contract = query.block();
            assertEquals(record.packageRevision(), contract.packageRevision());
            assertEquals(1, contract.targetRevision());
            assertEquals(source("integer"), contract.source());
            assertEquals(source("string"), f.engine.describeConfiguration(new PluginDefinitionRef("sample", "aux", "run"), 1).block().source());
            assertEquals(before, f.engine.published().current());
            assertEquals(0, f.provider.creates);
            assertEquals(0, f.provider.prepares);
            assertEquals(2, f.closes.get());
            assertCode(STALE_TARGET, f.engine.describeConfiguration(new PluginDefinitionRef("absent", "x", "x"), 0));
            assertCode(NOT_SELECTED, f.engine.describeConfiguration(new PluginDefinitionRef("absent", "x", "x"), 1));
            assertCode(UNKNOWN_DEFINITION, f.engine.describeConfiguration(new PluginDefinitionRef("sample", "missing", "run"), 1));
            assertCode(UNKNOWN_DEFINITION, f.engine.describeConfiguration(new PluginDefinitionRef("sample", "main", "missing"), 1));
            assertThrows(NullPointerException.class, () -> f.engine.describeConfiguration(null, 1));
            assertThrows(IllegalArgumentException.class, () -> f.engine.describeConfiguration(REF, -1));
        }
    }

    @Test void unknownNamesWinOverBadDocumentAndDormantSemanticErrorsAreDeferred() throws Exception {
        try (var f = new Fixture()) {
            var record = f.publish("sample", Map.of("main", Map.of("run", schema("integer"),
                "dormant", Map.of("$schema", DIALECT, "type", "string", "pattern", ".*"))));
            f.start(); f.apply(List.of(select(record, true)), graph(entry("one", REF, 3)), ConfigContextSnapshot.empty());
            assertEquals(1, f.provider.creates);
            f.provider.names = List.of("run", "dormant");
            assertCode(INVALID_CONTRACT, f.engine.describeConfiguration(new PluginDefinitionRef("sample", "main", "dormant"), 1));
            var bad = f.publishText("bad", "{broken");
            f.apply(List.of(select(bad, false)), graph(), ConfigContextSnapshot.empty());
            assertCode(UNKNOWN_DEFINITION, f.engine.describeConfiguration(new PluginDefinitionRef("bad", "main", "unknown"), 2));
            assertCode(INVALID_CONTRACT, f.engine.describeConfiguration(new PluginDefinitionRef("bad", "main", "run"), 2));
        }
    }

    @Test void candidateRevisionMustSupplyRulesAndRejectedRevisionRemainsPublished() throws Exception {
        try (var f = new Fixture()) {
            var one = f.publish("sample", Map.of("main", Map.of("run", schema("integer"))));
            f.start(); var graph = graph(entry("one", REF, 8));
            f.apply(List.of(select(one, true)), graph, ConfigContextSnapshot.empty());
            var current = f.engine.snapshot().current();
            var before = f.engine.snapshot().target();
            var rule = new LinkedHashMap<>(schema("integer")); rule.put("maximum", 2);
            var two = f.publish("sample", Map.of("main", Map.of("run", rule)));
            var failure = assertThrows(EngineChangeException.class,
                () -> f.apply(List.of(select(two, true)), graph, ConfigContextSnapshot.empty()));
            assertEquals(TargetSaveState.NOT_SAVED, failure.targetSaveState());
            assertEquals(current, f.engine.snapshot().current());
            assertEquals(before, f.engine.snapshot().target());
            assertEquals(1, f.provider.creates); assertEquals(1, f.provider.prepares); assertEquals(1, f.store.saves);
            assertTrue(f.packages.find(two.pluginId(), two.packageRevision()).isPresent());
            assertEquals(two.packageRevision(), f.resourceRevisions.getLast());
            assertEquals(0, f.provider.admissions);
            assertPlanning(f, EngineOperationKind.APPLY, TargetSaveState.NOT_SAVED, 2, false, false);
        }
    }

    @Test void schemaOnlyUpgradePreflightsRetainedEntriesButPreparesOnlyAffectedUnits() throws Exception {
        try (var f = new Fixture()) {
            var one = f.publish("sample", Map.of("main", Map.of("run", schema("integer"))));
            var other = f.publish("other", Map.of("main", Map.of("run", schema("integer"))));
            f.start(); var graph = graph(entry("one", REF, 1), entry("two", new PluginDefinitionRef("other", "main", "run"), 2));
            f.apply(List.of(select(one, true), select(other, true)), graph, ConfigContextSnapshot.empty());
            var unchanged = f.provider.units.get("two");
            var rule = new LinkedHashMap<>(schema("integer")); rule.put("maximum", 10);
            var two = f.publish("sample", Map.of("main", Map.of("run", rule)));
            var reads = f.reads.get();
            f.apply(List.of(select(two, true), select(other, true)), graph, ConfigContextSnapshot.empty());
            assertEquals(reads + 2, f.reads.get());
            assertSame(unchanged, f.provider.units.get("two"));
            assertEquals(Set.of("one"), f.provider.lastAffected);
        }
    }

    @Test void inactiveEntriesDeferRulesButPackageGatePreservesEvaluation() throws Exception {
        for (var mode : List.of("local", "ancestor", "when", "package")) {
            try (var f = new Fixture()) {
                var record = f.publishText("sample", null); f.start();
                var builder = DesiredInputEntry.builder("one", REF).config(LiteralValue.of(Map.of("$ref", "/missing")));
                DesiredInputNode node;
                if (mode.equals("local")) node = builder.enabled(false).build();
                else if (mode.equals("when")) node = builder.when(LiteralValue.of(false)).build();
                else if (mode.equals("ancestor")) node = DesiredInputGroup.builder("group").enabled(false).children(List.of(builder.build())).build();
                else node = builder.build();
                if (mode.equals("package")) {
                    var finalNode = node;
                    assertThrows(EngineChangeException.class, () -> f.apply(List.of(select(record, false)), graph(finalNode), ConfigContextSnapshot.empty()));
                    f.apply(List.of(select(record, false)), graph(entry("one", REF, 1)), ConfigContextSnapshot.empty());
                } else f.apply(List.of(select(record, true)), graph(node), ConfigContextSnapshot.empty());
                assertEquals(0, f.reads.get()); assertEquals(0, f.provider.creates);
                assertCode(MISSING_CONTRACT, f.engine.describeConfiguration(REF, 1));
                assertThrows(EngineChangeException.class, () -> f.apply(List.of(select(record, true)), graph(entry("one", REF, 1)), ConfigContextSnapshot.empty()));
            }
        }
    }

    @Test void expressionsValidateResolvedValuesAndEveryRepeatedInstance() throws Exception {
        try (var f = new Fixture()) {
            var record = f.publish("sample", Map.of("main", Map.of("run", schema("integer")))); f.start();
            var raw = LiteralValue.of(Map.of("$ref", "/number"));
            var context = ConfigContextSnapshot.of((LiteralValue.ObjectValue) LiteralValue.of(Map.of("number", 7)));
            var e = DesiredInputEntry.builder("one", REF).config(raw).build();
            f.apply(List.of(select(record, true)), graph(e), context);
            assertEquals(LiteralValue.of(7), f.provider.effective.get("one"));
            var failure = assertThrows(EngineChangeException.class,
                () -> f.apply(List.of(select(record, true)), graph(e, entry("two", REF, SECRET)), context));
            assertTrue(failure.getCause().getMessage().contains("inputIndex=1"));
            assertFalse(failure.toString().contains(SECRET)); assertFalse(failure.getCause().toString().contains(SECRET));
            assertFalse(f.engine.published().current().engineDiagnostics().toString().contains(SECRET));
            assertEquals(1, f.provider.creates);
        }
    }

    @Test void foreignErrorsIncludingForgedContractErrorsAreSanitizedAtSpiBoundary() throws Exception {
        for (var mode : List.of("read", "factory", "names", "missing-name", "forged", "hostile-list")) {
            try (var f = new Fixture()) {
                var record = f.publish("sample", Map.of("main", Map.of("run", schema("integer"))));
                f.start(); f.apply(List.of(select(record, false)), graph(), ConfigContextSnapshot.empty());
                if (mode.equals("hostile-list")) f.provider.names = new AbstractList<>() {
                    public String get(int index) { throw new ConfigContractException(INVALID_CONTRACT, SECRET); }
                    public int size() { return 1; }
                };
                else if (mode.equals("names")) f.provider.readFailure = new HostileError();
                else if (mode.equals("missing-name")) f.provider.readFailure = new NoSuchElementException(SECRET);
                else if (mode.equals("factory")) f.factoryFailure = new NoSuchElementException(SECRET);
                else f.readFailure = mode.equals("forged") ? new ConfigContractException(INVALID_CONTRACT, SECRET) : new HostileError();
                var failure = assertCode(READ_FAILED, f.engine.describeConfiguration(REF, 1));
                assertFalse(failure.toString().contains(SECRET));
            }
        }
    }

    @Test void queryDoubleFailureRetainsOnlyFailedOwnersAndDoesNotRewriteSuccessfulOperation() throws Exception {
        var f = new Fixture();
        try {
            var record = f.publish("sample", Map.of("main", Map.of("run", schema("integer"))));
            f.start(); f.apply(List.of(select(record, true)), graph(entry("one", REF, 1)), ConfigContextSnapshot.empty());
            var operation = f.engine.published().current().engineDiagnostics().operation();
            f.readFailure = new HostileError(); f.closeFailure = new HostileError();
            f.provider.admissionFailure = new HostileError();
            var error = assertCode(CLEANUP_FAILED, f.engine.describeConfiguration(REF, 1));
            assertTrue(error.getMessage().contains("prior=READ_FAILED"));
            assertEquals(operation, f.engine.published().current().engineDiagnostics().operation());
            var fact = f.engine.published().current().engineDiagnostics().failure().orElseThrow();
            assertInstanceOf(FailureSubject.Engine.class, fact.subject());
            assertTrue(fact.operationStage().isEmpty()); assertEquals(1, fact.targetRevision().orElseThrow());
            assertEquals(FailureStage.RELEASING, fact.stage());
            assertFalse(f.engine.published().current().engineDiagnostics().toString().contains(SECRET));
            int closes = f.closes.get(); int nameCloses = f.provider.nameCloses;
            assertSame(error, assertCode(CLEANUP_FAILED, f.engine.closeAsync()));
            assertSame(error, assertCode(CLEANUP_FAILED, f.engine.closeAsync()));
            assertEquals(closes, f.closes.get()); assertEquals(nameCloses, f.provider.nameCloses);
            assertEquals(0, f.provider.driverCloses); assertEquals(0, f.store.closes);
            assertEquals(operation, f.engine.published().current().engineDiagnostics().operation());
            var field = FibraEngine.class.getDeclaredField("readAttempt"); field.setAccessible(true);
            var owner = field.get(f.engine); assertNotNull(owner);
            var handles = owner.getClass().getDeclaredField("handles"); handles.setAccessible(true);
            var retained = (List<?>) handles.get(owner);
            assertEquals(1, retained.size()); assertSame(f.lastResource, retained.getFirst());
        } finally { f.packages.close(); }
    }

    @Test void allFiveInitiatorsKeepPlanningProvenanceForReadAndCleanupFailure() throws Exception {
        for (var path : List.of("new", "same", "bootstrap", "reconcile", "request")) {
            for (boolean cleanup : List.of(false, true)) {
                var f = new Fixture();
                try {
                    var record = f.publish("sample", Map.of("main", Map.of("run", schema("integer"))));
                    var selection = List.of(select(record, true)); var graph = graph(entry("one", REF, 1));
                    if (path.equals("bootstrap")) {
                        f.store.delegate.save(0, DeploymentTarget.of(1, selection, graph, ConfigContextSnapshot.empty()));
                    } else {
                        f.start();
                        if (!path.equals("new")) f.apply(selection, graph, ConfigContextSnapshot.empty());
                    }
                    f.readFailure = new HostileError();
                    if (cleanup) { f.closeFailure = new HostileError(); f.provider.admissionFailure = new HostileError(); }
                    int creates = f.provider.creates;
                    if (path.equals("bootstrap")) { if (cleanup) assertThrows(EngineChangeException.class, f::start); else f.start(); }
                    else if (path.equals("request")) {
                        f.provider.contract = "changed";
                        var done = f.engine.published().views().filter(v -> v.engineDiagnostics().operation()
                            .map(o -> o.kind() == EngineOperationKind.RECONCILE && o.outcome() == EngineOperationOutcome.FAILED).orElse(false)).next().toFuture();
                        f.provider.services.requestRecompile(new RuntimeRecompileReason(RUNTIME, "changed", "test"));
                        done.get(5, TimeUnit.SECONDS);
                    } else if (path.equals("reconcile")) {
                        f.provider.units.get("one").failed = true;
                        assertThrows(EngineChangeException.class, () -> f.engine.submit(new ReconcileCurrent()).block());
                    } else {
                        if (path.equals("same")) f.provider.contract = "changed";
                        assertThrows(EngineChangeException.class, () -> f.apply(selection, graph, ConfigContextSnapshot.empty()));
                    }
                    assertEquals(creates, f.provider.creates);
                    var kind = path.equals("bootstrap") ? EngineOperationKind.BOOTSTRAP
                        : path.equals("reconcile") || path.equals("request") ? EngineOperationKind.RECONCILE : EngineOperationKind.APPLY;
                    assertPlanning(f, kind, path.equals("new") ? TargetSaveState.NOT_SAVED : TargetSaveState.NOT_APPLICABLE,
                        1, cleanup, path.equals("bootstrap"));
                    if (cleanup) {
                        var operation = f.engine.published().current().engineDiagnostics().operation();
                        var fact = f.engine.published().current().engineDiagnostics().failure();
                        assertCode(CLEANUP_FAILED, f.engine.closeAsync());
                        assertEquals(operation, f.engine.published().current().engineDiagnostics().operation());
                        assertEquals(fact, f.engine.published().current().engineDiagnostics().failure());
                        assertEquals(0, f.store.closes); assertEquals(0, f.provider.driverCloses);
                    } else { assertEquals(0, f.provider.admissions); f.close(); }
                } finally { f.packages.close(); }
            }
        }
    }

    @Test void acceptedCancellationWaitsForActualReadAndCloseBeforeShutdown() throws Exception {
        try (var f = new Fixture()) {
            var record = f.publish("sample", Map.of("main", Map.of("run", schema("integer"))));
            f.start(); f.apply(List.of(select(record, false)), graph(), ConfigContextSnapshot.empty());
            f.readGate = new CountDownLatch(1); f.closeGate = new CountDownLatch(1);
            var result = f.engine.describeConfiguration(REF, 1).toFuture();
            assertTrue(f.readEntered.await(2, TimeUnit.SECONDS)); result.cancel(true);
            var close = f.engine.closeAsync().toFuture();
            assertFalse(close.isDone()); f.readGate.countDown();
            assertTrue(f.closeEntered.await(2, TimeUnit.SECONDS));
            assertFalse(close.isDone()); assertEquals(0, f.provider.driverCloses);
            f.closeGate.countDown(); close.get(5, TimeUnit.SECONDS);
            assertEquals(1, f.reads.get()); assertEquals(1, f.closes.get()); assertEquals(1, f.provider.nameCloses);
        }
    }

    @Test void healthyAndPendingReconcileAndSameTargetNoopDoNotReadNewContracts() throws Exception {
        try (var f = new Fixture()) {
            var record = f.publish("sample", Map.of("main", Map.of("run", schema("integer"))));
            f.start(); var graph = graph(entry("one", REF, 1));
            f.apply(List.of(select(record, true)), graph, ConfigContextSnapshot.empty());
            int reads = f.reads.get(); f.readFailure = new HostileError();
            f.engine.submit(new ReconcileCurrent()).block();
            f.apply(List.of(select(record, true)), graph, ConfigContextSnapshot.empty());
            f.provider.units.get("one").pending = true;
            f.engine.submit(new ReconcileCurrent()).block();
            assertEquals(reads, f.reads.get()); assertEquals(1, f.provider.creates);
        }
    }

    @Test void productionResourceFactoryBindsBytesFromThisFindAndDistinguishesAbsentFromLaterMissing() throws Exception {
        for (var mode : List.of("valid", "absent", "changed", "removed")) {
            try (var f = new Fixture(false)) {
                var record = mode.equals("absent") ? f.publishText("sample", null)
                    : f.publish("sample", Map.of("main", Map.of("run", schema("integer"))));
                f.start(); f.apply(List.of(select(record, false)), graph(), ConfigContextSnapshot.empty());
                var path = record.location().resolve("fibra-config.json");
                f.provider.beforeNames = () -> {
                    try {
                        if (mode.equals("removed")) Files.move(path, path.resolveSibling("held-configuration"));
                        else if (!mode.equals("valid")) Files.writeString(path, "{}");
                    } catch (java.io.IOException failure) { throw new AssertionError(failure); }
                };
                if (mode.equals("valid")) assertEquals(source("integer"), f.engine.describeConfiguration(REF, 1).block().source());
                else assertCode(mode.equals("absent") ? MISSING_CONTRACT : READ_FAILED, f.engine.describeConfiguration(REF, 1));
                assertEquals(0, f.provider.creates);
            }
        }
    }

    @Test void ifAndLiteralReachOnlyResolvedSchemaAndDoNotSupplyDefaults() throws Exception {
        try (var f = new Fixture()) {
            var object = Map.of("$schema", DIALECT, "type", "object", "properties", Map.of("$ref", Map.of("type", "string")),
                "additionalProperties", false, "required", List.of("$ref"));
            var record = f.publish("sample", Map.of("main", Map.of("run", object))); f.start();
            var raw = LiteralValue.of(Map.of("$if", List.of(true,
                Map.of("$literal", Map.of("$ref", "/unresolved")), Map.of("$ref", "/missing"))));
            f.apply(List.of(select(record, true)), graph(DesiredInputEntry.builder("one", REF).config(raw).build()), ConfigContextSnapshot.empty());
            assertEquals(LiteralValue.of(Map.of("$ref", "/unresolved")), f.provider.effective.get("one"));
            assertThrows(EngineChangeException.class, () -> f.apply(List.of(select(record, true)), graph(entry("one", REF, Map.of())), ConfigContextSnapshot.empty()));
            assertEquals(1, f.provider.creates);
        }
    }

    @Test void schemaAndInstanceAndBatchBudgetsAreCheckedBeforeCandidateAndBatchBeforeMismatch() throws Exception {
        for (var mode : List.of("schema", "instance", "batch")) {
            try (var f = new Fixture()) {
                var rule = new LinkedHashMap<String, Object>(schema(mode.equals("batch") ? "array" : "string"));
                if (mode.equals("schema")) rule.put("description", "x".repeat(4097));
                if (mode.equals("batch")) { rule.put("items", true); rule.put("description", "x".repeat(4096)); }
                var record = f.publish("sample", Map.of("main", Map.of("run", rule))); f.start();
                var nodes = new ArrayList<DesiredInputNode>();
                if (mode.equals("batch")) {
                    nodes.add(entry("first", REF, "wrong type must lose to whole batch budget"));
                    for (int i = 0; i < 100; i++) nodes.add(entry("item" + i, REF, Collections.nCopies(9999, 0)));
                } else nodes.add(entry("one", REF, mode.equals("instance") ? "x".repeat(65537) : "valid"));
                var error = assertThrows(EngineChangeException.class, () -> f.apply(List.of(select(record, true)),
                    new DesiredInputGraph(nodes), ConfigContextSnapshot.empty()));
                assertTrue(error.getCause().getMessage().contains("BUDGET_EXCEEDED"));
                assertTrue(error.getCause().getMessage().contains("inputIndex=" + (mode.equals("batch") ? -1 : 0)));
                assertEquals(0, f.provider.creates); assertEquals(0, f.store.saves);
                assertEquals(1, f.reads.get()); assertEquals(1, f.closes.get());
            }
        }
    }

    @Test void unknownAndDynamicInputKeysNeverReachSafeFailureProjections() throws Exception {
        for (boolean dictionary : List.of(false, true)) {
            try (var f = new Fixture()) {
                var inner = Map.of("type", "object", "properties", Map.of("known", Map.of("type", "integer")), "additionalProperties", false);
                var rule = Map.of("$schema", DIALECT, "type", "object", "properties", Map.of("items",
                    Map.of("type", "object", "additionalProperties", dictionary ? inner : false)), "additionalProperties", false);
                var record = f.publish("sample", Map.of("main", Map.of("run", rule))); f.start();
                var error = assertThrows(EngineChangeException.class, () -> f.apply(List.of(select(record, true)),
                    graph(entry("one", REF, Map.of("items", Map.of(SECRET, Map.of("known", SECRET))))), ConfigContextSnapshot.empty()));
                assertFalse(error.getCause().toString().contains(SECRET));
                assertNull(error.getCause().getCause()); assertEquals(0, error.getCause().getSuppressed().length);
                assertFalse(f.engine.published().current().engineDiagnostics().toString().contains(SECRET));
                assertTrue(error.getCause().getMessage().contains("instancePointer=/items"));
            }
        }
    }

    @Test void siblingResourceCloseFailureStillClosesEveryOtherResourceAndNameHandle() throws Exception {
        var f = new Fixture();
        try {
            var first = f.publish("sample", Map.of("main", Map.of("run", schema("integer"))));
            var second = f.publish("other", Map.of("main", Map.of("run", schema("integer")))); f.start();
            f.failCloseNumber = 1;
            var error = assertThrows(EngineChangeException.class, () -> f.apply(List.of(select(first, true), select(second, true)),
                graph(entry("one", REF, 1), entry("two", new PluginDefinitionRef("other", "main", "run"), 2)), ConfigContextSnapshot.empty()));
            assertEquals(CLEANUP_FAILED, assertInstanceOf(ConfigContractException.class, error.getCause()).code());
            assertEquals(2, f.closes.get()); assertEquals(2, f.provider.nameCloses); assertEquals(0, f.provider.creates);
            assertCode(CLEANUP_FAILED, f.engine.closeAsync()); assertEquals(2, f.closes.get());
        } finally { f.packages.close(); }
    }

    @Test void nullResourceReadIsReadFailureAndOtherMalformedNameResultsAreNeverTrusted() throws Exception {
        for (var mode : List.of("null-bytes", "null-factory", "duplicate", "blank", "null-read", "empty-read", "async-read")) {
            try (var f = new Fixture()) {
                var record = f.publish("sample", Map.of("main", Map.of("run", schema("integer"))));
                f.start(); f.apply(List.of(select(record, false)), graph(), ConfigContextSnapshot.empty());
                switch (mode) {
                    case "null-bytes" -> f.nullBytes = true;
                    case "null-factory" -> f.nullResource = true;
                    case "duplicate" -> f.provider.names = List.of("run", "run");
                    case "blank" -> f.provider.names = List.of("");
                    case "null-read" -> f.provider.nameRead = () -> null;
                    case "empty-read" -> f.provider.nameRead = Mono::empty;
                    case "async-read" -> f.provider.nameRead = () -> Mono.error(new ConfigContractException(INVALID_CONTRACT, SECRET));
                }
                assertCode(READ_FAILED, f.engine.describeConfiguration(REF, 1));
                assertEquals(1, f.provider.nameCloses);
            }
        }
    }

    @Test void asynchronousNameCloseMustCompleteAndFailureIsCachedWithResourceAlreadyReleased() throws Exception {
        for (boolean fail : List.of(false, true)) {
            var f = new Fixture();
            try {
                var record = f.publish("sample", Map.of("main", Map.of("run", schema("integer"))));
                f.start(); f.apply(List.of(select(record, false)), graph(), ConfigContextSnapshot.empty());
                var gate = Sinks.<Void>one(); var entered = Sinks.<Void>one();
                f.provider.nameClose = () -> { entered.tryEmitEmpty(); return gate.asMono(); };
                var result = f.engine.describeConfiguration(REF, 1).toFuture();
                entered.asMono().block(Duration.ofSeconds(2));
                assertFalse(result.isDone()); assertEquals(1, f.closes.get());
                if (fail) {
                    gate.tryEmitError(new HostileError());
                    var error = assertInstanceOf(ConfigContractException.class,
                        assertThrows(CompletionException.class, result::join).getCause());
                    assertEquals(CLEANUP_FAILED, error.code()); assertNull(error.getCause());
                    assertSame(error, assertCode(CLEANUP_FAILED, f.engine.closeAsync()));
                    assertEquals(1, f.provider.nameCloses); assertEquals(0, f.provider.driverCloses);
                } else { gate.tryEmitEmpty(); assertNotNull(result.get(2, TimeUnit.SECONDS)); f.close(); }
            } finally { f.packages.close(); }
        }
    }

    @Test void missingSelectedSchemaAndGlobalEnvelopeBudgetRejectWhileDormantSchemaBudgetDefers() throws Exception {
        try (var f = new Fixture()) {
            var dormant = Map.of("$schema", DIALECT, "type", "string", "description", "x".repeat(4097));
            var record = f.publish("sample", Map.of("main", Map.of("run", schema("integer"), "dormant", dormant)));
            f.start(); f.apply(List.of(select(record, true)), graph(entry("one", REF, 1)), ConfigContextSnapshot.empty());
            f.provider.names = List.of("run", "dormant", "missing");
            assertCode(INVALID_CONTRACT, f.engine.describeConfiguration(new PluginDefinitionRef("sample", "main", "dormant"), 1));
            assertCode(MISSING_CONTRACT, f.engine.describeConfiguration(new PluginDefinitionRef("sample", "main", "missing"), 1));
            var oversized = new LinkedHashMap<String, Object>(); oversized.put("run", schema("integer"));
            for (int i = 0; i < 128; i++) oversized.put("dormant" + i, schema("string"));
            var bad = f.publish("sample", Map.of("main", oversized));
            f.apply(List.of(select(bad, false)), graph(), ConfigContextSnapshot.empty());
            assertCode(INVALID_CONTRACT, f.engine.describeConfiguration(REF, 2));
        }
    }

    @Test void engineAndPackageAndRuntimeAdmissionPrecedeNameAndSchemaInspection() throws Exception {
        try (var f = new Fixture(false, false)) {
            assertCode(ENGINE_UNAVAILABLE, f.engine.describeConfiguration(REF, 999));
            var record = f.publishText("sample", "broken envelope"); f.start();
            f.apply(List.of(select(record, false)), graph(), ConfigContextSnapshot.empty());
            assertCode(STALE_TARGET, f.engine.describeConfiguration(REF, 0));
            assertCode(UNSUPPORTED_RUNTIME, f.engine.describeConfiguration(new PluginDefinitionRef("sample", "absent", "absent"), 1));
            Files.move(record.location(), record.location().resolveSibling("held-package"));
            assertCode(PACKAGE_UNAVAILABLE, f.engine.describeConfiguration(REF, 1));
            assertEquals(0, f.reads.get());
        }
    }

    static void assertPlanning(Fixture f, EngineOperationKind kind, TargetSaveState save, long revision,
                               boolean cleanup, boolean bootstrap) {
        var diagnostics = f.engine.published().current().engineDiagnostics();
        var operation = diagnostics.operation().orElseThrow();
        assertEquals(kind, operation.kind()); assertEquals(EngineOperationStage.PLANNING, operation.stage());
        assertEquals(EngineOperationOutcome.FAILED, operation.outcome()); assertEquals(save, operation.targetSaveState());
        assertEquals(revision, operation.targetRevision());
        assertTrue(operation.attemptId().isEmpty());
        var fact = diagnostics.failure().orElseThrow();
        assertEquals(cleanup ? FailureStage.RELEASING : FailureStage.PLANNING, fact.stage());
        assertEquals(EngineOperationStage.PLANNING, fact.operationStage().orElseThrow());
        assertEquals(revision, fact.targetRevision().orElseThrow());
        if (bootstrap) {
            assertEquals(new FailureSubject.DurableTarget(revision, f.engine.snapshot().target().orElseThrow().targetDigest()), fact.subject());
            assertEquals(DurableTargetState.PRESENT, f.engine.snapshot().durableState());
            assertEquals(TargetConvergence.BLOCKED, f.engine.snapshot().targetConvergence());
        } else assertEquals(new FailureSubject.Operation(operation.operationId()), fact.subject());
        assertTrue(f.engine.snapshot().candidate().isEmpty());
        assertFalse(fact.toString().contains(SECRET));
    }

    static ConfigContractException assertCode(ConfigContractException.Code code, Mono<?> result) {
        var failure = assertThrows(CompletionException.class, () -> result.toFuture().join());
        var error = assertInstanceOf(ConfigContractException.class, failure.getCause());
        assertEquals(code, error.code()); assertNull(error.getCause()); assertEquals(0, error.getSuppressed().length);
        return error;
    }
    static Map<String, Object> schema(String type) { return Map.of("$schema", DIALECT, "type", type); }
    static ConfigSchemaSource source(String type) { return new ConfigSchemaSource("fibra-config-1", (LiteralValue.ObjectValue) LiteralValue.of(schema(type))); }
    static PluginSelection select(PluginPackageRecord r, boolean enabled) { return new PluginSelection(r.pluginId(), r.packageRevision(), enabled); }
    static DesiredInputGraph graph(DesiredInputNode... entries) { return new DesiredInputGraph(List.of(entries)); }
    static DesiredInputEntry entry(String id, PluginDefinitionRef ref, Object value) { return DesiredInputEntry.builder(id, ref).config(LiteralValue.of(value)).build(); }
    static void raise(Throwable error) { if (error instanceof Error e) throw e; if (error instanceof RuntimeException e) throw e; }
    static void await(CountDownLatch latch) {
        if (latch != null) try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    static final class HostileError extends Error {
        @Override public String getMessage() { throw new AssertionError("accessed " + SECRET); }
        @Override public String toString() { throw new AssertionError("accessed " + SECRET); }
    }

    final class Fixture implements AutoCloseable {
        final PluginPackageStore packages = new PluginPackageStore(root.resolve(UUID.randomUUID().toString()));
        final CountingStore store = new CountingStore();
        final Provider provider = new Provider();
        final FibraEngine engine;
        final AtomicInteger reads = new AtomicInteger(), closes = new AtomicInteger();
        final List<String> resourceRevisions = new ArrayList<>();
        volatile Throwable readFailure, closeFailure, factoryFailure;
        volatile CountDownLatch readGate, closeGate;
        final CountDownLatch readEntered = new CountDownLatch(1), closeEntered = new CountDownLatch(1);
        PluginConfigurationResource lastResource; int failCloseNumber; boolean nullBytes, nullResource;
        Fixture() { this(true); }
        Fixture(boolean injected) { this(injected, true); }
        Fixture(boolean injected, boolean availableRuntime) {
            var builder = FibraEngine.builder(packages, store).hostTerminationPort(ignored -> { });
            if (availableRuntime) builder.runtimeProvider(provider);
            engine = injected ? new FibraEngine(builder, record -> {
                    if (factoryFailure != null) raise(factoryFailure);
                    if (nullResource) return null;
                    resourceRevisions.add(record.packageRevision());
                    var actual = record.configurationResource();
                    return lastResource = new PluginConfigurationResource() {
                        public byte[] read() { reads.incrementAndGet(); readEntered.countDown(); await(readGate);
                            if (readFailure != null) raise(readFailure); return nullBytes ? null : actual.read(); }
                        public void close() { int number = closes.incrementAndGet(); closeEntered.countDown(); await(closeGate);
                            if (number == failCloseNumber) throw new HostileError();
                            if (closeFailure != null) raise(closeFailure); actual.close(); }
                    };
                }) : builder.build();
        }
        void start() { engine.startAsync().block(); }
        void apply(List<PluginSelection> selections, DesiredInputGraph graph, ConfigContextSnapshot context) {
            engine.submit(ApplyDeployment.builder(graph).expectedRevision(engine.snapshot().target().map(DeploymentTarget::targetRevision).orElse(0L))
                .selections(selections).configContext(context).build()).block();
        }
        PluginPackageRecord publish(String id, Map<String, ?> facets) throws Exception {
            return publishText(id, LiteralValue.of(Map.of("format", 1, "profile", "fibra-config-1", "facets", facets)).canonicalJson());
        }
        PluginPackageRecord publishText(String id, String json) throws Exception {
            var source = Files.createDirectories(root.resolve(UUID.randomUUID().toString()));
            Files.writeString(source.resolve("payload"), "static");
            Files.writeString(source.resolve(PluginPackage.MANIFEST), "format: 1\nid: " + id + "\nversion: '1'\nfacets:\n"
                + "  - {id: main, role: host, runtime: probe, target: host, payload: payload, dependencies: [], capabilities: []}\n"
                + "  - {id: aux, role: host, runtime: probe, target: host, payload: payload, dependencies: [], capabilities: []}\n");
            if (json != null) Files.writeString(source.resolve("fibra-config.json"), json);
            try (var tx = packages.prepareInstall(source)) { return tx.save(); }
        }
        public void close() { engine.close(); }
    }
    static final class CountingStore implements DeploymentTargetStore {
        final DeploymentTargetStore delegate = DeploymentTargetStore.inMemory(); int saves, closes;
        public Optional<StoredTarget> load() { return delegate.load(); }
        public DurableTargetToken save(long expected, DeploymentTarget target) { saves++; return delegate.save(expected, target); }
        public void close() { closes++; delegate.close(); }
    }
    static final class Provider implements RuntimeProvider {
        int creates, prepares, nameCloses, driverCloses, admissions;
        String contract = "probe-v1"; List<String> names = List.of("run"); Throwable readFailure, admissionFailure;
        Runnable beforeNames = () -> { };
        java.util.function.Supplier<Mono<List<String>>> nameRead;
        java.util.function.Supplier<Mono<Void>> nameClose;
        RuntimeHostServices services;
        Set<String> lastAffected; Map<String, LiteralValue> effective = new HashMap<>();
        final Map<String, Unit> units = new HashMap<>();
        public RuntimeId id() { return RUNTIME; }
        public String contractIdentity() { return contract; }
        public List<BuiltInPluginPackage> builtInPackages() { return List.of(); }
        public RuntimeDriver create(RuntimeHostServices services) {
            this.services = services;
            return new RuntimeDriver() {
                public RuntimeId id() { return RUNTIME; }
                public Mono<RuntimeArtifactInspection> probe(PluginFacetSource source) { return Mono.error(new UnsupportedOperationException()); }
                public Mono<RuntimeArtifactInspection> inspect(ManagedFacet facet) { return Mono.error(new UnsupportedOperationException()); }
                public RuntimeDefinitionQuery createDefinitionQuery(ManagedFacet facet) {
                    return new RuntimeDefinitionQuery() {
                        public Mono<List<String>> readAsync() { beforeNames.run(); if (readFailure != null) raise(readFailure); return nameRead == null ? Mono.just(names) : nameRead.get(); }
                        public Mono<Void> closeAsync() { nameCloses++; return nameClose == null ? Mono.empty() : nameClose.get(); }
                    };
                }
                public RuntimeDriverSnapshot snapshot() { return new RuntimeDriverSnapshot(RUNTIME, Map.of()); }
                public Mono<Void> closeAsync() { driverCloses++; return Mono.empty(); }
                public RuntimeCandidate createCandidate(RuntimeTargetSlice slice) {
                    creates++; lastAffected = slice.affectedEntryIds();
                    return new RuntimeCandidate() {
                        RuntimePlan plan;
                        public Mono<Void> prepareAsync() { return Mono.fromRunnable(() -> {
                            prepares++; var plans = new ArrayList<ExecutionUnitPlan>(); var bindings = new ArrayList<DefinitionBindingPlan>();
                            for (var id : slice.affectedEntryIds()) {
                                var entry = (DesiredInputEntry) slice.desired().require(id).input(); var ref = entry.definitionRef();
                                effective.put(id, slice.desired().require(id).resolvedConfig().orElseThrow());
                                var facet = slice.facets().stream().filter(f -> f.facet().pluginId().value().equals(ref.pluginId())
                                    && f.facet().facet().facetId().value().equals(ref.facetId())).findFirst().orElseThrow().facet();
                                var key = new ExecutionUnitKey(id);
                                plans.add(ExecutionUnitPlan.builder(key, RUNTIME, new ExecutionTarget("host")).artifactId(facet.artifactId())
                                    .provenance(ref.pluginId(), ref.facetId(), slice.target().selections().get(facet.pluginId()).packageRevision())
                                    .dependencies(slice.unitDependencies().get(key)).build());
                                bindings.add(DefinitionBindingPlan.builder(ref, id).unitKey(key).publicationRequirement(entry.publicationRequirement()).build());
                            }
                            plan = RuntimePlan.of(RUNTIME, plans, bindings);
                        }); }
                        public RuntimePlan preparedPlan() { return plan; }
                        public PreparedRuntimeGeneration seal(CompiledRuntimeSlice compiled) {
                            var result = new LinkedHashMap<ExecutionUnitKey, RuntimeUnitGeneration>();
                            plan.units().forEach((key, p) -> result.put(key, new Unit(p, slice.target().targetRevision(), services.nextIdentity("unit"))));
                            return new PreparedRuntimeGeneration() {
                                public Map<ExecutionUnitKey, RuntimeUnitGeneration> units() { return result; }
                                public Mono<Void> abortAsync() { return Mono.empty(); }
                                public Mono<Void> retireAsync() { return Mono.empty(); }
                            };
                        }
                        public Mono<Void> closeAsync() { return Mono.empty(); }
                    };
                }
            };
        }
        final class Unit implements RuntimeUnitGeneration {
            final ExecutionUnitPlan plan; final long revision; final String instance; boolean failed, pending;
            ExecutionObservation observation;
            Unit(ExecutionUnitPlan plan, long revision, String instance) { this.plan = plan; this.revision = revision; this.instance = instance; observation = observe("prepared", ExecutionObservation.State.PENDING); }
            public ExecutionUnitPlan plan() { return plan; }
            public RuntimeUnitFence fence() { return RuntimeUnitFence.builder(RUNTIME, plan.key()).unitTargetRevision(revision).runtimeInstanceId(instance).build(); }
            public Mono<ExecutionObservation> reconcileAsync(String operation) { units.put(plan.key().value(), this); return Mono.just(observation = observe(operation, ExecutionObservation.State.ACTIVE)); }
            public void closeAdmission() { admissions++; if (admissionFailure != null) raise(admissionFailure); }
            public Mono<ExecutionObservation> drainAsync(String operation, Instant deadline) { return Mono.just(observation); }
            public Mono<ExecutionObservation> stopAsync(String operation, Instant deadline) { return Mono.just(observation = observe(operation, ExecutionObservation.State.PENDING)); }
            public ExecutionObservation snapshot() { return failed ? observe("failed", ExecutionObservation.State.FAILED) : pending ? observe("pending", ExecutionObservation.State.PENDING) : observation; }
            ExecutionObservation observe(String operation, ExecutionObservation.State state) {
                var detail = ExecutionObservation.Detail.builder().unitTargetRevision(revision).executionId(plan.key().value())
                    .runtimeInstanceId(instance).lifecycleOperationId(operation).state(state);
                if (state == ExecutionObservation.State.FAILED) detail.failure(new ExecutionObservation.Failure("FAILED", "test failed", Map.of()));
                return ExecutionObservation.of(plan.pluginId(), plan.facetId(), RUNTIME, plan.executionTarget(), List.of(detail.build()));
            }
        }
    }
}
