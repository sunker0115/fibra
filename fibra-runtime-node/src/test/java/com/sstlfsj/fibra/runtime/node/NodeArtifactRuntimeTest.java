package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.PluginPackage;
import com.sstlfsj.fibra.engine.DeploymentTargetCompiler;
import com.sstlfsj.fibra.engine.PreparedArtifact;
import com.sstlfsj.fibra.engine.ResolvedFacetDependency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeArtifactRuntimeTest {
    @Test
    void preparesOnlyTheManagedFacetAndResolvedWiringWithoutStartingNode(
        @TempDir Path work) throws Exception {
        var marker = work.resolve("sidecar-started");
        var facet = facet(work, "node-one", """
            protocol: 1
            entrypoint: index.mjs
            contributions:
              - name: say
                kind: echo
                schemaVersion: 1
                method: echo
                descriptor: { title: Echo }
            """, "import { writeFileSync } from 'node:fs'; writeFileSync('%s', 'started');"
            .formatted(marker));
        var dependency = new ResolvedFacetDependency(facet.pluginId(),
            facet.packageRevision(), facet.facet().facetId(), facet.artifactId());
        var runtime = new NodeArtifactRuntime();

        runtime.probe(facet.facet()).block();
        var inspection = runtime.inspect(facet).block();
        var update = runtime.createUpdate(List.of(compiled(facet, List.of(dependency))));

        update.prepareAsync().block();
        var prepared = (NodePreparedArtifact) update.preparedArtifacts()
            .get(facet.artifactId());
        assertEquals(facet, prepared.facet());
        assertEquals(List.of(dependency), prepared.dependencies());
        var descriptor = prepared.descriptor();
        assertEquals("index.mjs", descriptor.entrypoint());
        assertEquals(List.of("say"), descriptor.contributions()
            .stream().map(NodeEndpointManifest::name).toList());
        assertEquals("index.mjs", inspection.metadata().get("entrypoint"));
        assertFalse(Files.exists(marker), "artifact preparation must not start a sidecar");

        update.adopt();
        update.closeAsync().block();
        assertEquals(1, runtime.snapshot().resources().size());
        assertFalse(Files.exists(marker));
        runtime.closeAsync().block();
        assertTrue(runtime.snapshot().resources().isEmpty());
    }

    @Test
    void borrowsUnchangedPreparedFacetAndReleasesOnlyRetiredArtifacts(@TempDir Path work)
        throws Exception {
        var first = facet(work, "first", descriptor(), "export {};");
        var second = facet(work, "second", descriptor(), "export {};");
        var runtime = new NodeArtifactRuntime();

        var initial = runtime.createUpdate(List.of(compiled(first, List.of()),
            compiled(second, List.of())));
        initial.prepareAsync().block();
        initial.adopt();
        initial.closeAsync().block();
        var firstPrepared = runtime.snapshot().resources().getFirst().facet();

        var replacement = runtime.createUpdate(List.of(compiled(first, List.of())));
        replacement.prepareAsync().block();
        PreparedArtifact prepared = replacement.preparedArtifacts().get(first.artifactId());
        assertSame(firstPrepared, prepared.facet());
        replacement.adopt();
        replacement.closeAsync().block();

        assertEquals(List.of(first.artifactId()), runtime.snapshot().resources().stream()
            .map(value -> value.facet().artifactId()).toList());
    }

    @Test
    void readerRejectsLegacyLogicalIdentityAndUncoveredDescriptorFiles(@TempDir Path work)
        throws Exception {
        var legacy = facet(work, "legacy", """
            id: legacy
            version: 1.0.0
            protocol: 1
            entrypoint: index.mjs
            contributions: []
            """, "export {};");
        var runtime = new NodeArtifactRuntime();

        var failure = assertThrows(NodeRuntimeException.class,
            () -> runtime.inspect(legacy).block());
        assertEquals(legacy.artifactId(), failure.artifactId());
        assertTrue(failure.getMessage().contains("unknown descriptor fields"));
    }

    @Test
    void acceptsAnEmptyContributionDeclarationForStaticInspectionAndPreparation(
        @TempDir Path work) throws Exception {
        var facet = facet(work, "no-contributions", """
            protocol: 1
            entrypoint: index.mjs
            contributions: []
            """, "export {};");
        var runtime = new NodeArtifactRuntime();

        var inspection = runtime.inspect(facet).block();
        var update = runtime.createUpdate(List.of(compiled(facet, List.of())));
        update.prepareAsync().block();

        assertEquals(List.of(), inspection.metadata().get("contributions"));
        var prepared = (NodePreparedArtifact) update.preparedArtifacts()
            .get(facet.artifactId());
        assertTrue(prepared.descriptor().contributions().isEmpty());
        update.closeAsync().block();
    }

    @Test
    void failedPreparationDoesNotReplaceActiveFacetAndCanBeReleased(@TempDir Path work)
        throws Exception {
        var stable = facet(work, "stable", descriptor(), "export {};");
        var invalid = facet(work, "invalid", """
            protocol: 2
            entrypoint: index.mjs
            contributions:
              - name: say
                kind: echo
                schemaVersion: 1
                method: echo
                descriptor: { title: Echo }
            """, "export {};");
        var runtime = new NodeArtifactRuntime();
        var initial = runtime.createUpdate(List.of(compiled(stable, List.of())));
        initial.prepareAsync().block();
        initial.adopt();
        initial.closeAsync().block();

        var failed = runtime.createUpdate(List.of(compiled(invalid, List.of())));
        assertThrows(NodeRuntimeException.class, () -> failed.prepareAsync().block());
        assertEquals(List.of(stable.artifactId()), runtime.snapshot().resources().stream()
            .map(value -> value.facet().artifactId()).toList());
        failed.closeAsync().block();

        var next = runtime.createUpdate(List.of());
        next.prepareAsync().block();
        next.closeAsync().block();
    }

    @Test
    void updateIsRegisteredOnCreationAndTerminalCloseIsCached(@TempDir Path work)
        throws Exception {
        var facet = facet(work, "node-one", descriptor(), "export {};");
        var runtime = new NodeArtifactRuntime();
        var update = runtime.createUpdate(List.of(compiled(facet, List.of())));

        assertThrows(IllegalStateException.class,
            () -> runtime.createUpdate(List.of(compiled(facet, List.of()))));
        assertThrows(IllegalStateException.class, update::preparedArtifacts);
        update.prepareAsync().block();
        var first = update.closeAsync();
        var second = update.closeAsync();
        assertSame(first, second);
        first.block();

        var next = runtime.createUpdate(List.of());
        next.prepareAsync().block();
        next.closeAsync().block();
    }

    @Test
    void preparedDescriptorDeeplyFreezesNestedYamlMapsAndLists(@TempDir Path work)
        throws Exception {
        var facet = facet(work, "immutable", """
            protocol: 1
            entrypoint: index.mjs
            contributions:
              - name: say
                kind: echo
                schemaVersion: 1
                method: echo
                descriptor:
                  nested:
                    labels: [before]
            """, "export {};");
        var runtime = new NodeArtifactRuntime();
        var update = runtime.createUpdate(List.of(compiled(facet, List.of())));
        update.prepareAsync().block();

        var prepared = (NodePreparedArtifact) update.preparedArtifacts()
            .get(facet.artifactId());
        @SuppressWarnings("unchecked")
        var descriptor = (Map<Object, Object>) prepared.descriptor().contributions()
            .getFirst().descriptor();
        @SuppressWarnings("unchecked")
        var nested = (Map<Object, Object>) descriptor.get("nested");
        @SuppressWarnings("unchecked")
        var labels = (List<Object>) nested.get("labels");

        assertThrows(UnsupportedOperationException.class,
            () -> descriptor.put("extra", "mutated"));
        assertThrows(UnsupportedOperationException.class,
            () -> nested.put("other", "mutated"));
        assertThrows(UnsupportedOperationException.class, () -> labels.add("mutated"));
        assertEquals(List.of("before"), labels);
        assertEquals(Map.of("labels", List.of("before")), nested);
        update.closeAsync().block();
    }

    private static DeploymentTargetCompiler.CompiledFacet compiled(ManagedFacet facet,
                                                                     List<ResolvedFacetDependency> dependencies) {
        try {
            var constructor = DeploymentTargetCompiler.CompiledFacet.class
                .getDeclaredConstructor(ManagedFacet.class, List.class);
            constructor.setAccessible(true);
            return constructor.newInstance(facet, dependencies);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static ManagedFacet facet(Path work, String id, String descriptor,
                                      String entrypoint) throws Exception {
        var root = work.resolve(id);
        var payload = root.resolve("node");
        Files.createDirectories(payload);
        Files.writeString(payload.resolve("index.mjs"), entrypoint);
        Files.writeString(payload.resolve("fibra-plugin.yaml"), descriptor);
        Files.writeString(root.resolve("fibra-package.yaml"), """
            format: 1
            id: %s
            version: 1.0.0
            facets:
              - id: main
                role: host
                runtime: node
                target: host
                payload: node
                dependencies: []
                capabilities: []
            """.formatted(id));
        var source = PluginPackage.read(root);
        return new ManagedFacet(new ArtifactId(id + "-artifact"), source.pluginId(),
            source.packageDigest(), source.facets().getFirst());
    }

    private static String descriptor() {
        return """
            protocol: 1
            entrypoint: index.mjs
            contributions:
              - name: say
                kind: echo
                schemaVersion: 1
                method: echo
                descriptor: { title: Echo }
            """;
    }
}
