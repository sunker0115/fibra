package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.PluginFacet;
import com.sstlfsj.fibra.artifact.RuntimeId;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JavaFacetGraphTest {
    @Test
    void usesCompilerResolvedExactWiringInDependencyFirstOrder() {
        var base = JavaArtifactRuntimeTest.managed("z-base", "a", Path.of("base.jar"), List.of());
        var app = JavaArtifactRuntimeTest.managed("app", "b", Path.of("app.jar"), List.of("z-base"));
        var graph = JavaFacetGraph.resolve(JavaArtifactRuntimeTest.compile(app, base));
        assertEquals(List.of(base.artifactId(), app.artifactId()), graph.dependencyFirst());
        assertEquals(base.packageRevision(), graph.facet(app.artifactId()).dependencies().getFirst().packageRevision());
    }

    @Test
    void rejectsDuplicatePhysicalFacetIdentity() {
        var base = JavaArtifactRuntimeTest.managed("base", "a", Path.of("base.jar"), List.of());
        var compiled = JavaArtifactRuntimeTest.compile(base).getFirst();
        assertThrows(JavaRuntimeException.class, () -> JavaFacetGraph.resolve(List.of(compiled, compiled)));
    }

    @Test
    void retainsExternalResolvedDependencyWithoutCreatingALocalLoaderEdge() {
        var original = JavaArtifactRuntimeTest.managed("external", "a", Path.of("external"), List.of());
        var source = original.facet();
        var base = new ManagedFacet(original.artifactId(), original.pluginId(), original.packageRevision(),
            new PluginFacet(source.facetId(), source.role(), new RuntimeId("node"), source.executionTarget(),
                source.payload(), source.payloadDigest(), source.dependencies(), source.requiredCapabilities()));
        var app = JavaArtifactRuntimeTest.managed("app", "b", Path.of("app.jar"), List.of("external"));
        var compiled = JavaArtifactRuntimeTest.compile(app, base).stream()
            .filter(value -> value.facet().artifactId().equals(app.artifactId())).toList();
        var graph = JavaFacetGraph.resolve(compiled);
        assertEquals(List.of(app.artifactId()), graph.dependencyFirst());
        assertEquals(base.artifactId(), graph.facet(app.artifactId()).dependencies().getFirst().artifactId());
    }
}
