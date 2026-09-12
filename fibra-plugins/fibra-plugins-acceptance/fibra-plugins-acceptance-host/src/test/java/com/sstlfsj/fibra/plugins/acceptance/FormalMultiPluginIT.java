package com.sstlfsj.fibra.plugins.acceptance;

import com.sstlfsj.fibra.CancellationSource;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.bridge.ContributionUnavailableException;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.engine.FileEngineStateStore;
import com.sstlfsj.fibra.engine.PublishedView;
import com.sstlfsj.fibra.engine.RuntimeResourceSnapshot;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolException;
import com.sstlfsj.fibra.plugins.tool.ToolFailureCode;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
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

        try (var harness = PluginAcceptanceHarness.start(work.resolve("artifacts"),
            com.sstlfsj.fibra.engine.EngineStateStore.inMemory(),
            InMemoryDesiredStateRepository.empty())) {
            var deployed = harness.deploy(graph, PluginAcceptanceHarness.ALL_ARTIFACTS);
            assertTrue(deployed.observed().values().stream().allMatch(instance ->
                instance.state() == com.sstlfsj.fibra.PluginInstanceState.ACTIVE));

            var written = javaMap(harness.invoke("fs-tools", "write", Map.of(
                "path", "note.txt", "content", "first")).data().toJava());
            assertEquals("create", written.get("operation"));
            assertEquals("first", written.get("after"));
            var edited = javaMap(harness.invoke("fs-tools", "edit", Map.of(
                "path", "note.txt", "oldText", "first", "newText", "second")).data().toJava());
            assertEquals("edit", edited.get("operation"));
            assertEquals("second", edited.get("after"));
            var read = javaMap(harness.invoke("fs-tools", "read", Map.of(
                "path", "note.txt")).data().toJava());
            var firstLine = javaMap(javaList(read.get("lines")).getFirst());
            assertEquals(1, ((Number) firstLine.get("number")).intValue());
            assertEquals("second", firstLine.get("text"));

            var glob = javaMap(harness.invoke("search-tools", "glob", Map.of(
                "pattern", "*.txt")).data().toJava());
            assertTrue(javaList(glob.get("paths")).contains("note.txt"));
            assertTrue(javaList(glob.get("paths")).contains("nested/existing.txt"));

            var grep = javaMap(harness.invoke("search-tools", "grep", Map.of(
                "pattern", "needle", "path", ".")).data().toJava());
            assertEquals(1, ((Number) grep.get("seen")).intValue());
            assertEquals("./nested/existing.txt", javaMap(javaList(grep.get("matches")).getFirst()).get("path"));

            var shell = harness.invoke("shell-tools", "bash", Map.of(
                "command", "printf 'out'; printf 'err' >&2; exit 7",
                "workdir", content.toString(), "timeoutMs", 5_000));
            var shellData = javaMap(shell.data().toJava());
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

        try (var harness = PluginAcceptanceHarness.start(work.resolve("artifacts"),
            com.sstlfsj.fibra.engine.EngineStateStore.inMemory(),
            InMemoryDesiredStateRepository.empty())) {
            harness.deploy(graph, "fibra-subprocess", "fibra-subprocess-local",
                "fibra-tool-fs-search");

            var view = harness.engine().published().current();
            var timed = harness.engine().published().invoke(view.viewRevision(),
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
            var cancelled = harness.engine().published().invoke(view.viewRevision(),
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
    void sharedStorageRealmPropagatesEventsWhileAnotherRealmIsolatedAndRestartRecovers(
        @TempDir Path work) throws Exception {
        var storage = work.resolve("storage");
        var state = work.resolve("engine-state");
        var artifacts = work.resolve("artifacts");
        var graph = storageGraph(storage);

        try (var harness = PluginAcceptanceHarness.start(artifacts,
             new FileEngineStateStore(state),
                 InMemoryDesiredStateRepository.empty())) {
            harness.deploy(graph, "fibra-storage", "fibra-storage-json",
                "fibra-config-client-test-plugin");

            harness.invoke("config-a", "config", Map.of(
                "operation", "put", "key", "theme", "value", "dark"));
            var shared = configResult(harness.invoke("config-b", "config", Map.of("operation", "load")));
            var isolated = configResult(harness.invoke("config-c", "config", Map.of("operation", "load")));
            assertEquals("dark", javaMap(shared.get("values")).get("theme"));
            assertEquals(1, javaList(shared.get("events")).size());
            assertTrue(javaMap(isolated.get("values")).isEmpty());
            assertTrue(javaList(isolated.get("events")).isEmpty());

            var configBEvents = storage.resolve("config-b.events");
            assertEquals(List.of("1"), Files.readAllLines(configBEvents));
            harness.registry().disable("config-b").block(PluginAcceptanceHarness.TIMEOUT);
            harness.invoke("config-a", "config", Map.of(
                "operation", "put", "key", "language", "value", "zh-CN"));
            assertEquals(List.of("1"), Files.readAllLines(configBEvents));
            harness.registry().enable("config-b").block(PluginAcceptanceHarness.TIMEOUT);
            var reenabled = configResult(harness.invoke("config-b", "config",
                Map.of("operation", "load")));
            assertEquals("zh-CN", javaMap(reenabled.get("values")).get("language"));
            assertTrue(javaList(reenabled.get("events")).isEmpty());

            harness.invoke("config-c", "config", Map.of(
                "operation", "put", "key", "theme", "value", "light"));
            assertEquals("dark", javaMap(configResult(harness.invoke("config-a", "config",
                Map.of("operation", "load"))).get("values")).get("theme"));
            assertEquals("light", javaMap(configResult(harness.invoke("config-c", "config",
                Map.of("operation", "load"))).get("values")).get("theme"));
        }

        try (var reopened = PluginAcceptanceHarness.start(artifacts,
             new FileEngineStateStore(state),
                 InMemoryDesiredStateRepository.empty())) {
            var current = reopened.engine().published().current();
            assertEquals(graph.plugins().keySet(), current.engine().instances().keySet());
            assertEquals("dark", javaMap(configResult(reopened.invoke("config-b", "config",
                Map.of("operation", "load"))).get("values")).get("theme"));
            assertEquals("light", javaMap(configResult(reopened.invoke("config-c", "config",
                Map.of("operation", "load"))).get("values")).get("theme"));
        }
    }

    @Test
    void localArtifactUpdatePreservesUnrelatedProcessStackAndInflightInvocation(@TempDir Path work)
        throws Exception {
        var content = createDirectory(work.resolve("content"));
        var entered = work.resolve("entered");
        var release = work.resolve("release");
        var pidFile = work.resolve("payload.pid");
        var graph = applicationGraph(content, work.resolve("storage"));

        try (var harness = PluginAcceptanceHarness.start(work.resolve("artifacts"),
            com.sstlfsj.fibra.engine.EngineStateStore.inMemory(),
            InMemoryDesiredStateRepository.empty())) {
            harness.deploy(graph, PluginAcceptanceHarness.ALL_ARTIFACTS);
            var before = harness.engine().published().current();
            var stableInstances = instanceIdentities(before, "subprocess-provider", "shell-provider",
                "shell-tools", "search-tools", "storage-shared", "config-a");
            var stableResources = resourceIdentities(before, "fibra-subprocess",
                "fibra-subprocess-local", "fibra-shell", "fibra-shell-local",
                "fibra-tool-shell", "fibra-storage", "fibra-storage-json");

            var command = "printf '%s %s' $$ $PPID > " + shellQuote(pidFile)
                + "; : > " + shellQuote(entered)
                + "; while [ ! -e " + shellQuote(release) + " ]; do sleep 0.05; done; printf held";
            var heldView = harness.engine().published().current();
            var held = harness.engine().published().invoke(heldView.viewRevision(),
                ToolContributions.KIND, ToolContributions.id("shell-tools", "bash"),
                ToolRequest.of(Map.of("command", command, "workdir", content.toString(),
                    "timeoutMs", 15_000))).toFuture();
            awaitFile(entered, held);
            var processes = readProcessIds(pidFile);
            var payloadPid = processes.payload();
            var supervisorPid = processes.supervisor();
            assertTrue(ProcessHandle.of(payloadPid).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(ProcessHandle.of(supervisorPid).map(ProcessHandle::isAlive).orElse(false));

            var variant = variantJar(work, PluginAcceptanceHarness.stagedJar("fibra-fs-local"));
            var request = com.sstlfsj.fibra.registry.PluginInstallRequest.builder()
                .artifactId(new ArtifactId("fibra-fs-local"))
                .runtimeId(com.sstlfsj.fibra.runtime.java.JavaPluginRuntimeAdapter.RUNTIME_ID)
                .version(manifestVersion(variant)).source(variant).build();
            var changed = harness.registry().upgrade(request).block(PluginAcceptanceHarness.TIMEOUT);

            assertNotEquals(before.engine().instances().get("fs-provider").identity(),
                changed.observed().get("fs-provider").identity());
            assertEquals(stableInstances, instanceIdentities(harness.engine().published().current(),
                "subprocess-provider", "shell-provider", "shell-tools", "search-tools",
                "storage-shared", "config-a"));
            assertEquals(stableResources, resourceIdentities(harness.engine().published().current(),
                "fibra-subprocess", "fibra-subprocess-local", "fibra-shell", "fibra-shell-local",
                "fibra-tool-shell", "fibra-storage", "fibra-storage-json"));
            assertFalse(held.isDone());
            assertTrue(ProcessHandle.of(payloadPid).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(ProcessHandle.of(supervisorPid).map(ProcessHandle::isAlive).orElse(false));

            Files.writeString(release, "release");
            assertEquals("held", held.get(PluginAcceptanceHarness.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).text());
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

        try (var harness = PluginAcceptanceHarness.start(work.resolve("artifacts"),
            com.sstlfsj.fibra.engine.EngineStateStore.inMemory(),
            InMemoryDesiredStateRepository.empty())) {
            harness.deploy(graph, PluginAcceptanceHarness.ALL_ARTIFACTS);
            var current = harness.engine().published().current();
            var command = ": > " + shellQuote(entered) + "; while [ ! -e "
                + shellQuote(release) + " ]; do sleep 0.05; done; printf drained";
            var held = harness.engine().published().invoke(current.viewRevision(),
                ToolContributions.KIND, ToolContributions.id("shell-tools", "bash"),
                ToolRequest.of(Map.of("command", command, "workdir", content.toString(),
                    "timeoutMs", 15_000))).toFuture();
            awaitFile(entered, held);

            var disabled = harness.registry().disable("shell-tools").toFuture();
            Thread.sleep(150);
            assertFalse(disabled.isDone());
            assertFalse(held.isDone());
            Files.writeString(release, "release");
            assertEquals("drained", held.get(PluginAcceptanceHarness.TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS).text());
            disabled.get(PluginAcceptanceHarness.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            var after = harness.engine().published().current();
            assertFalse(after.engine().instances().containsKey("shell-tools"));
            assertThrows(ContributionUnavailableException.class, () ->
                harness.engine().published().invoke(after.viewRevision(), ToolContributions.KIND,
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
            entry("config-a", "config-client-test", Map.of(
                "eventLog", storage.resolve("config-a.events").toString()), shared),
            entry("config-b", "config-client-test", Map.of(
                "eventLog", storage.resolve("config-b.events").toString()), shared),
            entry("storage-isolated", "storage-json", Map.of(
                "root", storage.resolve("isolated").toString()), isolated),
            entry("config-c", "config-client-test", Map.of(
                "eventLog", storage.resolve("config-c.events").toString()), isolated));
    }

    private static DesiredInputEntry entry(String id, String definition, Object config,
                                           Map<String, LiteralValue> realms) {
        var builder = DesiredInputEntry.builder(id, definition).realms(realms);
        if (config != null) builder.config(LiteralValue.of(config));
        return builder.build();
    }

    private static Map<String, LiteralValue> realm(String service, String value) {
        return Map.of(service, LiteralValue.of(value));
    }

    private static Map<String, Object> configResult(com.sstlfsj.fibra.plugins.tool.ToolResult result) {
        var root = javaMap(result.data().toJava());
        var document = javaMap(root.get("document"));
        document.put("events", root.get("events"));
        return document;
    }

    private static Map<String, Long> instanceIdentities(PublishedView view, String... ids) {
        var result = new LinkedHashMap<String, Long>();
        for (var id : ids) result.put(id, view.engine().instances().get(id).identity());
        return result;
    }

    private static Map<String, String> resourceIdentities(PublishedView view, String... artifactIds) {
        var resources = view.engine().runtimes().get(
            com.sstlfsj.fibra.runtime.java.JavaPluginRuntimeAdapter.RUNTIME_ID).resources();
        var result = new LinkedHashMap<String, String>();
        for (var id : artifactIds) {
            var resource = resources.stream().filter(value -> value.artifact().id().value().equals(id))
                .filter(value -> value.state() == RuntimeResourceSnapshot.State.ACTIVE)
                .findFirst().orElseThrow();
            result.put(id, resource.identity());
        }
        return result;
    }

    private static Path variantJar(Path work, Path source) throws IOException {
        var target = work.resolve("fibra-fs-local-variant.jar");
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

    private static String manifestVersion(Path jar) {
        var manifest = PluginAcceptanceHarness.manifest(jar);
        for (var line : manifest.lines().toList()) {
            if (line.startsWith("version: ")) return line.substring("version: ".length()).trim();
        }
        throw new IllegalStateException("manifest has no version");
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
        assertEquals(code, ((ToolException) failure.getCause()).code());
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> javaMap(Object value) {
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> javaList(Object value) {
        return (List<Object>) value;
    }
}
