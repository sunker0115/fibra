package com.sstlfsj.fibra.runtime.java;

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
import com.sstlfsj.fibra.engine.ArtifactRuntime;
import com.sstlfsj.fibra.engine.DeploymentTarget;
import com.sstlfsj.fibra.engine.DeploymentTargetCompiler;
import com.sstlfsj.fibra.engine.PluginSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class JavaArtifactRuntimeTest {
    @TempDir Path work;

    @Test
    void preparesStaticEntrypointWithoutInvokingItsDefinition() throws Exception {
        var facet = facet("app", "a", "entrypoint: fixture.ThrowingEntrypoint\n",
            List.of(), fixture.ThrowingEntrypoint.class, fixture.ThrowingEntrypoint.PreparationFailure.class);
        var runtime = new JavaArtifactRuntime();
        try {
            runtime.probe(facet.facet()).block();
            assertEquals("fixture.ThrowingEntrypoint", runtime.inspect(facet).block().metadata().get("entrypoint"));
            var update = runtime.createUpdate(compile(facet));
            update.prepareAsync().block();
            var prepared = (JavaArtifactRuntime.JavaPreparedArtifact) update.preparedArtifacts().get(facet.artifactId());
            assertEquals(facet, prepared.facet());
            assertEquals("fixture.ThrowingEntrypoint", prepared.descriptor().entrypoint().orElseThrow());
            assertNotSame(getClass().getClassLoader(), prepared.classLoader());
            update.adopt();
            update.closeAsync().block();
            assertEquals(ArtifactRuntime.ResourceState.ACTIVE, runtime.snapshot().resources().getFirst().state());
        } finally { runtime.closeAsync().block(); }
    }

    @Test
    void rejectsLegacyIdentityUnknownDuplicateAndNonTextDescriptorFields() throws Exception {
        var runtime = new JavaArtifactRuntime();
        for (var descriptor : List.of("id: app\nversion: 1.0.0\n", "requires: []\n",
                "entrypoint: 42\n", "entrypoint: a\nentrypoint: b\n", "pluginId: app\n", "entrypoint: null\n",
                "{}\n---\nid: legacy\n")) {
            var facet = facet("bad" + Math.abs(descriptor.hashCode()), "a", descriptor, List.of());
            assertThrows(JavaRuntimeException.class, () -> runtime.probe(facet.facet()).block());
        }
        runtime.closeAsync().block();
    }

    @Test
    void classpathIsExactlyThePayloadJarAndRejectsImplicitManifestClasspath() throws Exception {
        var facet = facet("isolated", "a", "{}", List.of());
        Files.createDirectories(work.resolve("lib"));
        Files.writeString(work.resolve("lib/untrusted.jar"), "not a jar");
        var runtime = new JavaArtifactRuntime();
        try {
            var update = runtime.createUpdate(compile(facet));
            update.prepareAsync().block();
            var prepared = (JavaArtifactRuntime.JavaPreparedArtifact) update.preparedArtifacts().get(facet.artifactId());
            assertEquals(List.of(facet.facet().payload().toUri().toURL()),
                List.of(((PluginClassLoader) prepared.classLoader()).getURLs()));
            update.closeAsync().block();
            var manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "lib/untrusted.jar");
            try (var jar = new JarOutputStream(Files.newOutputStream(facet.facet().payload()), manifest)) {
                entry(jar, "META-INF/fibra/plugin.yaml", "{}");
            }
            assertThrows(JavaRuntimeException.class, () -> runtime.inspect(facet).block());
        } finally { runtime.closeAsync().block(); }
    }

    @Test
    void changedDependencyRebuildsDependentsAndPreservesUnrelatedIdentity() throws Exception {
        var base = facet("base", "a", "{}", List.of(), fixture.PrivateGreeting.class);
        var dependent = facet("dependent", "b", "{}", List.of("base"));
        var other = facet("other", "c", "{}", List.of());
        var runtime = new JavaArtifactRuntime();
        try {
            var first = runtime.createUpdate(compile(base, dependent, other));
            first.prepareAsync().block();
            var before = first.preparedArtifacts();
            first.adopt(); first.closeAsync().block();
            var revised = facet("base", "d", "{}", List.of(), fixture.PrivateGreeting.class);
            var second = runtime.createUpdate(compile(revised, dependent, other));
            second.prepareAsync().block();
            assertEquals(Set.of(base.artifactId(), dependent.artifactId()), second.affectedArtifacts());
            assertSame(before.get(other.artifactId()), second.preparedArtifacts().get(other.artifactId()));
            assertNotSame(before.get(dependent.artifactId()), second.preparedArtifacts().get(dependent.artifactId()));
            var prepared = (JavaArtifactRuntime.JavaPreparedArtifact) second.preparedArtifacts().get(dependent.artifactId());
            var basePrepared = (JavaArtifactRuntime.JavaPreparedArtifact) second.preparedArtifacts().get(base.artifactId());
            assertSame(basePrepared.classLoader(), prepared.classLoader().loadClass("fixture.PrivateGreeting").getClassLoader());
            assertEquals(revised.packageRevision(), prepared.dependencies().getFirst().packageRevision());
            second.adopt(); second.closeAsync().block();
        } finally { runtime.closeAsync().block(); }
    }

    @Test
    void partiallyPreparedLoaderIsRegisteredAndCleanupIsCached() throws Exception {
        var facet = facet("bad", "a", "entrypoint: fixture.Missing\n", List.of());
        var closes = new ArrayList<PluginClassLoader>();
        var runtime = new JavaArtifactRuntime(getClass().getClassLoader(), List.of("java.", "com.sstlfsj.fibra."), loader -> {
            closes.add(loader); loader.close();
        });
        var update = runtime.createUpdate(compile(facet));
        assertThrows(JavaRuntimeException.class, () -> update.prepareAsync().block());
        assertEquals(1, runtime.snapshot().resources().size());
        assertThrows(IllegalStateException.class, update::preparedArtifacts);
        var close = update.closeAsync();
        close.block(); close.block();
        assertEquals(1, closes.size());
        assertTrue(runtime.snapshot().resources().isEmpty());
        runtime.closeAsync().block();
    }

    @Test
    void failedCloseRetainsActualDependencyAndClosesIndependentSiblingOnlyOnce() throws Exception {
        var base = facet("base", "a", "{}", List.of());
        var dependent = facet("dependent", "b", "{}", List.of("base"));
        var other = facet("other", "c", "{}", List.of());
        var closed = new ArrayList<String>();
        var runtime = new JavaArtifactRuntime(getClass().getClassLoader(), List.of("java."), loader -> {
            var name = Path.of(loader.getURLs()[0].getPath()).getFileName().toString();
            closed.add(name);
            if (name.startsWith("dependent")) throw new IOException("cannot close dependent");
            loader.close();
        });
        var update = runtime.createUpdate(compile(base, dependent, other));
        update.prepareAsync().block();
        var prepared = update.preparedArtifacts();
        update.adopt(); update.closeAsync().block();
        var close = runtime.closeAsync();
        assertThrows(JavaRuntimeException.class, () -> close.block());
        assertThrows(JavaRuntimeException.class, () -> close.block());
        assertEquals(2, closed.size());
        assertFalse(closed.contains("base-a.jar"));
        assertTrue(runtime.snapshot().resources().stream().anyMatch(resource -> resource.state() == ArtifactRuntime.ResourceState.CLOSE_FAILED));
        for (var facet : List.of(base, dependent)) {
            ((PluginClassLoader) ((JavaArtifactRuntime.JavaPreparedArtifact) prepared.get(facet.artifactId())).classLoader()).close();
        }
    }

    @Test
    void cancellationBeforePreparationDoesNoIoAndReleasesPendingHandle() throws Exception {
        var facet = facet("unused", "a", "{}", List.of());
        var runtime = new JavaArtifactRuntime();
        var update = runtime.createUpdate(compile(facet));
        Files.delete(facet.facet().payload());
        update.closeAsync().block();
        assertTrue(runtime.snapshot().resources().isEmpty());
        assertThrows(IllegalStateException.class, () -> update.prepareAsync().block());
        runtime.createUpdate(List.of()).closeAsync().block();
        runtime.closeAsync().block();
    }

    @Test
    void prepareDoesNotRunStaticInitializerAndClosedPreparedArtifactCannotExposeLoader() throws Exception {
        var facet = facet("static", "a", "entrypoint: fixture.StaticFailureEntrypoint\n", List.of(),
            fixture.StaticFailureEntrypoint.class);
        var runtime = new JavaArtifactRuntime();
        var update = runtime.createUpdate(compile(facet));
        update.prepareAsync().block();
        var prepared = (JavaArtifactRuntime.JavaPreparedArtifact) update.preparedArtifacts().get(facet.artifactId());
        assertNotNull(prepared.classLoader());
        update.closeAsync().block();
        assertThrows(IllegalStateException.class, prepared::classLoader);
        runtime.closeAsync().block();
    }

    @Test
    void failedRetirementKeepsOldDependencyWithoutBlockingNewGeneration() throws Exception {
        var base = facet("base", "a", "{}", List.of(), fixture.PrivateGreeting.class);
        var dependent = facet("dependent", "b", "{}", List.of("base"));
        var closed = new ArrayList<PluginClassLoader>();
        var runtime = new JavaArtifactRuntime(getClass().getClassLoader(), List.of("java."), loader -> {
            if (loader.getURLs()[0].getPath().endsWith("dependent-b.jar")) throw new IOException("retire failed");
            closed.add(loader); loader.close();
        });
        var first = runtime.createUpdate(compile(base, dependent));
        first.prepareAsync().block();
        var oldBase = (JavaArtifactRuntime.JavaPreparedArtifact) first.preparedArtifacts().get(base.artifactId());
        var oldDependent = (JavaArtifactRuntime.JavaPreparedArtifact) first.preparedArtifacts().get(dependent.artifactId());
        first.adopt(); first.closeAsync().block();
        var nextBase = facet("base", "c", "{}", List.of(), fixture.PrivateGreeting.class);
        var nextDependent = facet("dependent", "d", "{}", List.of("base"));
        var second = runtime.createUpdate(compile(nextBase, nextDependent));
        second.prepareAsync().block(); second.adopt();
        assertThrows(JavaRuntimeException.class, () -> second.closeAsync().block());
        assertSame(oldBase.classLoader(), oldDependent.classLoader().loadClass("fixture.PrivateGreeting").getClassLoader());
        assertTrue(closed.isEmpty());
        assertThrows(JavaRuntimeException.class, () -> runtime.closeAsync().block());
        assertEquals(2, closed.size());
        assertEquals(4, runtime.snapshot().resources().size());
        ((PluginClassLoader) oldDependent.classLoader()).close();
        ((PluginClassLoader) oldBase.classLoader()).close();
    }

    ManagedFacet facet(String id, String revision, String descriptor, List<String> dependencies, Class<?>... types) throws Exception {
        var payload = work.resolve(id + '-' + revision + ".jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(payload))) {
            entry(jar, "META-INF/fibra/plugin.yaml", descriptor);
            for (var type : types) {
                var name = type.getName().replace('.', '/') + ".class";
                jar.putNextEntry(new JarEntry(name));
                try (var input = type.getResourceAsStream('/' + name)) { jar.write(input.readAllBytes()); }
                jar.closeEntry();
            }
        }
        return managed(id, revision, payload, dependencies);
    }

    static ManagedFacet managed(String id, String revision, Path payload, List<String> dependencies) {
        return new ManagedFacet(new ArtifactId(id), new PluginId(id), revision.repeat(64),
            new PluginFacet(new FacetId("host"), FacetRole.HOST, new RuntimeId("java"),
                new ExecutionTarget("host"), payload, "e".repeat(64),
                dependencies.stream().map(value -> new FacetDependency(new PluginId(value), new FacetId("host"))).toList(), List.of()));
    }

    static List<DeploymentTargetCompiler.CompiledFacet> compile(ManagedFacet... facets) {
        var packages = java.util.Arrays.stream(facets).map(facet -> ManagedPluginPackage.builder()
            .pluginId(facet.pluginId()).version("1.0.0").packageRevision(facet.packageRevision()).facets(List.of(facet)).build()).toList();
        var target = DeploymentTarget.of(1, packages.stream().map(pkg -> new PluginSelection(pkg.pluginId(), pkg.packageRevision(), true)).toList(), new DesiredInputGraph(List.of()));
        return List.copyOf(new DeploymentTargetCompiler().compile(target, packages, List.of()).facets().values());
    }

    private static void entry(JarOutputStream jar, String name, String content) throws Exception {
        jar.putNextEntry(new JarEntry(name)); jar.write(content.getBytes(StandardCharsets.UTF_8)); jar.closeEntry();
    }
}
