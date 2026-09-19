package com.sstlfsj.fibra.plugins.acceptance;

import com.sstlfsj.fibra.CancellationSource;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.bridge.ContributionUnavailableException;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.engine.DeploymentTargetStore;
import com.sstlfsj.fibra.engine.ExecutionUnitKey;
import com.sstlfsj.fibra.engine.FileDeploymentTargetStore;
import com.sstlfsj.fibra.engine.PublishedView;
import com.sstlfsj.fibra.engine.TargetSaveState;
import com.sstlfsj.fibra.plugins.tool.ToolContent;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolException;
import com.sstlfsj.fibra.plugins.tool.ToolFailureCode;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
import com.sstlfsj.fibra.plugins.tool.ToolResult;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormalMultiPluginIT {
    private static final Map<String, LiteralValue> FS_REALM = realm("fibra.fs", "files");
    private static final Map<String, LiteralValue> PROCESS_REALM = realm("fibra.subprocess", "processes");
    private static final Map<String, LiteralValue> SHELL_REALM = realm("fibra.shell", "shells");

    @Test
    void invokesRealFileSearchAndShellProductsThroughPublishedRuntime(@TempDir Path work)
        throws Exception {
        var content = work.resolve("content");
        Files.createDirectories(content.resolve("nested"));
        Files.writeString(content.resolve("nested/existing.txt"), "needle existing\n");
        var graph = applicationGraph(content, work.resolve("storage"));

        try (var harness = PluginAcceptanceHarness.start(work.resolve("package-store"),
            DeploymentTargetStore.inMemory())) {
            var deployed = harness.deploy(graph, PluginAcceptanceHarness.ALL_PACKAGES);
            assertTrue(deployed.observed().values().stream().allMatch(instance ->
                instance.aggregateState() == com.sstlfsj.fibra.engine.ExecutionObservation.State.ACTIVE));

            var written = javaMap(harness.invoke("fs-tools", "write", Map.of(
                "path", "note.txt", "content", "first")));
            assertEquals("create", written.get("operation"));
            assertEquals("first", written.get("after"));
            var edited = javaMap(harness.invoke("fs-tools", "edit", Map.of(
                "path", "note.txt", "oldText", "first", "newText", "second")));
            assertEquals("edit", edited.get("operation"));
            assertEquals("second", edited.get("after"));
            var read = javaMap(harness.invoke("fs-tools", "read", Map.of(
                "path", "note.txt")));
            var firstLine = javaMap(javaList(read.get("lines")).getFirst());
            assertEquals(1, ((Number) firstLine.get("number")).intValue());
            assertEquals("second", firstLine.get("text"));

            var glob = javaMap(harness.invoke("search-tools", "glob", Map.of(
                "pattern", "*.txt")));
            assertTrue(javaList(glob.get("paths")).contains("note.txt"));
            assertTrue(javaList(glob.get("paths")).contains("nested/existing.txt"));

            var grep = javaMap(harness.invoke("search-tools", "grep", Map.of(
                "pattern", "needle", "path", ".")));
            assertEquals(1, ((Number) grep.get("seen")).intValue());
            assertEquals("./nested/existing.txt", javaMap(javaList(grep.get("matches")).getFirst()).get("path"));

            var shell = harness.invoke("shell-tools", "bash", Map.of(
                "command", "printf 'out'; printf 'err' >&2; exit 7",
                "workdir", content.toString(), "timeoutMs", 5_000));
            var shellData = javaMap(shell);
            assertEquals(7, ((Number) shellData.get("exitCode")).intValue());
            assertEquals("out", javaMap(shellData.get("stdout")).get("text"));
            assertEquals("err", javaMap(shellData.get("stderr")).get("text"));
            assertFalse((Boolean) shellData.get("timedOut"));
            assertFalse((Boolean) shellData.get("aborted"));
        }
    }

    @Test
    void realSearchPluginTerminatesManagedProcessesOnTimeoutAndCancellation(@TempDir Path work)
        throws Exception {
        var content = createDirectory(work.resolve("content"));
        var entered = work.resolve("search-entered");
        var pidFile = work.resolve("search.pid");
        var wrapper = hangingExecutable(work.resolve("hanging-rg"), entered, pidFile);
        var graph = searchGraph(content, wrapper, 5_000, 50);

        try (var harness = PluginAcceptanceHarness.start(work.resolve("package-store"),
            DeploymentTargetStore.inMemory())) {
            harness.deploy(graph, "fibra-subprocess", "fibra-subprocess-local",
                "fibra-tool-fs-search");

            var view = harness.engine().published().current();
            var timed = harness.engine().published().invoke(view.viewRevision(), identity(view,
                ToolContributions.KIND, ToolContributions.id("search-tools", "glob")),
                ToolContributions.KIND, ToolContributions.id("search-tools", "glob"),
                ToolRequest.of(Map.of("pattern", "*.txt"))).toFuture();
            awaitFile(entered, timed);
            var timedProcesses = readProcessIds(pidFile);
            assertToolFailure(ToolFailureCode.TIMEOUT, timed);
            assertProcessStopped(timedProcesses.payload());
            assertProcessStopped(timedProcesses.supervisor());

            Files.delete(entered);
            Files.delete(pidFile);
            var cancellation = new CancellationSource();
            view = harness.engine().published().current();
            var cancelled = harness.engine().published().invoke(view.viewRevision(), identity(view,
                ToolContributions.KIND, ToolContributions.id("search-tools", "grep")),
                ToolContributions.KIND, ToolContributions.id("search-tools", "grep"),
                ToolRequest.of(Map.of("pattern", "needle"), cancellation.token())).toFuture();
            awaitFile(entered, cancelled);
            var cancelledProcesses = readProcessIds(pidFile);
            cancellation.cancel();
            assertToolFailure(ToolFailureCode.ABORTED, cancelled);
            assertProcessStopped(cancelledProcesses.payload());
            assertProcessStopped(cancelledProcesses.supervisor());
        }
    }

    @Test
    void sharedStorageRealmPropagatesChangesWhileAnotherRealmIsolatedAndRestartRecovers(
        @TempDir Path work) throws Exception {
        var storage = work.resolve("storage");
        var state = work.resolve("engine-state");
        var packageStore = work.resolve("package-store");
        var graph = storageGraph(storage);

        try (var harness = PluginAcceptanceHarness.start(packageStore,
             new FileDeploymentTargetStore(state))) {
            harness.deploy(graph, "fibra-storage", "fibra-storage-json", "fibra-tool-storage");

            harness.invoke("config-a", "put", Map.of("key", "theme", "value", "dark"));
            var shared = javaMap(harness.invoke("config-b", "load", Map.of()));
            var isolated = javaMap(harness.invoke("config-c", "load", Map.of()));
            assertEquals("dark", javaMap(shared.get("values")).get("theme"));
            assertChanges(harness.invoke("config-b", "changes", Map.of()),
                new ExpectedChange(1, "theme", "PUT", "dark"));
            assertTrue(javaMap(isolated.get("values")).isEmpty());
            assertChanges(harness.invoke("config-c", "changes", Map.of()));

            var captured = harness.engine().published().current();
            var capturedIdentities = new LinkedHashMap<String, Long>();
            for (var tool : List.of("load", "put", "remove", "changes")) {
                capturedIdentities.put(tool, identity(captured, ToolContributions.KIND,
                    ToolContributions.id("config-b", tool)));
            }
            harness.registry().disable("config-b").block(PluginAcceptanceHarness.TIMEOUT);
            var disabledView = harness.engine().published().current();
            for (var tool : List.of("load", "put", "remove", "changes")) {
                assertThrows(ContributionUnavailableException.class,
                    () -> harness.engine().published().invoke(disabledView.viewRevision(),
                        capturedIdentities.get(tool), ToolContributions.KIND,
                        ToolContributions.id("config-b", tool), ToolRequest.of(Map.of()))
                        .block(PluginAcceptanceHarness.TIMEOUT));
            }
            harness.invoke("config-a", "put", Map.of("key", "language", "value", "zh-CN"));
            harness.registry().enable("config-b").block(PluginAcceptanceHarness.TIMEOUT);
            var reenabled = javaMap(harness.invoke("config-b", "load", Map.of()));
            assertEquals("zh-CN", javaMap(reenabled.get("values")).get("language"));
            assertChanges(harness.invoke("config-b", "changes", Map.of()));

            harness.invoke("config-a", "put", Map.of("key", "font", "value", "serif"));
            assertChanges(harness.invoke("config-b", "changes", Map.of()),
                new ExpectedChange(3, "font", "PUT", "serif"));
            harness.invoke("config-c", "put", Map.of("key", "theme", "value", "light"));
            harness.invoke("config-c", "put", Map.of("key", "temporary", "value", true));
            harness.invoke("config-c", "remove", Map.of("key", "temporary"));
            assertChanges(harness.invoke("config-b", "changes", Map.of()),
                new ExpectedChange(3, "font", "PUT", "serif"));
            assertChanges(harness.invoke("config-c", "changes", Map.of()),
                new ExpectedChange(1, "theme", "PUT", "light"),
                new ExpectedChange(2, "temporary", "PUT", true),
                new ExpectedChange(3, "temporary", "REMOVED", null));
            assertEquals("dark", javaMap(javaMap(harness.invoke("config-a", "load", Map.of()))
                .get("values")).get("theme"));
            assertEquals("light", javaMap(javaMap(harness.invoke("config-c", "load", Map.of()))
                .get("values")).get("theme"));
        }

        try (var reopened = PluginAcceptanceHarness.start(packageStore,
             new FileDeploymentTargetStore(state))) {
            var current = reopened.engine().published().current();
            assertEquals(graph.plugins().keySet(), current.engine().units().keySet().stream()
                .map(ExecutionUnitKey::value).collect(java.util.stream.Collectors.toSet()));
            assertEquals("dark", javaMap(javaMap(reopened.invoke("config-b", "load", Map.of()))
                .get("values")).get("theme"));
            assertEquals("light", javaMap(javaMap(reopened.invoke("config-c", "load", Map.of()))
                .get("values")).get("theme"));
            assertChanges(reopened.invoke("config-a", "changes", Map.of()));
            assertChanges(reopened.invoke("config-b", "changes", Map.of()));
            assertChanges(reopened.invoke("config-c", "changes", Map.of()));
            reopened.invoke("config-a", "put", Map.of("key", "layout", "value", "compact"));
            assertChanges(reopened.invoke("config-b", "changes", Map.of()),
                new ExpectedChange(4, "layout", "PUT", "compact"));
        }
    }

    @Test
    void packageGateKeepsDependentExecutionsPendingUntilTheProviderRecovers(
        @TempDir Path work) {
        var graph = storageGraph(work.resolve("storage"));

        try (var harness = PluginAcceptanceHarness.start(work.resolve("package-store"),
            DeploymentTargetStore.inMemory())) {
            harness.deploy(graph, "fibra-storage", "fibra-storage-json", "fibra-tool-storage");
            var active = harness.engine().published().current();
            var retained = instanceIdentities(active, "config-a", "config-b", "config-c");

            harness.registry().disablePackage(new PluginId("fibra-storage-json"))
                .block(PluginAcceptanceHarness.TIMEOUT);

            var pending = harness.engine().published().current();
            var audit = harness.registry().history().getLast();
            assertTrue(audit.succeeded());
            assertEquals(TargetSaveState.SAVED, audit.targetSaveState());
            assertFalse(pending.engineDiagnostics().targetSatisfied());
            assertFalse(pending.engine().target().orElseThrow().selections()
                .get(new PluginId("fibra-storage-json")).enabled());
            assertTrue(pending.engine().target().orElseThrow().desiredGraph().plugins()
                .values().stream().allMatch(DesiredInputEntry::enabled));
            assertNull(pending.engine().units().get(new ExecutionUnitKey("storage-shared")));
            assertNull(pending.engine().units().get(new ExecutionUnitKey("storage-isolated")));
            for (var id : retained.keySet()) {
                assertEquals(com.sstlfsj.fibra.engine.ExecutionObservation.State.PENDING,
                    pending.engine().units().get(new ExecutionUnitKey(id)).aggregateState());
                assertEquals(retained.get(id), instanceIdentity(pending, id));
            }

            harness.registry().enablePackage(new PluginId("fibra-storage-json"))
                .block(PluginAcceptanceHarness.TIMEOUT);

            var recovered = harness.engine().published().current();
            assertTrue(recovered.engineDiagnostics().targetSatisfied());
            assertEquals(retained, instanceIdentities(recovered,
                "config-a", "config-b", "config-c"));
            assertTrue(recovered.engine().units().values().stream().allMatch(unit ->
                unit.aggregateState()
                    == com.sstlfsj.fibra.engine.ExecutionObservation.State.ACTIVE));
            harness.invoke("config-a", "put", Map.of("key", "recovered", "value", true));
            assertEquals(true, javaMap(javaMap(harness.invoke("config-b", "load", Map.of()))
                .get("values")).get("recovered"));
        }
    }

    @Test
    void contractPackageUpgradeReplacesItsRealDependentsWithoutADesiredContractEntry(
        @TempDir Path work) throws Exception {
        var graph = storageGraph(work.resolve("storage"));

        try (var harness = PluginAcceptanceHarness.start(work.resolve("package-store"),
            DeploymentTargetStore.inMemory())) {
            harness.deploy(graph, "fibra-storage", "fibra-storage-json", "fibra-tool-storage");
            var before = harness.engine().published().current();
            var beforeIdentities = instanceIdentities(before, "storage-shared", "storage-isolated",
                "config-a", "config-b", "config-c");
            assertFalse(graph.plugins().values().stream().anyMatch(entry ->
                entry.definitionRef().pluginId().equals("fibra-storage")));

            var variant = variantJar(work, PluginAcceptanceHarness.stagedJar("fibra-storage"),
                "fibra-storage-variant.jar");
            var request = PluginAcceptanceHarness.installRequest(work.resolve("variant-packages"), variant);
            harness.registry().upgrade(request.source()).block(PluginAcceptanceHarness.TIMEOUT);

            var upgraded = harness.engine().published().current();
            for (var entry : beforeIdentities.entrySet()) {
                assertNotEquals(entry.getValue(), instanceIdentity(upgraded, entry.getKey()));
            }
            assertTrue(upgraded.engineDiagnostics().targetSatisfied());
            harness.invoke("config-a", "put", Map.of("key", "contract", "value", "upgraded"));
            assertEquals("upgraded", javaMap(javaMap(harness.invoke("config-b", "load", Map.of()))
                .get("values")).get("contract"));
        }
    }

    @Test
    void localPackageUpgradePreservesUnrelatedProcessStackAndInflightInvocation(@TempDir Path work)
        throws Exception {
        var content = createDirectory(work.resolve("content"));
        var entered = work.resolve("entered");
        var release = work.resolve("release");
        var pidFile = work.resolve("payload.pid");
        var graph = applicationGraph(content, work.resolve("storage"));

        try (var harness = PluginAcceptanceHarness.start(work.resolve("package-store"),
            DeploymentTargetStore.inMemory())) {
            harness.deploy(graph, PluginAcceptanceHarness.ALL_PACKAGES);
            var before = harness.engine().published().current();
            var stableInstances = instanceIdentities(before, "subprocess-provider", "shell-provider",
                "shell-tools", "search-tools", "storage-shared", "storage-isolated", "config-a",
                "config-b", "config-c");
            var stablePackages = packageRevisions(before, "fibra-subprocess",
                "fibra-subprocess-local", "fibra-shell", "fibra-shell-local",
                "fibra-tool-shell", "fibra-storage", "fibra-storage-json", "fibra-tool-storage");

            var command = "printf '%s %s' $$ $PPID > " + shellQuote(pidFile)
                + "; : > " + shellQuote(entered)
                + "; while [ ! -e " + shellQuote(release) + " ]; do sleep 0.05; done; printf held";
            var heldView = harness.engine().published().current();
            var held = harness.engine().published().invoke(heldView.viewRevision(), identity(heldView,
                ToolContributions.KIND, ToolContributions.id("shell-tools", "bash")),
                ToolContributions.KIND, ToolContributions.id("shell-tools", "bash"),
                ToolRequest.of(Map.of("command", command, "workdir", content.toString(),
                    "timeoutMs", 15_000))).toFuture();
            awaitFile(entered, held);
            var processes = readProcessIds(pidFile);
            var payloadPid = processes.payload();
            var supervisorPid = processes.supervisor();
            assertTrue(ProcessHandle.of(payloadPid).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(ProcessHandle.of(supervisorPid).map(ProcessHandle::isAlive).orElse(false));

            var variant = variantJar(work, PluginAcceptanceHarness.stagedJar("fibra-fs-local"),
                "fibra-fs-local-variant.jar");
            var request = PluginAcceptanceHarness.installRequest(work.resolve("variant-packages"), variant);
            var changed = harness.registry().upgrade(request.source()).block(PluginAcceptanceHarness.TIMEOUT);

            assertNotEquals(instanceIdentity(before, "fs-provider"),
                changed.observed().get("fs-provider").executions().getFirst().runtimeInstanceId());
            assertEquals(stableInstances, instanceIdentities(harness.engine().published().current(),
                "subprocess-provider", "shell-provider", "shell-tools", "search-tools",
                "storage-shared", "storage-isolated", "config-a", "config-b", "config-c"));
            assertEquals(stablePackages, packageRevisions(harness.engine().published().current(),
                "fibra-subprocess", "fibra-subprocess-local", "fibra-shell", "fibra-shell-local",
                "fibra-tool-shell", "fibra-storage", "fibra-storage-json", "fibra-tool-storage"));
            assertFalse(held.isDone());
            assertTrue(ProcessHandle.of(payloadPid).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(ProcessHandle.of(supervisorPid).map(ProcessHandle::isAlive).orElse(false));

            Files.writeString(release, "release");
            assertEquals("held", text(held.get(PluginAcceptanceHarness.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)));
            assertFalse(ProcessHandle.of(payloadPid).map(ProcessHandle::isAlive).orElse(false));
            assertFalse(ProcessHandle.of(supervisorPid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void disablingToolWaitsForInflightCallThenRevokesItsPublishedRoute(@TempDir Path work)
        throws Exception {
        var content = createDirectory(work.resolve("content"));
        var entered = work.resolve("entered");
        var release = work.resolve("release");
        var graph = applicationGraph(content, work.resolve("storage"));

        try (var harness = PluginAcceptanceHarness.start(work.resolve("package-store"),
            DeploymentTargetStore.inMemory())) {
            harness.deploy(graph, PluginAcceptanceHarness.ALL_PACKAGES);
            var current = harness.engine().published().current();
            var heldIdentity = identity(current, ToolContributions.KIND,
                ToolContributions.id("shell-tools", "bash"));
            var command = ": > " + shellQuote(entered) + "; while [ ! -e "
                + shellQuote(release) + " ]; do sleep 0.05; done; printf drained";
            var held = harness.engine().published().invoke(current.viewRevision(), heldIdentity,
                ToolContributions.KIND, ToolContributions.id("shell-tools", "bash"),
                ToolRequest.of(Map.of("command", command, "workdir", content.toString(),
                    "timeoutMs", 15_000))).toFuture();
            awaitFile(entered, held);

            var disabled = harness.registry().disable("shell-tools").toFuture();
            Thread.sleep(150);
            assertFalse(disabled.isDone());
            assertFalse(held.isDone());
            Files.writeString(release, "release");
            assertEquals("drained", text(held.get(PluginAcceptanceHarness.TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS)));
            disabled.get(PluginAcceptanceHarness.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            var after = harness.engine().published().current();
            assertFalse(after.engine().units().containsKey(new ExecutionUnitKey("shell-tools")));
            assertThrows(ContributionUnavailableException.class, () ->
                harness.engine().published().invoke(after.viewRevision(), heldIdentity,
                    ToolContributions.KIND,
                    ToolContributions.id("shell-tools", "bash"), ToolRequest.of(Map.of(
                        "command", "true", "workdir", content.toString(), "timeoutMs", 1_000)))
                    .block(PluginAcceptanceHarness.TIMEOUT));
        }
    }

    private static DesiredInputGraph applicationGraph(Path content, Path storage) {
        var entries = new ArrayList<DesiredInputEntry>();
        entries.add(entry("fs-provider", "fs-local", Map.of(
            "root", content.toString(), "spillDirectory", ".fibra-spill"), FS_REALM));
        entries.add(entry("fs-tools", "tool-fs", null, FS_REALM));
        entries.add(entry("subprocess-provider", "fibra-subprocess-local", Map.of(
            "nodeExecutable", PluginAcceptanceHarness.executable("node").toString()), PROCESS_REALM));
        entries.add(entry("search-tools", "tool-fs-search", Map.of(
            "rgExecutable", PluginAcceptanceHarness.executable("rg").toString(),
            "workdir", content.toString()), PROCESS_REALM));
        var shellRealms = new LinkedHashMap<>(PROCESS_REALM);
        shellRealms.putAll(SHELL_REALM);
        entries.add(entry("shell-provider", "fibra-shell-local", Map.of(
            "bashExecutable", PluginAcceptanceHarness.executable("bash").toString(),
            "outputMaxBytes", 1_048_576, "graceMillis", 500), shellRealms));
        entries.add(entry("shell-tools", "fibra-tool-shell", null, shellRealms));
        entries.addAll(storageEntries(storage));
        return new DesiredInputGraph(List.copyOf(entries));
    }

    private static DesiredInputGraph storageGraph(Path storage) {
        return new DesiredInputGraph(storageEntries(storage));
    }

    private static DesiredInputGraph searchGraph(Path content, Path executable,
                                                 long timeoutMillis, long graceMillis) {
        return new DesiredInputGraph(List.of(
            entry("subprocess-provider", "fibra-subprocess-local", Map.of(
                "nodeExecutable", PluginAcceptanceHarness.executable("node").toString()),
                PROCESS_REALM),
            entry("search-tools", "tool-fs-search", Map.of(
                "rgExecutable", executable.toString(),
                "workdir", content.toString(),
                "timing", Map.of("timeoutMillis", timeoutMillis,
                    "graceMillis", graceMillis)), PROCESS_REALM)));
    }

    private static List<DesiredInputEntry> storageEntries(Path storage) {
        var shared = realm("fibra.storage", "shared");
        var isolated = realm("fibra.storage", "isolated");
        return List.of(
            entry("storage-shared", "storage-json", Map.of(
                "root", storage.resolve("shared").toString()), shared),
            entry("config-a", "tool-storage", null, shared),
            entry("config-b", "tool-storage", null, shared),
            entry("storage-isolated", "storage-json", Map.of(
                "root", storage.resolve("isolated").toString()), isolated),
            entry("config-c", "tool-storage", null, isolated));
    }

    private static DesiredInputEntry entry(String id, String definition, Object config,
                                           Map<String, LiteralValue> realms) {
        var pluginId = definition.startsWith("fibra-") ? definition : "fibra-" + definition;
        var builder = DesiredInputEntry.builder(id,
            new PluginDefinitionRef(pluginId, "main", definition)).realms(realms);
        if (config != null) builder.config(LiteralValue.of(config));
        return builder.build();
    }

    private static Map<String, LiteralValue> realm(String service, String value) {
        return Map.of(service, LiteralValue.of(value));
    }

    private static void assertChanges(ToolResult result, ExpectedChange... expected) {
        var snapshot = javaMap(result);
        assertFalse((Boolean) snapshot.get("dropped"));
        var changes = javaList(snapshot.get("changes")).stream()
            .map(FormalMultiPluginIT::javaMap).toList();
        assertEquals(expected.length, changes.size());
        for (var index = 0; index < expected.length; index++) {
            var actual = changes.get(index);
            var change = expected[index];
            assertEquals(change.revision(), ((Number) actual.get("revision")).longValue());
            assertEquals(change.key(), actual.get("key"));
            assertEquals(change.operation(), actual.get("operation"));
            assertEquals(change.value(), actual.get("value"));
        }
    }

    private record ExpectedChange(long revision, String key, String operation, Object value) {
    }

    private static Map<String, String> instanceIdentities(PublishedView view, String... ids) {
        var result = new LinkedHashMap<String, String>();
        for (var id : ids) result.put(id, instanceIdentity(view, id));
        return result;
    }

    private static String instanceIdentity(PublishedView view, String id) {
        return view.engine().units().get(new ExecutionUnitKey(id)).executions().getFirst()
            .runtimeInstanceId();
    }

    private static long identity(PublishedView view, ContributionKind<?, ?, ?> kind, ContributionId id) {
        return view.contributions().entries().stream()
            .filter(entry -> entry.kind().equals(kind.name()) && entry.id().equals(id))
            .map(entry -> entry.registrationIdentity()).findFirst().orElseThrow();
    }

    private static Map<String, String> packageRevisions(PublishedView view, String... pluginIds) {
        var selections = view.engine().target().orElseThrow().selections();
        var result = new LinkedHashMap<String, String>();
        for (var id : pluginIds) {
            result.put(id, selections.entrySet().stream()
                .filter(entry -> entry.getKey().value().equals(id))
                .findFirst().orElseThrow().getValue().packageRevision());
        }
        return result;
    }

    private static Path variantJar(Path work, Path source, String targetName) throws IOException {
        var target = work.resolve(targetName);
        try (var input = new JarInputStream(Files.newInputStream(source));
             var output = new JarOutputStream(Files.newOutputStream(target))) {
            JarEntry entry;
            while ((entry = input.getNextJarEntry()) != null) {
                output.putNextEntry(new JarEntry(entry.getName()));
                input.transferTo(output);
                output.closeEntry();
            }
            output.putNextEntry(new JarEntry("META-INF/fibra/acceptance-variant"));
            output.write("variant".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return target;
    }

    private static void awaitFile(Path file, CompletableFuture<?> operation) throws Exception {
        var parent = file.getParent();
        try (var watcher = FileSystems.getDefault().newWatchService()) {
            parent.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY);
            var deadline = System.nanoTime() + PluginAcceptanceHarness.TIMEOUT.toNanos();
            while (!Files.exists(file)) {
                if (operation.isDone()) {
                    operation.get();
                    throw new IllegalStateException("operation completed before creating " + file);
                }
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("timed out waiting for " + file);
                var key = watcher.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)),
                    TimeUnit.NANOSECONDS);
                if (key != null) key.reset();
            }
        }
    }

    private static Path hangingExecutable(Path executable, Path entered, Path pidFile)
        throws IOException {
        Files.writeString(executable, "#!/bin/sh\n"
            + "printf '%s %s' $$ $PPID > " + shellQuote(pidFile) + "\n"
            + ": > " + shellQuote(entered) + "\n"
            + "while :; do sleep 1; done\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(executable, Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        return executable;
    }

    private static void assertToolFailure(ToolFailureCode code, CompletableFuture<?> operation) {
        var failure = assertThrows(ExecutionException.class,
            () -> operation.get(PluginAcceptanceHarness.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertEquals(ToolException.class, failure.getCause().getClass());
        assertEquals(code, ((ToolException) failure.getCause()).failure().code());
    }

    private static void assertProcessStopped(long pid) {
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
            () -> "process remains alive: " + pid);
    }

    private static ProcessIds readProcessIds(Path pidFile) throws IOException {
        var values = Files.readString(pidFile).trim().split("\\s+");
        return new ProcessIds(Long.parseLong(values[0]), Long.parseLong(values[1]));
    }

    private record ProcessIds(long payload, long supervisor) {
    }

    private static String shellQuote(Path path) {
        return "'" + path.toString().replace("'", "'\\''") + "'";
    }

    private static Path createDirectory(Path path) {
        try {
            return Files.createDirectories(path);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Map<String, Object> javaMap(ToolResult result) {
        return javaMap(result.structuredContent().orElseThrow().toJava());
    }

    private static String text(ToolResult result) {
        return ((ToolContent.Text) result.content().getFirst()).text();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> javaMap(Object value) {
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> javaList(Object value) {
        return (List<Object>) value;
    }
}
