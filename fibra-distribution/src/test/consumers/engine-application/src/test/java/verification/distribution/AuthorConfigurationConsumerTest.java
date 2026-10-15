package verification.distribution;

import com.sstlfsj.fibra.artifact.PluginConfigurationResource;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.deployment.DeploymentTargetStore;
import com.sstlfsj.fibra.plugins.acceptance.AuthorConfigurationQualification;
import com.sstlfsj.fibra.registry.InMemoryPluginAuditRepository;
import com.sstlfsj.fibra.registry.PluginInstallRequest;
import com.sstlfsj.fibra.registry.PluginRegistry;
import com.sstlfsj.fibra.runtime.java.JavaRuntimeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AuthorConfigurationConsumerTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final List<AuthorConfigurationQualification> FAILED_OWNERS = new ArrayList<>();

    @Test
    void qualifiesAllPublishedPackagesIncludingEmptyFacetsWhileDisabled(@TempDir Path work) throws Exception {
        var packages = new PluginPackageStore(work.resolve("store"));
        var targets = DeploymentTargetStore.inMemory();
        var engine = start(packages, targets);
        try (var qa = new AuthorConfigurationQualification(engine, packages, targets, FAILED_OWNERS::add)) {
            var registry = new PluginRegistry(engine, new InMemoryPluginAuditRepository());
            for (var plugin : EngineConsumerTest.FORMAL_ARTIFACTS) install(registry, work, plugin);
            var snapshot = registry.snapshot();
            assertEquals(12, snapshot.selections().size());
            assertTrue(snapshot.desiredGraph().plugins().isEmpty());
            assertTrue(snapshot.observed().isEmpty());
            var revision = snapshot.target().orElseThrow().targetRevision();
            var emptyPackages = Set.of("fibra-fs", "fibra-subprocess", "fibra-shell", "fibra-storage");
            for (var selection : snapshot.selections().values()) {
                assertFalse(selection.enabled());
                var document = qa.qualify(selection, revision);
                if (emptyPackages.contains(selection.pluginId().value())) {
                    assertEquals(Map.of("main", Map.of()), document.facets());
                }
            }
        }
    }

    @Test
    void rejectsInstalledInactiveBadMissingAndOrphanSchemas(@TempDir Path work) throws Exception {
        var schema = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"null\"}";
        var definitions = List.of(
            "{\"fibra-tool-shell\":" + schema.replace("\"null\"", "\"invalid-type\"") + "}",
            "{}",
            "{\"fibra-tool-shell\":" + schema + ",\"orphan\":" + schema + "}");
        for (var index = 0; index < definitions.size(); index++) {
            var root = work.resolve("invalid-" + index);
            var request = packageRequest(root, "fibra-tool-shell");
            Files.writeString(request.source().resolve("fibra-config.json"),
                "{\"format\":1,\"profile\":\"fibra-config-1\",\"facets\":{\"main\":" + definitions.get(index) + "}}");
            var packages = new PluginPackageStore(root.resolve("store"));
            var targets = DeploymentTargetStore.inMemory();
            var engine = start(packages, targets);
            try (var qa = new AuthorConfigurationQualification(engine, packages, targets, FAILED_OWNERS::add)) {
                var registry = new PluginRegistry(engine, new InMemoryPluginAuditRepository());
                var snapshot = registry.install(request).block(TIMEOUT);
                var selection = snapshot.selections().values().iterator().next();
                assertFalse(selection.enabled());
                assertTrue(snapshot.observed().isEmpty());
                assertTrue(packages.find(selection.pluginId(), selection.packageRevision()).isPresent());
                var revision = snapshot.target().orElseThrow().targetRevision();
                assertEquals(List.of("fibra-tool-shell"), engine.describeDefinitions(selection.pluginId(), revision)
                    .block(TIMEOUT).facets().getFirst().definitionIds());
                assertThrows(IllegalStateException.class, () -> qa.qualify(selection, revision));
            }
        }
    }

    @Test
    void cleanupFailureRetainsTheSameExternalQaOwnerAndDoesNotCloseTheEngine(@TempDir Path work) throws Exception {
        var packages = new PluginPackageStore(work.resolve("store"));
        var targets = DeploymentTargetStore.inMemory();
        var engine = start(packages, targets);
        var registry = new PluginRegistry(engine, new InMemoryPluginAuditRepository());
        install(registry, work, "fibra-fs");
        var snapshot = registry.snapshot();
        var selection = snapshot.selections().values().iterator().next();
        var revision = snapshot.target().orElseThrow().targetRevision();
        var reads = new AtomicInteger();
        var closes = new AtomicInteger();
        var terminated = new AtomicInteger();
        var qa = new AuthorConfigurationQualification(engine, packages, targets, record -> {
            var delegate = record.configurationResource();
            return new PluginConfigurationResource() {
                @Override public byte[] read() { reads.incrementAndGet(); return delegate.read(); }
                @Override public void close() {
                    closes.incrementAndGet();
                    throw new IllegalStateException("injected cleanup failure");
                }
            };
        }, failed -> {
            FAILED_OWNERS.add(failed);
            terminated.incrementAndGet();
        });
        assertThrows(IllegalStateException.class, () -> qa.qualify(selection, revision));
        assertSame(qa, FAILED_OWNERS.getLast());
        assertEquals(1, reads.get());
        assertEquals(1, closes.get());
        assertEquals(1, terminated.get());
        assertThrows(IllegalStateException.class, qa::close);
        assertEquals(1, closes.get());
        assertTrue(packages.find(selection.pluginId(), selection.packageRevision()).isPresent());
        assertEquals(selection.packageRevision(), engine.describeDefinitions(selection.pluginId(), revision)
            .block(TIMEOUT).packageRevision());
    }

    private static FibraEngine start(PluginPackageStore packages, DeploymentTargetStore targets) {
        var engine = FibraEngine.builder(packages, targets).runtimeProvider(new JavaRuntimeProvider(List.of()))
            .hostTerminationPort(ignored -> { }).build();
        engine.startAsync().block(TIMEOUT);
        return engine;
    }

    private static PluginInstallRequest packageRequest(Path work, String plugin) throws Exception {
        var request = EngineConsumerTest.installRequest(work.resolve("sources"), plugin,
            Path.of(System.getProperty("fibra.pluginArtifacts"), plugin + ".jar"));
        return new PluginInstallRequest(request.source(), false);
    }

    private static void install(PluginRegistry registry, Path work, String plugin) throws Exception {
        registry.install(packageRequest(work, plugin)).block(TIMEOUT);
    }
}
