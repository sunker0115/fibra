package com.sstlfsj.fibra.plugins.acceptance;

import com.sstlfsj.fibra.artifact.*;
import com.sstlfsj.fibra.config.DefinitionConfigDocument;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.deployment.*;
import com.sstlfsj.fibra.registry.*;
import com.sstlfsj.fibra.runtime.java.JavaRuntimeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AuthorConfigurationQualificationTest {
    private static final String SCHEMA = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"null\"}";

    @Test
    void exactSetsRejectMissingOrphanMissingAndExtraEmptyFacetsAndInactiveBadSchemas() {
        var catalog = new PackageDefinitionCatalog(1, new PluginId("sample"), "a".repeat(64), List.of(
            new FacetDefinitionNames(new FacetId("main"), new RuntimeId("java"),
                new ExecutionTarget("host"), List.of("sample")),
            new FacetDefinitionNames(new FacetId("empty"), new RuntimeId("java"),
                new ExecutionTarget("host"), List.of())));
        var valid = "{\"main\":{\"sample\":" + SCHEMA + "},\"empty\":{}}";
        AuthorConfigurationQualification.check(catalog, document(valid));
        for (var facets : List.of("{\"main\":{},\"empty\":{}}",
            valid.replace("\"sample\":", "\"orphan\":"),
            "{\"main\":{\"sample\":" + SCHEMA + "}}",
            valid.substring(0, valid.length() - 1) + ",\"extra\":{}}",
            valid.replace("\"type\":\"null\"", "\"type\":\"not-a-type\""))) {
            assertThrows(IllegalStateException.class, () ->
                AuthorConfigurationQualification.check(catalog, document(facets)));
        }
        var empty = new PackageDefinitionCatalog(1, new PluginId("sample"), "a".repeat(64),
            List.of(catalog.facets().stream().filter(f -> f.facetId().value().equals("empty")).findFirst().orElseThrow()));
        AuthorConfigurationQualification.check(empty, document("{\"empty\":{}}"));
    }

    @Test
    void resourceReadAndCloseOutcomesUseTheSameBoundedOwner(@TempDir Path work) {
        for (var fatal : List.of(false, true)) {
        for (var failRead : List.of(false, true)) {
            for (var failClose : List.of(false, true)) {
                var root = work.resolve(fatal + "-" + failRead + "-" + failClose);
                var packages = new PluginPackageStore(root.resolve("store"));
                var targets = DeploymentTargetStore.inMemory();
                var engine = FibraEngine.builder(packages, targets)
                    .runtimeProvider(new JavaRuntimeProvider(List.of()))
                    .hostTerminationPort(ignored -> { }).build();
                engine.startAsync().block(PluginAcceptanceHarness.TIMEOUT);
                var registry = new PluginRegistry(engine, new InMemoryPluginAuditRepository());
                var request = PluginAcceptanceHarness.installRequest(root.resolve("sources"),
                    PluginAcceptanceHarness.stagedJar("fibra-fs"));
                var snapshot = registry.install(new PluginInstallRequest(request.source(), false))
                    .block(PluginAcceptanceHarness.TIMEOUT);
                var selection = snapshot.selections().values().iterator().next();
                var reads = new AtomicInteger();
                var closes = new AtomicInteger();
                var terminated = new AtomicInteger();
                var readError = new IllegalStateException("read failure");
                var closeError = new IllegalStateException("close failure");
                var fatalRead = new InternalError("fatal read");
                var fatalClose = new InternalError("fatal close");
                var notificationError = new IllegalStateException("termination notification failed");
                var handle = new PluginConfigurationResource() {
                    public byte[] read() {
                        reads.incrementAndGet();
                        if (failRead) { if (fatal) throw fatalRead; else throw readError; }
                        return bytes("{\"main\":{}}");
                    }
                    public void close() {
                        closes.incrementAndGet();
                        if (failClose) { if (fatal) throw fatalClose; else throw closeError; }
                    }
                };
                var owner = new AuthorConfigurationQualification(engine, packages, targets,
                    ignored -> handle, failed -> {
                        FailedOwners.RETAINED.add(failed);
                        terminated.incrementAndGet();
                        throw notificationError;
                    });
                Class<? extends Throwable> expectedFailure = fatal ? InternalError.class : IllegalStateException.class;
                if (failRead || failClose) assertThrows(expectedFailure, () ->
                    owner.qualify(selection, snapshot.target().orElseThrow().targetRevision()));
                else owner.qualify(selection, snapshot.target().orElseThrow().targetRevision());
                assertEquals(1, reads.get());
                assertEquals(1, closes.get());
                assertSame(failRead ? (fatal ? fatalRead : readError) : null, owner.readFailure);
                if (failClose) {
                    assertSame(handle, owner.pending);
                    assertSame(fatal ? fatalClose : closeError, owner.closeFailure);
                    assertSame(notificationError, owner.terminationFailure);
                    assertSame(owner, FailedOwners.RETAINED.getLast());
                    assertSame(engine, owner.engine);
                    assertSame(packages, owner.packages);
                    assertSame(targets, owner.targets);
                    assertEquals(1, terminated.get());
                    assertThrows(IllegalStateException.class, owner::close);
                    assertEquals(1, closes.get());
                    assertFalse(owner.closed);
                    assertTrue(packages.find(selection.pluginId(), selection.packageRevision()).isPresent());
                    assertTrue(engine.describeDefinitions(selection.pluginId(), snapshot.target().orElseThrow()
                        .targetRevision()).block(PluginAcceptanceHarness.TIMEOUT) != null);
                    // The test seam owns no physical stream. Retain the failed owner until the test host ends.
                } else {
                    assertNull(owner.pending);
                    assertEquals(0, terminated.get());
                    owner.close();
                    owner.close();
                    assertTrue(owner.closed);
                }
            }
        }
        }
    }

    private static DefinitionConfigDocument document(String facets) {
        return DefinitionConfigDocument.parse(bytes(facets));
    }

    private static byte[] bytes(String facets) {
        return ("{\"format\":1,\"profile\":\"fibra-config-1\",\"facets\":" + facets + "}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static final class FailedOwners {
        static final List<AuthorConfigurationQualification> RETAINED = new java.util.ArrayList<>();
    }
}
