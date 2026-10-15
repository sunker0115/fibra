package verification.hostapi;

import com.sstlfsj.fibra.artifact.PluginPackageRecord;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.bridge.ContributionHandle;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionUnavailableException;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.engine.ApplyDeployment;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.HostServiceRegistry;
import com.sstlfsj.fibra.engine.EngineChangeException;
import com.sstlfsj.fibra.engine.observation.TargetSaveState;
import com.sstlfsj.fibra.engine.deployment.DeploymentTargetStore;
import com.sstlfsj.fibra.engine.deployment.PluginSelection;
import com.sstlfsj.fibra.engine.publication.PublishedView;
import com.sstlfsj.fibra.engine.publication.PublishedRevisionConflictException;
import com.sstlfsj.fibra.runtime.java.JavaApiContract;
import com.sstlfsj.fibra.runtime.java.JavaRuntimeProvider;
import com.sstlfsj.fibra.runtime.java.JavaRuntimeException;
import com.sstlfsj.fibra.runtime.java.JavaRuntimePhase;
import fixture.hostapi.DocumentApi;
import fixture.hostapi.DocumentProbe;
import fixture.hostapi.PreparationProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import javax.tools.ToolProvider;
import java.lang.ref.WeakReference;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 仅消费公开 API，可原样复制到仓外 consumer；不取得 Engine 私有 driver。 */
class JavaHostApiEngineTest {
    private static final Duration LIMIT = Duration.ofSeconds(8);
    private static final String IMPORTS = "hostApis: [{id: fixture.document, version: '1'}, {id: fixture.probe, version: '1'}]\n";
    @TempDir Path work;

    @Test
    @Timeout(40)
    void typedCallsShareIdentityWhileReplacementAndDisableRetireOnlyTheirProvider() throws Exception {
        var probe = new Probe();
        var packages = new PluginPackageStore(work.resolve("packages"));
        var a = install(packages, plugin("a", "1", IMPORTS, false));
        var b = install(packages, plugin("b", "1", IMPORTS, false));
        var a2 = install(packages, plugin("a", "2", IMPORTS, false));
        var services = new HostServiceRegistry();
        services.register(DocumentProbe.KEY, probe);
        try (var engine = FibraEngine.builder(packages, DeploymentTargetStore.inMemory())
            .hostServices(services).runtimeProvider(provider())
            .hostTerminationPort(ignored -> fail("unexpected Host termination"))
            .lifecycleTimeout(LIMIT).build()) {
            engine.startAsync().block(LIMIT);
            var first = deploy(engine, 0, List.of(a, b));
            assertEquals(2, first.contributions().entries().size());
            assertEquals("a:one", invoke(engine, first, "a", "one").block(LIMIT).text());
            assertEquals("b:two", invoke(engine, first, "b", "two").block(LIMIT).text());
            var aLoader = probe.loaders.get("a").get();
            var bLoader = probe.loaders.get("b").get();
            assertNotNull(aLoader);
            assertNotSame(aLoader, bLoader);
            assertSame(DocumentApi.class, aLoader.loadClass(DocumentApi.class.getName()));
            assertSame(DocumentApi.Request.class, bLoader.loadClass(DocumentApi.Request.class.getName()));
            assertSame(DocumentApi.KIND, aLoader.loadClass(DocumentApi.class.getName()).getField("KIND").get(null));
            assertNotSame(aLoader.loadClass("example.provider.PrivateLibrary"),
                bLoader.loadClass("example.provider.PrivateLibrary"));
            assertThrows(ClassNotFoundException.class, () -> aLoader.loadClass("fixture.hostapi.HostBusiness"));
            assertNotNull(aLoader.getResource("example/provider/Entry.class"));
            var oldRegistration = registration(first, "a");

            var replaced = deploy(engine, 1, List.of(a2, b));
            assertSame(bLoader, probe.loaders.get("b").get());
            assertNull(aLoader.getResource("example/provider/Entry.class"), "retired URL loader is physically closed");
            assertNotSame(aLoader, probe.loaders.get("a").get());
            assertEquals("a:new", invoke(engine, replaced, "a", "new").block(LIMIT).text());
            assertThrows(PublishedRevisionConflictException.class, () -> invoke(engine, first, "a", "stale-view").block(LIMIT));
            assertThrows(ContributionUnavailableException.class, () -> engine.published().invoke(
                new ContributionHandle(replaced.viewRevision(), oldRegistration,
                    new ContributionId("a", "complete")),
                DocumentApi.KIND, new DocumentApi.Request("stale-registration")).block(LIMIT));

            var removed = deploy(engine, 2, List.of(b));
            assertEquals("b:still-live", invoke(engine, removed, "b", "still-live").block(LIMIT).text());
            assertSame(bLoader, probe.loaders.get("b").get());
            assertNotNull(bLoader.getResource("example/provider/Entry.class"));
            assertEquals(1, removed.contributions().entries().size());
            assertSame(DocumentApi.KIND.descriptorType(), DocumentApi.Descriptor.class);
            engine.closeAsync().block(LIMIT);
            engine.closeAsync().block(LIMIT);
            assertNull(bLoader.getResource("example/provider/Entry.class"));
            assertEquals(2, probe.stops.get("a"));
            assertEquals(1, probe.stops.get("b"));
        }
    }

