package verification.distribution;

import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.engine.ChangePhase;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.PluginArtifactProbe;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
import com.sstlfsj.fibra.plugins.tool.ToolResult;
import com.sstlfsj.fibra.registry.InMemoryPluginAuditRepository;
import com.sstlfsj.fibra.registry.PluginDeploymentRequest;
import com.sstlfsj.fibra.registry.PluginEnableRequest;
import com.sstlfsj.fibra.registry.PluginInstallRequest;
import com.sstlfsj.fibra.registry.PluginRegistry;
import com.sstlfsj.fibra.runtime.java.JavaPluginRuntimeAdapter;
import com.sstlfsj.fibra.runtime.node.NodePluginRuntimeAdapter;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeOptions;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleConsumerTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final ArtifactId ARTIFACT = new ArtifactId("lifecycle");
    private static final String INSTANCE = "lifecycle-instance";

    @ParameterizedTest(name = "{0} 仓外生命周期")
    @EnumSource(RuntimeFlavor.class)
    @Timeout(90)
    void provesThePublicRegistryLifecycle(RuntimeFlavor runtime, @TempDir Path work) throws Exception {
        var fixture = runtime.fixture(work);
        var probe = new PluginArtifactProbe(List.of(new JavaPluginRuntimeAdapter(), fixture.nodeAdapter()));
        var initial = fixture.artifact("one", "1.0.0");
        var inspected = probe.probe(initial.source()).block(TIMEOUT);
        assertEquals(ARTIFACT, inspected.artifactId());
        assertEquals(runtime.runtimeId(), inspected.runtimeId());

        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(new ArtifactStore(work.resolve("artifacts")))
            .runtimeAdapter(new JavaPluginRuntimeAdapter())
            .runtimeAdapter(fixture.nodeAdapter()).build()) {
            engine.start().block(TIMEOUT);
            var registry = new PluginRegistry(engine, new InMemoryPluginAuditRepository());

            var installed = registry.install(request(inspected)).block(TIMEOUT);
            assertTrue(installed.observed().isEmpty(), "install 不得自动启用实例");
            assertTrue(installed.desiredGraph().plugins().isEmpty());

            var first = config(fixture, "one");
            var enabled = registry.enable(PluginEnableRequest.of(INSTANCE, "lifecycle", first)).block(TIMEOUT);
            assertEquals(PluginInstanceState.ACTIVE, enabled.observed().get(INSTANCE).state());
            assertFact(engine, fixture, "one", first);
            var previousPids = fixture.activePids();

            var updated = config(fixture, "configured");
            registry.enable(PluginEnableRequest.of(INSTANCE, "lifecycle", updated)).block(TIMEOUT);
            fixture.assertStopped(previousPids);
            assertFact(engine, fixture, "one", updated);
            previousPids = fixture.activePids();

            var disabled = registry.disable(INSTANCE).block(TIMEOUT);
            fixture.assertStopped(previousPids);
            assertFalse(disabled.observed().containsKey(INSTANCE));
            assertFalse(disabled.desiredGraph().plugins().get(INSTANCE).enabled());
            assertEquals(updated, disabled.desiredGraph().plugins().get(INSTANCE).config());

            registry.enable(INSTANCE).block(TIMEOUT);
            assertFact(engine, fixture, "one", updated);
            previousPids = fixture.activePids();
            var beforeUpgrade = fixture.loader(engine);
            var v2 = fixture.artifact("two", "2.0.0");
            var upgraded = registry.upgrade(request(probe.probe(v2.source()).block(TIMEOUT))).block(TIMEOUT);
            fixture.assertStopped(previousPids);
            assertEquals("2.0.0", upgraded.artifacts().get(ARTIFACT).version());
            assertFact(engine, fixture, "two", updated);
            fixture.assertCollected(beforeUpgrade);

            var stableRevision = upgraded.artifacts().get(ARTIFACT).revision();
            var stableResource = resourceIdentity(engine, runtime);
            var stableInstance = engine.published().current().engine().instances().get(INSTANCE).identity();
            var stableLoader = fixture.loader(engine);
            var stablePids = fixture.activePids();
            var audits = registry.history().size();
            var same = registry.upgrade(request(probe.probe(v2.source()).block(TIMEOUT))).block(TIMEOUT);
            assertNotEquals(upgraded.viewRevision(), same.viewRevision());
            assertEquals(audits + 1, registry.history().size());
            assertEquals(stableRevision, same.artifacts().get(ARTIFACT).revision());
            assertEquals(stableResource, resourceIdentity(engine, runtime));
            assertEquals(stableInstance, engine.published().current().engine().instances().get(INSTANCE).identity());
            fixture.assertSame(stableLoader, engine);
            fixture.assertSamePids(stablePids);

            var replacement = fixture.artifact("replacement", "2.0.0");
            registry.upgrade(request(probe.probe(replacement.source()).block(TIMEOUT))).block(TIMEOUT);
            fixture.assertStopped(stablePids);
            assertNotEquals(stableRevision, registry.snapshot().artifacts().get(ARTIFACT).revision());
            assertFact(engine, fixture, "replacement", updated);
            fixture.assertCollected(stableLoader);

            var removedLoader = atomicallyRemove(registry, engine, fixture, runtime);
            fixture.assertStopped(fixture.allPids());
            fixture.assertSessionsEmpty();
            fixture.assertCollected(removedLoader);
            assertTrue(registry.snapshot().artifacts().isEmpty());
            assertTrue(registry.snapshot().desiredGraph().plugins().isEmpty());

            var reinstalled = fixture.artifact("reinstalled", "2.0.0");
            registry.install(request(probe.probe(reinstalled.source()).block(TIMEOUT))).block(TIMEOUT);
            registry.enable(PluginEnableRequest.of(INSTANCE, "lifecycle", config(fixture, "reinstalled"))).block(TIMEOUT);
            assertFact(engine, fixture, "reinstalled", config(fixture, "reinstalled"));
            var reinstalledLoader = fixture.loader(engine);
            previousPids = fixture.activePids();
            registry.disable(INSTANCE).block(TIMEOUT);
            fixture.assertStopped(previousPids);
            var uninstalled = registry.uninstall(ARTIFACT).block(TIMEOUT);
            assertTrue(uninstalled.artifacts().isEmpty());
            assertTrue(uninstalled.desiredGraph().plugins().containsKey(INSTANCE),
                "uninstall 不得修改 desired graph");
            assertFalse(uninstalled.desiredGraph().plugins().get(INSTANCE).enabled());
            fixture.assertSessionsEmpty();
            fixture.assertCollected(reinstalledLoader);
        }
    }

    private static PluginInstallRequest request(com.sstlfsj.fibra.engine.DeploymentArtifact artifact) {
        return PluginInstallRequest.builder().artifactId(artifact.artifactId())
            .runtimeId(artifact.runtimeId()).version(artifact.version()).source(artifact.source()).build();
    }

    private static LiteralValue config(Fixture fixture, String configuredVariant) {
        var values = new LinkedHashMap<String, Object>();
        values.put("variant", configuredVariant);
        values.put("holdEntered", fixture.holdEntered().toString());
        values.put("release", fixture.releaseFile().toString());
        fixture.pidFile().ifPresent(value -> values.put("pidFile", value.toString()));
        return LiteralValue.of(values);
    }

    private static void assertFact(FibraEngine engine, Fixture fixture, String packagedVariant,
                                   LiteralValue config) {
        var result = invoke(engine, ToolRequest.of(Map.of("operation", "facts")));
        var values = map(result.structuredContent().orElseThrow().toJava());
        assertEquals(packagedVariant, values.get("variant"));
        assertEquals(config.toJava(), values.get("config"));
        fixture.assertInvocation(values);
    }

    private static ToolResult invoke(FibraEngine engine, ToolRequest request) {
        return invokeAsync(engine, request).block(TIMEOUT);
    }

    private static reactor.core.publisher.Mono<ToolResult> invokeAsync(FibraEngine engine,
                                                                        ToolRequest request) {
        var current = engine.published().current();
        var id = ToolContributions.id(INSTANCE, "lifecycle");
        var identity = current.contributions().entries().stream()
            .filter(entry -> entry.kind().equals(ToolContributions.KIND.name()) && entry.id().equals(id))
            .findFirst().orElseThrow().registrationIdentity();
        return engine.published().invoke(current.viewRevision(), identity, ToolContributions.KIND, id, request);
    }

    private static String resourceIdentity(FibraEngine engine, RuntimeFlavor runtime) {
        return engine.published().current().engine().runtimes().get(runtime.runtimeId()).resources()
            .getFirst().identity();
    }

    private static WeakReference<ClassLoader> atomicallyRemove(PluginRegistry registry,
                                                                 FibraEngine engine,
                                                                 Fixture fixture,
                                                                 RuntimeFlavor runtime) throws Exception {
        var loader = fixture.loader(engine);
        var resource = resourceIdentity(engine, runtime);
        var instance = engine.published().current().engine().instances().get(INSTANCE).identity();
        var pids = fixture.activePids();
        var hold = invokeAsync(engine, ToolRequest.of(Map.of("operation", "hold"))).toFuture();
        fixture.awaitHold();
        var revoking = engine.published().views().filter(view ->
            view.engineDiagnostics().phase() == ChangePhase.RECONCILING
                && view.contributions().entries().stream().noneMatch(entry ->
                    entry.kind().equals(ToolContributions.KIND.name())
                        && entry.id().equals(ToolContributions.id(INSTANCE, "lifecycle"))))
            .next().toFuture();
        var removal = registry.deploy(new PluginDeploymentRequest(List.of(),
            new DesiredInputGraph(List.of()))).toFuture();
        revoking.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThrows(java.util.concurrent.TimeoutException.class,
            () -> removal.get(300, TimeUnit.MILLISECONDS), "排空中的调用不得被完整 deploy 越过");
        assertEquals(resource, resourceIdentity(engine, runtime));
        assertEquals(instance, engine.published().current().engine().instances().get(INSTANCE).identity());
        fixture.assertActiveLoader(loader);
        fixture.assertActivePids(pids);
        fixture.release();
        hold.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        removal.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        return loader;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    private enum RuntimeFlavor {
        JAVA {
            @Override Fixture fixture(Path work) { return new JavaFixture(work); }
            @Override com.sstlfsj.fibra.artifact.RuntimeId runtimeId() {
                return JavaPluginRuntimeAdapter.RUNTIME_ID;
            }
        },
        NODE {
            @Override Fixture fixture(Path work) { return new NodeFixture(work); }
            @Override com.sstlfsj.fibra.artifact.RuntimeId runtimeId() {
                return NodePluginRuntimeAdapter.RUNTIME_ID;
            }
        };

        abstract Fixture fixture(Path work);
        abstract com.sstlfsj.fibra.artifact.RuntimeId runtimeId();
    }

    private abstract static class Fixture {
        private final Path work;
        private final Path holdEntered;
        private final Path release;

        private Fixture(Path work) {
            this.work = work;
            holdEntered = work.resolve("hold-entered");
            release = work.resolve("release");
        }

        abstract com.sstlfsj.fibra.engine.DeploymentArtifact artifact(String variant, String version)
            throws Exception;
        abstract NodePluginRuntimeAdapter nodeAdapter();
        abstract void assertInvocation(Map<String, Object> fact);
        abstract WeakReference<ClassLoader> loader(FibraEngine engine);
        abstract void assertActiveLoader(WeakReference<ClassLoader> loader);
        abstract void assertSame(WeakReference<ClassLoader> expected, FibraEngine engine);
        abstract void assertCollected(WeakReference<ClassLoader> reference) throws Exception;
        abstract List<Long> activePids() throws Exception;
        abstract List<Long> allPids() throws Exception;
        abstract void assertActivePids(List<Long> expected) throws Exception;
        abstract void assertSamePids(List<Long> expected) throws Exception;
        abstract void assertStopped(List<Long> pids) throws Exception;
        abstract void assertSessionsEmpty() throws Exception;

        final Path work() { return work; }
        final Path holdEntered() { return holdEntered; }
        final Path releaseFile() { return release; }
        java.util.Optional<Path> pidFile() { return java.util.Optional.empty(); }
        final void awaitHold() throws Exception { awaitFile(holdEntered, "调用没有进入 hold"); }
        final void release() throws Exception { Files.writeString(release, "release"); }
    }

    private static final class JavaFixture extends Fixture {
        private JavaFixture(Path work) { super(work); }

        @Override
        com.sstlfsj.fibra.engine.DeploymentArtifact artifact(String variant, String version) throws Exception {
            var root = Files.createDirectories(work().resolve("java-" + variant));
            Files.writeString(root.resolve("plugin.properties"), "formatVersion=1\nruntime=java\npayload=plugin.jar\n");
            var source = Path.of(System.getProperty("plugin.jar"));
            try (var input = new JarFile(source.toFile());
                 var output = new JarOutputStream(Files.newOutputStream(root.resolve("plugin.jar")))) {
                var entries = input.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.getName().equals("META-INF/fibra/plugin.yaml")) continue;
                    output.putNextEntry(new JarEntry(entry.getName()));
                    if (!entry.isDirectory()) input.getInputStream(entry).transferTo(output);
                    output.closeEntry();
                }
                output.putNextEntry(new JarEntry("META-INF/fibra/plugin.yaml"));
                output.write(("id: lifecycle\nversion: " + version + "\nentrypoint: "
                    + "verification.distribution.plugin.LifecycleEntrypoint\nrequires: []\n")
                    .getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
                output.putNextEntry(new JarEntry("lifecycle-variant.txt"));
                output.write(variant.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
            return new PluginArtifactProbe(List.of(new JavaPluginRuntimeAdapter())).probe(root).block(TIMEOUT);
        }

        @Override NodePluginRuntimeAdapter nodeAdapter() {
            return new NodePluginRuntimeAdapter(name -> java.util.Optional.empty(),
                NodeRuntimeOptions.defaults(Path.of("node"), work().resolve("unused-node-sessions")));
        }
        @Override void assertInvocation(Map<String, Object> fact) { }
        @Override WeakReference<ClassLoader> loader(FibraEngine engine) {
            var descriptor = engine.published().current().contributions().entries().stream()
                .map(entry -> entry.descriptor()).filter(value -> value.getClass().getName()
                    .equals("verification.distribution.plugin.LifecycleEntrypoint$LifecycleDescriptor"))
                .findFirst().orElseThrow();
            return new WeakReference<>(descriptor.getClass().getClassLoader());
        }
        @Override void assertActiveLoader(WeakReference<ClassLoader> loader) {
            assertNotNull(loader.get(), "排空期间旧 Java loader 必须保持存活");
        }
        @Override void assertSame(WeakReference<ClassLoader> expected, FibraEngine engine) {
            assertNotNull(expected.get(), "活动 Java loader 应保持存活");
            org.junit.jupiter.api.Assertions.assertSame(expected.get(), loader(engine).get());
        }
        @Override void assertCollected(WeakReference<ClassLoader> reference) throws Exception {
            for (var attempt = 0; attempt < 60; attempt++) {
                System.gc();
                if (reference.get() == null) return;
                Thread.sleep(25);
            }
            assertNull(reference.get(), "live Engine 保留了退役 Java fixture loader");
        }
        @Override List<Long> activePids() { return List.of(); }
        @Override List<Long> allPids() { return List.of(); }
        @Override void assertActivePids(List<Long> expected) { assertTrue(expected.isEmpty()); }
        @Override void assertSamePids(List<Long> expected) { assertTrue(expected.isEmpty()); }
        @Override void assertStopped(List<Long> pids) { }
        @Override void assertSessionsEmpty() { }
    }

    private static final class NodeFixture extends Fixture {
        private final Path pidFile;
        private final Path sessions;

        private NodeFixture(Path work) {
            super(work);
            pidFile = work.resolve("node-pids");
            sessions = work.resolve("node-sessions");
        }

        @Override java.util.Optional<Path> pidFile() { return java.util.Optional.of(pidFile); }

        @Override
        com.sstlfsj.fibra.engine.DeploymentArtifact artifact(String variant, String version) throws Exception {
            var root = Files.createDirectories(work().resolve("node-" + variant));
            var payload = Files.createDirectories(root.resolve("payload"));
            Files.writeString(root.resolve("plugin.properties"), "formatVersion=1\nruntime=node\npayload=payload\n");
            Files.writeString(payload.resolve("fibra-plugin.yaml"), """
                id: lifecycle
                version: %s
                protocol: 1
                entrypoint: index.mjs
                contributions:
                  - name: lifecycle
                    kind: fibra.tool
                    schemaVersion: 2
                    method: tool.lifecycle
                    descriptor:
                      displayName: Lifecycle fixture
                      description: External lifecycle evidence
                      inputSchema: { type: object }
                      outputSchema: { type: object }
                """.formatted(version));
            Files.writeString(payload.resolve("index.mjs"), """
                import fs from 'node:fs';
                import readline from 'node:readline';
                let config = {};
                const packagedVariant = %s;
                const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
                readline.createInterface({input: process.stdin}).on('line', line => {
                  const message = JSON.parse(line); const {id, method} = message;
                  if (method === 'fibra.handshake') reply(id, {protocol:1});
                  else if (method === 'fibra.ping') reply(id, {ok:true});
                  else if (method === 'fibra.start') { config = message.params.config; fs.appendFileSync(config.pidFile, process.pid + ':' + process.ppid + '\\n'); reply(id, {ok:true}); }
                  else if (method === 'fibra.stop') reply(id, {ok:true});
                  else if (method === 'tool.lifecycle') {
                    if (message.params.input.arguments.operation === 'hold') {
                      fs.writeFileSync(config.holdEntered, 'entered');
                      const waiting = setInterval(() => { if (fs.existsSync(config.release)) { clearInterval(waiting); reply(id, {content:[{type:'text', text:'released'}], structuredContent:{variant:config.variant, config}}); } }, 10);
                    } else reply(id, {content:[{type:'text', text:'facts'}], structuredContent:{variant:packagedVariant, config, pid:process.pid, ppid:process.ppid}});
                  }
                });
                """.formatted(LiteralValue.of(variant).canonicalJson()));
            return new PluginArtifactProbe(List.of(nodeAdapter())).probe(root).block(TIMEOUT);
        }

        @Override NodePluginRuntimeAdapter nodeAdapter() {
            return new NodePluginRuntimeAdapter(name -> "fibra.tool".equals(name)
                ? java.util.Optional.of(ToolContributions.KIND) : java.util.Optional.empty(),
                NodeRuntimeOptions.defaults(executable("node"), sessions));
        }
        @Override void assertInvocation(Map<String, Object> fact) {
            assertTrue(((Number) fact.get("pid")).longValue() > 0);
            assertTrue(((Number) fact.get("ppid")).longValue() > 0);
        }
        @Override WeakReference<ClassLoader> loader(FibraEngine engine) { return new WeakReference<>(null); }
        @Override void assertActiveLoader(WeakReference<ClassLoader> loader) { }
        @Override void assertSame(WeakReference<ClassLoader> expected, FibraEngine engine) { }
        @Override void assertCollected(WeakReference<ClassLoader> reference) { }
        @Override List<Long> activePids() throws Exception {
            return allPids().stream().filter(NodeFixture::alive).toList();
        }
        @Override List<Long> allPids() throws Exception {
            return java.util.stream.Stream.concat(recorded(0).stream(), recorded(1).stream())
                .distinct().toList();
        }
        @Override void assertActivePids(List<Long> expected) throws Exception { assertEquals(expected, activePids()); }
        @Override void assertSamePids(List<Long> expected) throws Exception { assertActivePids(expected); }
        @Override void assertStopped(List<Long> pids) throws Exception {
            for (var pid : pids) awaitStopped(pid);
        }
        @Override void assertSessionsEmpty() throws Exception {
            assertTrue(Files.isDirectory(sessions));
            try (var paths = Files.list(sessions)) { assertEquals(0, paths.count()); }
        }

        private static boolean alive(long pid) {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        }
        private List<Long> recorded(int column) throws Exception {
            if (Files.notExists(pidFile)) return List.of();
            return Files.readAllLines(pidFile).stream().map(line -> line.split(":"))
                .map(parts -> Long.parseLong(parts[column])).distinct().toList();
        }
        private static void awaitStopped(long pid) throws Exception {
            var deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (alive(pid)) {
                if (System.nanoTime() >= deadline) throw new AssertionError("Node 进程仍在运行: " + pid);
                Thread.sleep(10);
            }
        }
    }

    private static void awaitFile(Path file, String message) throws Exception {
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (Files.notExists(file)) {
            if (System.nanoTime() >= deadline) throw new AssertionError(message);
            Thread.sleep(10);
        }
    }

    private static Path executable(String name) {
        for (var directory : System.getenv().getOrDefault("PATH", "").split(":")) {
            var candidate = Path.of(directory, name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
        }
        throw new IllegalStateException("required executable is unavailable: " + name);
    }
}
