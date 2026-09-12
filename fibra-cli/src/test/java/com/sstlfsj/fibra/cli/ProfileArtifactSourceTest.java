package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactPackage;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.engine.DeploymentArtifact;
import com.sstlfsj.fibra.engine.PluginArtifactProbe;
import com.sstlfsj.fibra.engine.PluginRuntimeAdapter;
import com.sstlfsj.fibra.engine.RuntimeArtifactInspection;
import com.sstlfsj.fibra.engine.RuntimeResourceOwner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileArtifactSourceTest {
    @TempDir Path work;

    @Test
    void constructionDoesNotReadSourcesOrProbePackages() {
        var adapter = new ProbeAdapter();
        source(adapter);
        assertTrue(adapter.seen.isEmpty());
    }

    @Test
    void requiresAnExistingManifest() {
        var failure = assertThrows(IllegalArgumentException.class, () -> source(new ProbeAdapter()).load());
        assertTrue(failure.getMessage().contains("default.artifacts.yaml"));
    }

    @Test
    void acceptsAnExplicitEmptySelectionWithoutReadingCandidates() throws Exception {
        manifest("[]");
        assertEquals(List.of(), source(new ProbeAdapter()).load());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "artifacts: []", "- 42", "- true", "- null",
        "- {}", "- []", "- ''", "- '   '", "[", "[]\n---\n[]"})
    void rejectsAnythingOtherThanOneStringArray(String value) throws Exception {
        manifest(value);
        var adapter = new ProbeAdapter();
        assertThrows(IllegalArgumentException.class, () -> source(adapter).load());
        assertTrue(adapter.seen.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "..", "../outside", "nested/../../outside", "/tmp/package",
        "https://example.com/plugin", "*.jar"})
    void rejectsUnsafeOrNonLocalPathsBeforeProbing(String value) throws Exception {
        manifest("- '" + value + "'");
        var adapter = new ProbeAdapter();
        assertThrows(IllegalArgumentException.class, () -> source(adapter).load());
        assertTrue(adapter.seen.isEmpty());
    }

    @Test
    void rejectsDuplicateNormalizedPathsBeforeProbing() throws Exception {
        installation("sample", "sample");
        manifest("- sample\n- nested/../sample\n");
        var adapter = new ProbeAdapter();
        var failure = assertThrows(IllegalArgumentException.class, () -> source(adapter).load());
        assertTrue(failure.getMessage().contains("duplicate artifact path"));
        assertTrue(adapter.seen.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"[first]", "{first,second}"})
    void rejectsGlobSyntaxEvenWhenALiteralPackageDirectoryExists(String name) throws Exception {
        installation(name, "sample");
        manifest("- '" + name + "'\n");
        var adapter = new ProbeAdapter();
        assertThrows(IllegalArgumentException.class, () -> source(adapter).load());
        assertTrue(adapter.seen.isEmpty());
    }

    @Test
    void probesOnlySelectedPackagesAndUsesTheirInternalIdentity() throws Exception {
        var first = installation("first", "one");
        var second = installation("second", "two");
        Files.createDirectory(work.resolve("plugins/unlisted-invalid-package"));
        manifest("- ./second\n- nested/../first\n");
        var adapter = new ProbeAdapter();

        var result = source(adapter).load();

        assertEquals(List.of(new ArtifactId("two"), new ArtifactId("one")),
            result.stream().map(DeploymentArtifact::artifactId).toList());
        assertEquals(List.of(second.toRealPath(), first.toRealPath()), adapter.seen);
        assertEquals(adapter.seen, result.stream().map(DeploymentArtifact::source).toList());
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    @Test
    void rejectsDuplicateInternalArtifactIds() throws Exception {
        installation("first", "same");
        installation("second", "same");
        manifest("- first\n- second\n");
        var failure = assertThrows(IllegalArgumentException.class, () -> source(new ProbeAdapter()).load());
        assertTrue(failure.getMessage().contains("duplicate artifact id"));
    }

    @Test
    void delegatesPackageValidationToTheProbe() throws Exception {
        Files.createDirectories(work.resolve("plugins/invalid"));
        manifest("- invalid\n");
        var adapter = new ProbeAdapter();
        assertThrows(RuntimeException.class, () -> source(adapter).load());
        assertTrue(adapter.seen.isEmpty());
    }

    @Test
    void rejectsAnIntermediateSymlinkEscapingTheCandidateRoot() throws Exception {
        var outside = Files.createDirectories(work.resolve("outside/package"));
        Files.writeString(outside.resolve("plugin.properties"), "formatVersion=1\nruntime=test\npayload=id\n");
        Files.writeString(outside.resolve("id"), "outside");
        Files.createDirectory(work.resolve("plugins"));
        Files.createSymbolicLink(work.resolve("plugins/link"), work.resolve("outside"));
        manifest("- link/package\n");
        var adapter = new ProbeAdapter();
        assertThrows(IllegalArgumentException.class, () -> source(adapter).load());
        assertTrue(adapter.seen.isEmpty());
    }

    private ProfileArtifactSource source(ProbeAdapter adapter) {
        return new ProfileArtifactSource(work.resolve("default.artifacts.yaml"), work.resolve("plugins"),
            new PluginArtifactProbe(List.of(adapter)));
    }

    private void manifest(String value) throws Exception {
        Files.writeString(work.resolve("default.artifacts.yaml"), value);
    }

    private Path installation(String directory, String id) throws Exception {
        var root = Files.createDirectories(work.resolve("plugins").resolve(directory));
        Files.writeString(root.resolve("plugin.properties"), "formatVersion=1\nruntime=test\npayload=id\n");
        Files.writeString(root.resolve("id"), id);
        return root;
    }

    private static final class ProbeAdapter implements PluginRuntimeAdapter {
        private final List<Path> seen = new ArrayList<>();

        @Override public RuntimeId id() { return new RuntimeId("test"); }

        @Override
        public Mono<DeploymentArtifact> probe(ArtifactPackage artifact) {
            return Mono.fromCallable(() -> {
                seen.add(artifact.root());
                return DeploymentArtifact.builder().artifactId(new ArtifactId(Files.readString(artifact.payload())))
                    .runtimeId(id()).version("1.0.0").source(artifact.root()).build();
            });
        }

        @Override public Mono<RuntimeArtifactInspection> inspect(ArtifactRecord artifact) {
            throw new AssertionError("source must not inspect installed artifacts");
        }

        @Override public RuntimeResourceOwner create() {
            throw new AssertionError("source must not create runtime resources");
        }
    }
}