    @Test
    @Timeout(30)
    void disablingWaitsForAnActuallyAdmittedCallBeforeClosingItsLoader() throws Exception {
        var probe = new Probe();
        var packages = new PluginPackageStore(work.resolve("packages"));
        var a = install(packages, plugin("a", "1", IMPORTS, false));
        var services = new HostServiceRegistry();
        services.register(DocumentProbe.KEY, probe);
        try (var engine = FibraEngine.builder(packages, DeploymentTargetStore.inMemory())
            .hostServices(services).runtimeProvider(provider()).lifecycleTimeout(LIMIT)
            .hostTerminationPort(ignored -> fail("unexpected Host termination")).build()) {
            engine.startAsync().block(LIMIT);
            var view = deploy(engine, 0, List.of(a));
            var loader = probe.loaders.get("a").get();
            var call = invoke(engine, view, "a", "hold").toFuture();
            assertTrue(probe.entered.await(5, TimeUnit.SECONDS), "handler actually accepted the call");
            var removal = engine.submit(command(1, List.of())).toFuture();
            // The published route disappears only after the command has sealed its admission.
            var limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!engine.published().current().contributions().entries().isEmpty() && System.nanoTime() < limit) {
                Thread.sleep(5);
            }
            assertTrue(engine.published().current().contributions().entries().isEmpty());
            assertFalse(removal.isDone(), "actual handler completion is still outstanding");
            assertFalse(call.isDone());
            assertFalse(probe.stops.containsKey("a"));
            assertNotNull(loader.getResource("example/provider/Entry.class"));
            assertThrows(PublishedRevisionConflictException.class, () -> invoke(engine, view, "a", "late").block(LIMIT));
            assertEquals(Sinks.EmitResult.OK, probe.release.tryEmitValue(new DocumentApi.Result("released")));
            assertEquals("released", call.get(5, TimeUnit.SECONDS).text());
            removal.get(5, TimeUnit.SECONDS);
            assertEquals(1, probe.stops.get("a"));
            assertNull(loader.getResource("example/provider/Entry.class"));
        }
    }

    @Test
    @Timeout(45)
    void missingVersionClosureAndShadowsFailBeforeActivationAndCanBeRepeated() throws Exception {
        for (var imports : List.of("", "hostApis: [{id: fixture.document, version: '2'}, {id: fixture.probe, version: '1'}]\n",
            "hostApis: [{id: fixture.probe, version: '1'}]\n", "hostApis: [{id: absent, version: '1'}]\n", IMPORTS)) {
            var root = Files.createTempDirectory(work, "rejected-");
            var probe = new Probe();
            var packages = new PluginPackageStore(root.resolve("packages"));
            var bad = install(packages, plugin("bad", "1", imports, imports.equals(IMPORTS)));
            var services = new HostServiceRegistry();
            services.register(DocumentProbe.KEY, probe);
            try (var engine = FibraEngine.builder(packages, DeploymentTargetStore.inMemory())
                .hostServices(services).runtimeProvider(provider())
                .hostTerminationPort(ignored -> fail("unexpected Host termination")).build()) {
                engine.startAsync().block(LIMIT);
                for (var round = 0; round < 2; round++) {
                    var rejected = assertThrows(EngineChangeException.class,
                        () -> engine.submit(command(0, List.of(bad))).block(LIMIT));
                    assertEquals(TargetSaveState.NOT_SAVED, rejected.targetSaveState());
                    assertEquals(JavaRuntimePhase.LOAD, assertInstanceOf(JavaRuntimeException.class,
                        rejected.getCause()).phase());
                    assertTrue(probe.loaders.isEmpty(), "no activation: " + imports);
                    assertTrue(engine.published().current().contributions().entries().isEmpty());
                }
                engine.closeAsync().block(LIMIT);
                engine.closeAsync().block(LIMIT);
            }
        }
    }

    @Test
    @Timeout(30)
    void retiredAndFailedPreparationLoadersAreCollectibleWhileAPeerAndHostApiRemainLive() throws Exception {
        var probe = new Probe();
        var packages = new PluginPackageStore(work.resolve("packages"));
        var a = install(packages, plugin("a", "1", IMPORTS, false));
        var b = install(packages, plugin("b", "1", IMPORTS, false));
        var bad = install(packages, plugin("bad", "1", IMPORTS, false, true));
        var services = new HostServiceRegistry();
        services.register(DocumentProbe.KEY, probe);
        try (var engine = FibraEngine.builder(packages, DeploymentTargetStore.inMemory())
            .hostServices(services).runtimeProvider(provider())
            .hostTerminationPort(ignored -> fail("unexpected Host termination")).build()) {
            engine.startAsync().block(LIMIT);
            PreparationProbe.LOADER.set(null);
            var scenario = new FutureTask<>(() -> {
                deploy(engine, 0, List.of(a, b));
                var retired = new ArrayList<WeakReference<ClassLoader>>();
                retired.add(probe.loaders.get("a"));
                deploy(engine, 1, List.of(b));
                for (var attempt = 0; attempt < 2; attempt++) {
                    PreparationProbe.LOADER.set(null);
                    var failure = assertThrows(EngineChangeException.class,
                        () -> deploy(engine, 2, List.of(b, bad)));
                    assertEquals(JavaRuntimePhase.LOAD,
                        assertInstanceOf(JavaRuntimeException.class, failure.getCause()).phase());
                    assertEquals(TargetSaveState.NOT_SAVED, failure.targetSaveState());
                    var materialized = PreparationProbe.LOADER.get();
                    assertNotNull(materialized, "each failure actually materialized its own loader");
                    assertNotSame(retired.getLast(), materialized);
                    retired.add(materialized);
                }
                assertFalse(probe.loaders.containsKey("bad"));
                return List.copyOf(retired);
            });
            var thread = Thread.ofPlatform().name("host-api-reclamation-proof").start(scenario);
            var retired = scenario.get(10, TimeUnit.SECONDS);
            thread.join(5000);
            assertFalse(thread.isAlive());
            assertNotNull(retired.get(1));
            for (var attempt = 0; attempt < 60 && retired.stream().anyMatch(ref -> ref.get() != null); attempt++) {
                System.gc();
                Thread.sleep(25);
            }
            assertTrue(retired.stream().allMatch(ref -> ref.get() == null), "retired and failed loaders are released");
            assertNotNull(probe.loaders.get("b").get(), "live peer is the collection control");
            assertEquals("b:live", invoke(engine, engine.published().current(), "b", "live").block(LIMIT).text());
            Reference.reachabilityFence(engine);
        } finally {
            PreparationProbe.LOADER.set(null);
        }
    }

    private static JavaRuntimeProvider provider() {
        return new JavaRuntimeProvider(List.of(), List.of(new JavaApiContract("fixture.document", "1",
            List.of(DocumentApi.class, DocumentApi.Descriptor.class, DocumentApi.Request.class, DocumentApi.Result.class)),
            new JavaApiContract("fixture.probe", "1", List.of(DocumentProbe.class)),
            new JavaApiContract("fixture.preparation", "1", List.of(PreparationProbe.class))));
    }

    private static Mono<DocumentApi.Result> invoke(FibraEngine engine, PublishedView view, String name, String text) {
        return engine.published().invoke(
            new ContributionHandle(view.viewRevision(), registration(view, name),
                new ContributionId(name, "complete")),
            DocumentApi.KIND, new DocumentApi.Request(text));
    }

    private static long registration(PublishedView view, String name) {
        var entry = view.contributions().entries().stream()
            .filter(value -> value.id().equals(new ContributionId(name, "complete"))).findFirst().orElseThrow();
        assertInstanceOf(DocumentApi.Descriptor.class, entry.descriptor());
        return entry.registrationIdentity();
    }

    private static PublishedView deploy(FibraEngine engine, long revision, List<PluginPackageRecord> records) {
        return engine.submit(command(revision, records)).block(LIMIT).view();
    }

    private static ApplyDeployment command(long revision, List<PluginPackageRecord> records) {
        var graph = new DesiredInputGraph(records.stream().map(record -> DesiredInputEntry.builder(
            record.pluginId().value(), new PluginDefinitionRef(record.pluginId().value(), "main", "document")).build()).toList());
        return ApplyDeployment.builder(graph).expectedRevision(revision).selections(records.stream()
            .map(record -> new PluginSelection(record.pluginId(), record.packageRevision(), true)).toList())
            .configContext(ConfigContextSnapshot.empty()).build();
    }

    private Path plugin(String name, String version, String imports, boolean shadow) throws Exception {
        return plugin(name, version, imports, shadow, false);
    }

    private Path plugin(String name, String version, String imports, boolean shadow, boolean failPreparation) throws Exception {
        var root = Files.createTempDirectory(work, "plugin-");
        var src = root.resolve("Entry.java");
        var classes = Files.createDirectories(root.resolve("classes"));
        Files.writeString(src, """
            package example.provider;
            import com.sstlfsj.fibra.*;
            import com.sstlfsj.fibra.bridge.*;
            import fixture.hostapi.*;
            import reactor.core.publisher.Mono;
            public final class Entry implements PluginEntrypoint<Void> {
                public PluginDefinition<Void> definition() {
                    %s
                    return PluginDefinition.builder("document", Void.class, () -> (context, config) -> {
                        var probe = context.services().require(DocumentProbe.KEY);
                        probe.started("%s", getClass().getClassLoader());
                        context.effects().add(() -> Mono.fromRunnable(() -> probe.stopped("%s")));
                        return context.services().require(ContributionServices.REGISTRAR).register(context,
                            DocumentApi.KIND, "complete", new DocumentApi.Descriptor("%s"),
                            (call, request) -> probe.invoke("%s", request)).then();
                    }).require(DocumentProbe.KEY).require(ContributionServices.REGISTRAR).build();
                }
            }
            final class PrivateLibrary { }
            """.formatted(failPreparation
                ? "if (true) { PreparationProbe.LOADER.set(new java.lang.ref.WeakReference<>(getClass().getClassLoader())); "
                    + "throw new IllegalStateException(\"prepare failure\"); }"
                : "", name, name, version, name));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21",
            "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), src.toString()));
        var pkg = Files.createDirectories(root.resolve("package"));
        Files.writeString(pkg.resolve("fibra-config.json"), """
            {"format":1,"profile":"fibra-config-1","facets":{"main":{"document":{
              "$schema":"https://json-schema.org/draft/2020-12/schema","type":"null"
            }}}}
            """);
        Files.writeString(pkg.resolve("fibra-package.yaml"), """
            format: 1
            id: %s
            version: '%s'
            facets:
              - id: main
                role: host
                runtime: java
                target: host
                payload: main.jar
                dependencies: []
                capabilities: []
            """.formatted(name, version));
        try (var jar = new JarOutputStream(Files.newOutputStream(pkg.resolve("main.jar")))) {
            var declaration = failPreparation ? imports.replace("}]", "}, {id: fixture.preparation, version: '1'}]") : imports;
            add(jar, "META-INF/fibra/plugin.yaml", ("entrypoint: example.provider.Entry\ndefinitionId: document\n"
                + declaration).getBytes(StandardCharsets.UTF_8));
            try (var files = Files.walk(classes)) {
                for (var path : files.filter(Files::isRegularFile).toList()) {
                    add(jar, classes.relativize(path).toString(), Files.readAllBytes(path));
                }
            }
            if (shadow) add(jar, DocumentApi.class.getName().replace('.', '/') + ".class", new byte[]{0});
        }
        return pkg;
    }

    private static void add(JarOutputStream jar, String name, byte[] bytes) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(bytes);
        jar.closeEntry();
    }

    private static PluginPackageRecord install(PluginPackageStore store, Path path) {
        try (var tx = store.prepareInstall(path)) { return tx.save(); }
    }

    private static final class Probe implements DocumentProbe {
        final Map<String, WeakReference<ClassLoader>> loaders = new ConcurrentHashMap<>();
        final Map<String, Integer> stops = new ConcurrentHashMap<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final Sinks.One<DocumentApi.Result> release = Sinks.one();
        @Override public void started(String name, ClassLoader loader) { loaders.put(name, new WeakReference<>(loader)); }
        @Override public void stopped(String name) { stops.merge(name, 1, Integer::sum); }
        @Override public Mono<DocumentApi.Result> invoke(String name, DocumentApi.Request request) {
            if (!request.text().equals("hold")) return Mono.just(new DocumentApi.Result(name + ":" + request.text()));
            entered.countDown();
            return release.asMono();
        }
    }
}
