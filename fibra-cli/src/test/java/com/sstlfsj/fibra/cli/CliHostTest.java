package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliHostTest {
    @TempDir Path work;

    @Test
    void firstStartBuildsAndSavesTheCompleteProfileTarget() throws Exception {
        profileWithSampleArtifact();

        try (var host = CliHost.open(paths())) {
            assertTrue(host.published().current().engine().artifacts().containsKey(
                new com.sstlfsj.fibra.artifact.ArtifactId("sample")));
            assertTrue(host.registry().get("sample").orElseThrow().observed().requirementSatisfied());
            assertTrue(host.probe() != null);
        }

        assertTrue(Files.isRegularFile(paths().stateRoot().resolve("target.json")));
    }

    @Test
    void restoresASavedTargetWithoutReadingAnyProfileSource() throws Exception {
        profileWithSampleArtifact();
        try (var ignored = CliHost.open(paths())) {
        }
        delete(paths().profileFile());
        delete(paths().profileArtifactsFile());
        delete(paths().pluginsRoot());

        try (var restored = CliHost.open(paths())) {
            assertTrue(restored.registry().get("sample").orElseThrow().observed().requirementSatisfied());
        }
    }

    @Test
    void firstStartRequiresTheAdjacentArtifactSelection() throws Exception {
        Files.createDirectories(paths().profileFile().getParent());
        Files.writeString(paths().profileFile(), "- {id: sample, plugin: sample}\n");

        var failure = assertThrows(RuntimeException.class, () -> CliHost.open(paths()));

        assertTrue(failure.getMessage().contains("default.artifacts.yaml"));
    }

    @Test
    void aSecondHostCannotOwnTheSameProfileUntilTheFirstCloses() throws Exception {
        profileWithSampleArtifact();
        var first = CliHost.open(paths());
        try {
            assertThrows(RuntimeException.class, () -> CliHost.open(paths()));
        } finally {
            first.close();
        }

        try (var reopened = CliHost.open(paths())) {
            assertTrue(reopened.registry().get("sample").orElseThrow().observed().requirementSatisfied());
        }
    }

    @Test
    void closeIsIdempotentAndReleasesTheProfileLocks() throws Exception {
        profileWithSampleArtifact();
        var host = CliHost.open(paths());
        host.close();
        host.close();

        try (var reopened = CliHost.open(paths())) {
            assertTrue(reopened.registry().get("sample").orElseThrow().observed().requirementSatisfied());
        }
    }

    @Test
    void applyReloadsTheCurrentProfileAndArtifactSelection() throws Exception {
        profileWithSampleArtifact();

        try (var host = CliHost.open(paths())) {
            Files.writeString(paths().profileFile(), "- {id: sample, plugin: sample, enabled: false}\n");

            var applied = host.apply();

            assertFalse(applied.desiredGraph().plugins().get("sample").enabled());
            assertFalse(applied.observed().containsKey("sample"));
        }
    }

    private CliPaths paths() {
        return CliPaths.resolve(work.resolve("home"), "default", null, null, null, null);
    }

    private void profileWithSampleArtifact() throws Exception {
        Files.createDirectories(paths().profileFile().getParent());
        Files.writeString(paths().profileFile(), "- {id: sample, plugin: sample}\n");
        Files.writeString(paths().profileArtifactsFile(), "- sample\n");
        var root = Files.createDirectories(paths().pluginsRoot().resolve("sample"));
        var lib = Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("plugin.properties"), """
            formatVersion=1
            runtime=java
            payload=lib/main.jar
            """);
        try (var output = new JarOutputStream(Files.newOutputStream(lib.resolve("main.jar")))) {
            output.putNextEntry(new JarEntry("META-INF/fibra/plugin.yaml"));
            output.write(("""
                id: sample
                version: 1.0.0
                entrypoint: %s
                requires: []
                """).formatted(TestEntrypoint.class.getName()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry(TestEntrypoint.class.getName().replace('.', '/') + ".class"));
            try (InputStream input = CliHostTest.class.getResourceAsStream('/' +
                TestEntrypoint.class.getName().replace('.', '/') + ".class")) {
                if (input == null) throw new IllegalStateException("missing test entrypoint class");
                output.write(input.readAllBytes());
            }
            output.closeEntry();
        }
    }

    private static void delete(Path path) throws Exception {
        if (Files.notExists(path)) return;
        try (var paths = Files.walk(path)) {
            for (var current : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(current);
            }
        }
    }

    public static final class TestEntrypoint implements PluginEntrypoint<Void> {
        @Override
        public PluginDefinition<Void> definition() {
            return PluginDefinition.builder("sample", Void.class,
                () -> (context, config) -> Mono.empty()).build();
        }
    }
}
