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
import com.sstlfsj.fibra.artifact.PluginPackage;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeploymentTargetCompilerTest {
    private static final String A_REVISION = "a".repeat(64);
    private static final String B_REVISION = "b".repeat(64);
    private static final String PAYLOAD_DIGEST = "c".repeat(64);

    @TempDir
    Path temporary;

    @Test
    void resolvesSamePackageAndCrossPackageDependenciesExactly() throws Exception {
        var alpha = readPackage("alpha", """
            format: 1
            id: alpha
            version: 1.0.0
            facets:
              - id: host
                role: host
                runtime: java
                target: host
                payload: host.jar
                dependencies: []
                capabilities: []
              - id: client
                role: client
                runtime: client
                target: browser
                payload: client.js
                dependencies:
                  - pluginId: alpha
                    facetId: host
                  - pluginId: beta
                    facetId: service
                capabilities: [dom]
            """, List.of("host.jar", "client.js"));
        var beta = readPackage("beta", """
            format: 1
            id: beta
            version: 1.0.0
            facets:
              - id: service
                role: host
                runtime: java
                target: host
                payload: service.jar
                dependencies: []
                capabilities: []
            """, List.of("service.jar"));
        var managedAlpha = ManagedPluginPackage.from(alpha, Map.of(
            new FacetId("host"), new ArtifactId("a-host"),
            new FacetId("client"), new ArtifactId("a-client")));
        var managedBeta = ManagedPluginPackage.from(beta, Map.of(
            new FacetId("service"), new ArtifactId("b-service")));
        var aHost = requireFacet(managedAlpha, "host");
        var aClient = requireFacet(managedAlpha, "client");
        var bService = requireFacet(managedBeta, "service");
        var target = target(List.of(selection("beta", beta.packageDigest(), true),
            selection("alpha", alpha.packageDigest(), true)));

        var compiled = new DeploymentTargetCompiler().compile(target,
            List.of(managedAlpha, managedBeta), List.of());

        assertEquals(List.of(aHost.artifactId(), bService.artifactId(), aClient.artifactId()),
            compiled.dependencyFirst());
        assertEquals(List.of(
            new ResolvedFacetDependency(new PluginId("alpha"), alpha.packageDigest(),
                new FacetId("host"), aHost.artifactId()),
            new ResolvedFacetDependency(new PluginId("beta"), beta.packageDigest(),
                new FacetId("service"), bService.artifactId())),
            compiled.facets().get(aClient.artifactId()).dependencies());
    }

    @Test
    void rejectsMissingDisabledAndDuplicateDependencyTargets() {
        var beta = facet("b-service", "beta", B_REVISION, "service", List.of());
        var missingFacet = facet("a-client", "alpha", A_REVISION, "client",
            List.of(dependency("beta", "missing")));
        var dependencyOnDisabled = facet("a-disabled", "alpha", A_REVISION, "disabled",
            List.of(dependency("beta", "service")));
        var duplicate = facet("a-duplicate", "alpha", A_REVISION, "duplicate",
            List.of(dependency("beta", "service"), dependency("beta", "service")));
        var compiler = new DeploymentTargetCompiler();

        assertThrows(IllegalArgumentException.class, () -> compiler.compile(
            target(List.of(selection("alpha", A_REVISION, true),
                selection("beta", B_REVISION, true))),
            List.of(managedPackage("alpha", A_REVISION, missingFacet),
                managedPackage("beta", B_REVISION, beta)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(
            target(List.of(selection("alpha", A_REVISION, true),
                selection("beta", B_REVISION, false))),
            List.of(managedPackage("alpha", A_REVISION, dependencyOnDisabled),
                managedPackage("beta", B_REVISION, beta)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(
            target(List.of(selection("alpha", A_REVISION, true),
                selection("beta", B_REVISION, true))),
            List.of(managedPackage("alpha", A_REVISION, duplicate),
                managedPackage("beta", B_REVISION, beta)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(
            target(List.of(selection("alpha", A_REVISION, true),
                selection("beta", B_REVISION, true))),
            List.of(managedPackage("alpha", A_REVISION, missingFacet)), List.of()));
    }

    @Test
    void rejectsCyclesBeforeTheTargetCanBeSaved() {
        var alpha = facet("a", "alpha", A_REVISION, "main",
            List.of(dependency("beta", "main")));
        var beta = facet("b", "beta", B_REVISION, "main",
            List.of(dependency("alpha", "main")));

        assertThrows(IllegalArgumentException.class, () -> new DeploymentTargetCompiler().compile(
            target(List.of(selection("alpha", A_REVISION, true),
                selection("beta", B_REVISION, true))),
            List.of(managedPackage("alpha", A_REVISION, alpha),
                managedPackage("beta", B_REVISION, beta)), List.of()));
    }

    @Test
    void exactRevisionSelectionExcludesDisabledAndOtherInstalledRevisions() {
        var selected = facet("selected", "alpha", A_REVISION, "main", List.of());
        var otherRevision = facet("other", "alpha", B_REVISION, "other", List.of());
        var disabled = facet("disabled", "beta", B_REVISION, "main", List.of());

        var compiled = new DeploymentTargetCompiler().compile(target(List.of(
            selection("alpha", A_REVISION, true),
            selection("beta", B_REVISION, false))),
            List.of(managedPackage("alpha", B_REVISION, otherRevision),
                managedPackage("beta", B_REVISION, disabled),
                managedPackage("alpha", A_REVISION, selected)), List.of());

        assertEquals(List.of(selected.artifactId()), compiled.dependencyFirst());
        assertEquals(List.of(selected.artifactId()), compiled.facets().keySet().stream().toList());
        assertThrows(UnsupportedOperationException.class, () -> compiled.facets().clear());
        assertThrows(UnsupportedOperationException.class, () -> compiled.facets()
            .get(selected.artifactId()).dependencies().clear());
    }

    private DeploymentTarget target(List<PluginSelection> selections) {
        return DeploymentTarget.of(1, selections, new DesiredInputGraph(List.of()),
            com.sstlfsj.fibra.config.ConfigContextSnapshot.empty());
    }

    private ManagedFacet facet(String artifactId, String pluginId, String revision,
                               String facetId, List<FacetDependency> dependencies) {
        var id = new FacetId(facetId);
        var facet = new PluginFacet(id, FacetRole.HOST, new RuntimeId("java"),
            new ExecutionTarget("host"), temporary.resolve(artifactId), PAYLOAD_DIGEST,
            dependencies, List.of());
        return new ManagedFacet(new ArtifactId(artifactId), new PluginId(pluginId),
            revision, facet);
    }

    private PluginPackage readPackage(String directory, String manifest,
                                      List<String> payloads) throws Exception {
        var root = temporary.resolve(directory);
        Files.createDirectories(root);
        for (var payload : payloads) {
            Files.writeString(root.resolve(payload), payload);
        }
        Files.writeString(root.resolve(PluginPackage.MANIFEST), manifest);
        return PluginPackage.read(root);
    }

    private static ManagedFacet requireFacet(ManagedPluginPackage pluginPackage,
                                             String facetId) {
        return pluginPackage.facets().stream()
            .filter(value -> value.facet().facetId().equals(new FacetId(facetId)))
            .findFirst().orElseThrow();
    }

    private static ManagedPluginPackage managedPackage(String pluginId, String revision,
                                                        ManagedFacet... facets) {
        return ManagedPluginPackage.builder().pluginId(new PluginId(pluginId))
            .version("1.0.0").packageRevision(revision)
            .facets(List.of(facets)).build();
    }

    private static FacetDependency dependency(String pluginId, String facetId) {
        return new FacetDependency(new PluginId(pluginId), new FacetId(facetId));
    }

    private static PluginSelection selection(String pluginId, String revision,
                                             boolean enabled) {
        return new PluginSelection(new PluginId(pluginId), revision, enabled);
    }
}
