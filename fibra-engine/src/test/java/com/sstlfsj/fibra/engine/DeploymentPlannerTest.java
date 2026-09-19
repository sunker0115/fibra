package com.sstlfsj.fibra.engine;

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
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeploymentPlannerTest {
    private static final RuntimeId JAVA = new RuntimeId("java");
    private static final String ALPHA_REVISION = "a".repeat(64);
    private static final String BETA_REVISION = "b".repeat(64);
    private static final String GAMMA_REVISION_1 = "c".repeat(64);
    private static final String GAMMA_REVISION_2 = "d".repeat(64);
    private static final String DELTA_REVISION = "e".repeat(64);
    private static final String UNUSED_REVISION_1 = "1".repeat(64);
    private static final String UNUSED_REVISION_2 = "2".repeat(64);

    @TempDir
    Path temporary;

    @Test
    void dependencyOnlyRevisionChangeAffectsItsConsumerAndExecutionDependents() {
        var planner = new DeploymentPlanner();
        var previousInput = planner.inputs(target(1, GAMMA_REVISION_1,
                UNUSED_REVISION_1), packages(GAMMA_REVISION_1,
                UNUSED_REVISION_1), List.of(), HostCapabilitySnapshot.empty());
        var previous = compile(planner, previousInput);
        var next = planner.inputs(target(2, GAMMA_REVISION_2,
                UNUSED_REVISION_1), packages(GAMMA_REVISION_2,
                UNUSED_REVISION_1), List.of(), HostCapabilitySnapshot.empty());

        assertEquals(Set.of(new ExecutionUnitKey("a1"),
                new ExecutionUnitKey("d1")),
            planner.affected(next, previous, Set.of(), false));
    }

    @Test
    void unrelatedDependencyOnlyRevisionChangeDoesNotAffectExistingUnits() {
        var planner = new DeploymentPlanner();
        var previousInput = planner.inputs(target(1, GAMMA_REVISION_1,
                UNUSED_REVISION_1), packages(GAMMA_REVISION_1,
                UNUSED_REVISION_1), List.of(), HostCapabilitySnapshot.empty());
        var previous = compile(planner, previousInput);
        var next = planner.inputs(target(2, GAMMA_REVISION_1,
                UNUSED_REVISION_2), packages(GAMMA_REVISION_1,
                UNUSED_REVISION_2), List.of(), HostCapabilitySnapshot.empty());

        assertEquals(Set.of(),
            planner.affected(next, previous, Set.of(), false));
    }

    @Test
    void staticDependencyEdgeChangeAffectsConsumerAndExecutionDependents() {
        var planner = new DeploymentPlanner();
        var previousInput = planner.inputs(target(1, GAMMA_REVISION_1,
                UNUSED_REVISION_1), packages(GAMMA_REVISION_1,
                UNUSED_REVISION_1, false), List.of(), HostCapabilitySnapshot.empty());
        var previous = compile(planner, previousInput);
        var next = planner.inputs(target(2, GAMMA_REVISION_1,
                UNUSED_REVISION_1), packages(GAMMA_REVISION_1,
                UNUSED_REVISION_1, true), List.of(), HostCapabilitySnapshot.empty());

        assertEquals(Set.of(new ExecutionUnitKey("a1"),
                new ExecutionUnitKey("d1")),
            planner.affected(next, previous, Set.of(), false));
    }

    @Test
    void activeUnitRequiresCapabilitiesFromItsStaticFacetClosure() {
        var planner = new DeploymentPlanner();
        var packages = packages(GAMMA_REVISION_1, UNUSED_REVISION_1).stream()
            .map(value -> value.pluginId().value().equals("gamma")
                ? pluginPackage("gamma", GAMMA_REVISION_1,
                    facet("gamma", GAMMA_REVISION_1, List.of(),
                        List.of("contract-codec")))
                : value)
            .toList();
        var target = target(1, GAMMA_REVISION_1, UNUSED_REVISION_1);

        assertThrows(IllegalArgumentException.class, () -> planner.inputs(
            target, packages, List.of(), HostCapabilitySnapshot.empty()));
        planner.inputs(target, packages, List.of(), HostCapabilitySnapshot.of(
            Map.of("contract-codec", Map.of("version", 1))));
    }

    @Test
    void unusedFacetMissingCapabilitiesDoesNotBlockActiveDeployment() {
        var planner = new DeploymentPlanner();
        var packages = packages(GAMMA_REVISION_1, UNUSED_REVISION_1).stream()
            .map(value -> value.pluginId().value().equals("unused")
                ? pluginPackage("unused", UNUSED_REVISION_1,
                    facet("unused", UNUSED_REVISION_1, List.of(),
                        List.of("unselected-capability")))
                : value)
            .toList();

        var input = planner.inputs(target(1, GAMMA_REVISION_1,
            UNUSED_REVISION_1), packages, List.of(),
            HostCapabilitySnapshot.empty());

        assertFalse(input.entries().containsKey("unused"));
        assertFalse(input.units().containsKey(new ExecutionUnitKey("unused")));
        assertEquals(Set.of(new ExecutionUnitKey("a1"),
                new ExecutionUnitKey("d1")), input.units().keySet());
    }

    private CompiledDeployment compile(DeploymentPlanner planner,
                                       DeploymentPlanner.Input input) {
        var bindings = input.units().values().stream().map(unit -> {
            var entry = input.entries().get(unit.key().value());
            return DefinitionBindingPlan.builder(entry.definitionRef(),
                    unit.key().value())
                .unitKey(unit.key())
                .publicationRequirement(entry.publicationRequirement())
                .build();
        }).toList();
        var plan = RuntimePlan.of(JAVA, input.units().values(), bindings);
        return planner.validate(input, "f".repeat(64), Map.of(JAVA, plan),
            Set.of());
    }

    private DeploymentTarget target(long targetRevision, String gammaRevision,
                                    String unusedRevision) {
        return DeploymentTarget.of(targetRevision, List.of(
                selection("alpha", ALPHA_REVISION),
                selection("beta", BETA_REVISION),
                selection("gamma", gammaRevision),
                selection("delta", DELTA_REVISION),
                selection("unused", unusedRevision)),
            new DesiredInputGraph(List.of(entry("a1", "alpha"),
                entry("d1", "delta"))), ConfigContextSnapshot.empty());
    }

    private List<ManagedPluginPackage> packages(String gammaRevision,
                                                String unusedRevision) {
        return packages(gammaRevision, unusedRevision, true);
    }

    private List<ManagedPluginPackage> packages(String gammaRevision,
                                                String unusedRevision,
                                                boolean gammaThroughBeta) {
        return List.of(
            pluginPackage("alpha", ALPHA_REVISION,
                facet("alpha", ALPHA_REVISION,
                    gammaThroughBeta
                        ? List.of(dependency("beta"))
                        : List.of(dependency("beta"), dependency("gamma")))),
            pluginPackage("beta", BETA_REVISION,
                facet("beta", BETA_REVISION,
                    gammaThroughBeta
                        ? List.of(dependency("gamma")) : List.of())),
            pluginPackage("gamma", gammaRevision,
                facet("gamma", gammaRevision, List.of())),
            pluginPackage("delta", DELTA_REVISION,
                facet("delta", DELTA_REVISION,
                    List.of(dependency("alpha")))),
            pluginPackage("unused", unusedRevision,
                facet("unused", unusedRevision, List.of())));
    }

    private ManagedFacet facet(String pluginId, String revision,
                               List<FacetDependency> dependencies) {
        return facet(pluginId, revision, dependencies, List.of());
    }

    private ManagedFacet facet(String pluginId, String revision,
                               List<FacetDependency> dependencies,
                               List<String> requiredCapabilities) {
        var facetId = new FacetId("main");
        var facet = new PluginFacet(facetId, FacetRole.HOST, JAVA,
            new ExecutionTarget("host"), temporary.resolve(pluginId + '-' + revision),
            "9".repeat(64), dependencies, requiredCapabilities);
        return new ManagedFacet(new ArtifactId(pluginId + ':' + revision),
            new PluginId(pluginId), revision, facet);
    }

    private static ManagedPluginPackage pluginPackage(String pluginId,
                                                       String revision,
                                                       ManagedFacet facet) {
        return ManagedPluginPackage.builder().pluginId(new PluginId(pluginId))
            .version("1.0.0").packageRevision(revision)
            .facets(List.of(facet)).build();
    }

    private static DesiredInputEntry entry(String id, String pluginId) {
        return DesiredInputEntry.builder(id,
                new PluginDefinitionRef(pluginId, "main", "definition"))
            .config(LiteralValue.of(Map.of())).build();
    }

    private static FacetDependency dependency(String pluginId) {
        return new FacetDependency(new PluginId(pluginId), new FacetId("main"));
    }

    private static PluginSelection selection(String pluginId, String revision) {
        return new PluginSelection(new PluginId(pluginId), revision, true);
    }
}
