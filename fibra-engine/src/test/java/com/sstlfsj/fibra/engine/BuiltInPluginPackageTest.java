package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.*;
import com.sstlfsj.fibra.config.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuiltInPluginPackageTest {
    @Test
    void multipleFacetsKeepIdentityCapabilitiesAndDependencyOrder() {
        var api = BuiltInFacet.builder(new FacetId("api"), new RuntimeId("java"), new ExecutionTarget("host"))
            .definitionIds(Set.of("service")).requiredCapabilities(Set.of("storage")).build();
        var impl = BuiltInFacet.builder(new FacetId("impl"), new RuntimeId("java"), new ExecutionTarget("host"))
            .definitionIds(Set.of("service")).dependencies(List.of(new FacetDependency(
                new PluginId("core"), new FacetId("api")))).build();
        var builtIn = metadata(List.of(impl, api));
        var target = DeploymentTarget.of(1, List.of(builtIn.selection(true)), new DesiredInputGraph(List.of()),
            ConfigContextSnapshot.empty());
        var compiled = new DeploymentTargetCompiler().compile(target, List.of(), List.of(builtIn));
        assertEquals(List.of(builtIn.artifactId(api.facetId()), builtIn.artifactId(impl.facetId())),
            compiled.dependencyFirst());
        assertEquals(Set.of("storage"), compiled.builtInFacets().get(builtIn.artifactId(api.facetId()))
            .facet().requiredCapabilities());
        assertEquals(builtIn.artifactId(api.facetId()), compiled.builtInFacets()
            .get(builtIn.artifactId(impl.facetId())).dependencies().getFirst().artifactId());
        assertNotEquals(builtIn.artifactId(api.facetId()), builtIn.artifactId(impl.facetId()));
        assertThrows(UnsupportedOperationException.class, () -> builtIn.facets().clear());
    }

    @Test
    void builtInFacetCyclesAndCrossProviderFragmentsAreRejected() {
        var one = BuiltInFacet.builder(new FacetId("one"), new RuntimeId("java"), new ExecutionTarget("host"))
            .dependencies(List.of(new FacetDependency(new PluginId("core"), new FacetId("two")))).build();
        var two = BuiltInFacet.builder(new FacetId("two"), new RuntimeId("java"), new ExecutionTarget("host"))
            .dependencies(List.of(new FacetDependency(new PluginId("core"), new FacetId("one")))).build();
        var value = metadata(List.of(one, two));
        var target = DeploymentTarget.of(1, List.of(value.selection(true)), new DesiredInputGraph(List.of()),
            ConfigContextSnapshot.empty());
        assertThrows(IllegalArgumentException.class,
            () -> new DeploymentTargetCompiler().compile(target, List.of(), List.of(value)));
        var foreign = BuiltInFacet.builder(new FacetId("foreign"), new RuntimeId("node"), new ExecutionTarget("host")).build();
        assertThrows(IllegalArgumentException.class, () -> metadata(List.of(one, foreign)));
    }

    @Test
    void providerRevisionMustMatchEvenWhenItsPackageIsDisabled() {
        var value = metadata(List.of(BuiltInFacet.builder(new FacetId("host"), new RuntimeId("java"),
            new ExecutionTarget("host")).definitionIds(Set.of("sample")).build()));
        var target = DeploymentTarget.of(1, List.of(new PluginSelection(value.pluginId(), "b".repeat(64), false)),
            new DesiredInputGraph(List.of()), ConfigContextSnapshot.empty());
        assertThrows(IllegalArgumentException.class,
            () -> new DeploymentTargetCompiler().compile(target, List.of(), List.of(value)));
    }

    private static BuiltInPluginPackage metadata(List<BuiltInFacet> facets) {
        return BuiltInPluginPackage.builder().pluginId(new PluginId("core")).version("1")
            .packageDigest("a".repeat(64)).facets(facets).build();
    }
}
