package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ExecutionTarget;
import com.sstlfsj.fibra.artifact.FacetDependency;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.FacetRole;
import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.ManagedPluginPackage;
import com.sstlfsj.fibra.artifact.PluginFacet;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuiltInPluginPackageTest {
    private static final String DIGEST = "a".repeat(64);

    @Test
    void definitionReferenceAlwaysCarriesPluginOwnership() {
        var reference = new PluginDefinitionRef("core", "sample");
        assertEquals("core", reference.pluginId());
        assertEquals("sample", reference.definitionId());
        assertThrows(IllegalArgumentException.class,
            () -> new PluginDefinitionRef("", "sample"));
        assertThrows(IllegalArgumentException.class,
            () -> new PluginDefinitionRef("core", " "));
    }

    @Test
    void builtInDefinitionsHaveStablePackageIdentity() {
        var definition = PluginDefinition.builder("sample", Void.class,
            () -> (context, config) -> Mono.empty()).build();
        var catalogEntry = new PluginCatalogEntry<>(definition, ignored -> null);
        var builtIn = BuiltInPluginPackage.builder()
            .pluginId(new PluginId("core"))
            .version("1.0.0")
            .packageDigest(DIGEST)
            .catalog(PluginCatalog.of(catalogEntry))
            .build();

        assertEquals(new PluginId("core"), builtIn.pluginId());
        assertEquals("1.0.0", builtIn.version());
        assertEquals(DIGEST, builtIn.packageDigest());
        assertEquals(new FacetId("host"), builtIn.facetId());
        assertEquals(new ExecutionTarget("host"), builtIn.executionTarget());
        assertEquals(new PluginSelection(new PluginId("core"), DIGEST, false),
            builtIn.selection(false));
        assertSame(catalogEntry,
            builtIn.definitions().get(new PluginDefinitionRef("core", "sample")));
        assertThrows(UnsupportedOperationException.class,
            () -> builtIn.definitions().clear());
        assertThrows(IllegalArgumentException.class, () -> BuiltInPluginPackage.builder()
            .pluginId(new PluginId("core")).version("1.0.0")
            .packageDigest("invalid").catalog(PluginCatalog.of(catalogEntry)).build());
    }

    @Test
    void dynamicAndBuiltInPluginIdsCannotConflict() {
        var definition = PluginDefinition.builder("sample", Void.class,
            () -> (context, config) -> Mono.empty()).build();
        var builtIn = BuiltInPluginPackage.builder()
            .pluginId(new PluginId("core")).version("1.0.0").packageDigest(DIGEST)
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition, ignored -> null)))
            .build();
        var facet = new ManagedFacet(new ArtifactId("dynamic"), new PluginId("core"),
            DIGEST, new PluginFacet(new FacetId("main"), FacetRole.HOST,
                new RuntimeId("java"), new ExecutionTarget("host"), Path.of("dynamic"),
                "b".repeat(64), List.of(), List.of()));
        var target = DeploymentTarget.of(1,
            List.of(new PluginSelection(new PluginId("core"), DIGEST, true)),
            new DesiredInputGraph(List.of()));

        assertThrows(IllegalArgumentException.class,
            () -> new DeploymentTargetCompiler().compile(target,
                List.of(ManagedPluginPackage.builder().pluginId(new PluginId("core"))
                    .version("1.0.0").packageRevision(DIGEST)
                    .facets(List.of(facet)).build()), List.of(builtIn)));
    }

    @Test
    void builtInHostFacetIsAnExactDependencyTarget() {
        var definition = PluginDefinition.builder("sample", Void.class,
            () -> (context, config) -> Mono.empty()).build();
        var builtIn = BuiltInPluginPackage.builder()
            .pluginId(new PluginId("core")).version("1.0.0").packageDigest(DIGEST)
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition, ignored -> null)))
            .build();
        var dynamicRevision = "b".repeat(64);
        var consumer = new ManagedFacet(new ArtifactId("consumer"),
            new PluginId("consumer"), dynamicRevision,
            new PluginFacet(new FacetId("main"), FacetRole.HOST,
                new RuntimeId("java"), new ExecutionTarget("host"),
                Path.of("consumer"), "c".repeat(64),
                List.of(new FacetDependency(new PluginId("core"),
                    new FacetId("host"))), List.of()));
        var dynamic = ManagedPluginPackage.builder()
            .pluginId(new PluginId("consumer")).version("1.0.0")
            .packageRevision(dynamicRevision).facets(List.of(consumer)).build();
        var target = DeploymentTarget.of(1, List.of(
            new PluginSelection(new PluginId("consumer"), dynamicRevision, true),
            builtIn.selection(true)), new DesiredInputGraph(List.of()));

        var compiled = new DeploymentTargetCompiler().compile(target,
            List.of(dynamic), List.of(builtIn));

        assertEquals(builtIn.artifactId(), compiled.facets().get(consumer.artifactId())
            .dependencies().getFirst().artifactId());
    }
}
