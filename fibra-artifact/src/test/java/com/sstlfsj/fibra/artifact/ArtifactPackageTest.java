package com.sstlfsj.fibra.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtifactPackageTest {
    @Test
    void readsCanonicalRootRuntimeAndFilePayload(@TempDir Path work) throws Exception {
        var root = Files.createDirectory(work.resolve("package"));
        Files.writeString(root.resolve("plugin.properties"),
            "formatVersion=1\nruntime=java\npayload=lib/plugin.jar\n");
        var payload = Files.createDirectories(root.resolve("lib")).resolve("plugin.jar");
        Files.writeString(payload, "payload");

        var artifact = ArtifactPackage.read(root.resolve("."));

        assertEquals(root.toRealPath(), artifact.root());
        assertEquals(new RuntimeId("java"), artifact.runtimeId());
        assertEquals(payload.toRealPath(), artifact.payload());
    }

    @Test
    void acceptsDirectoryPayload(@TempDir Path work) throws Exception {
        Files.writeString(work.resolve("plugin.properties"),
            "formatVersion=1\nruntime=node\npayload=node\n");
        var payload = Files.createDirectory(work.resolve("node"));
        assertEquals(payload.toRealPath(), ArtifactPackage.read(work).payload());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "runtime=java\npayload=plugin.jar\n",
        "formatVersion=2\nruntime=java\npayload=plugin.jar\n",
        "formatVersion=1\npayload=plugin.jar\n",
        "formatVersion=1\nruntime=java\n",
        "formatVersion=1\nruntime=\npayload=plugin.jar\n",
        "formatVersion=1\nruntime=java\npayload=\n",
        "formatVersion=1\nruntime=java\npayload=plugin.jar\nunknown=true\n",
        "formatVersion=1\nruntime=java\nruntime=node\npayload=plugin.jar\n",
        "formatVersion=1\nruntime=java\npayload=plugin.jar\npayload=plugin.jar\n",
        "formatVersion=1\nformatVersion=1\nruntime=java\npayload=plugin.jar\n"
    })
    void rejectsMissingUnknownDuplicateAndInvalidFields(String manifest, @TempDir Path work)
        throws Exception {
        Files.writeString(work.resolve("plugin.properties"), manifest);
        Files.writeString(work.resolve("plugin.jar"), "payload");
        assertInvalid(work);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing.jar", "../outside.jar", "sub/../../outside.jar", ".", "sub/.."})
    void rejectsAbsentAndEscapingPayload(String payload, @TempDir Path work) throws Exception {
        Files.writeString(work.resolve("plugin.properties"),
            "formatVersion=1\nruntime=java\npayload=" + payload + "\n");
        assertInvalid(work);
    }

    @Test
    void rejectsAbsolutePayloadEvenInsideRoot(@TempDir Path work) throws Exception {
        var payload = Files.writeString(work.resolve("plugin.jar"), "payload");
        Files.writeString(work.resolve("plugin.properties"),
            "formatVersion=1\nruntime=java\npayload=" + payload + "\n");
        assertInvalid(work);
    }

    @Test
    void rejectsBareJarAndMissingManifest(@TempDir Path work) throws Exception {
        assertInvalid(Files.writeString(work.resolve("plugin.jar"), "payload"));
        assertInvalid(work);
    }

    @Test
    void rejectsRootManifestPayloadAndOtherTreeSymlinks(@TempDir Path work) throws Exception {
        var root = Files.createDirectory(work.resolve("package"));
        var manifest = Files.writeString(root.resolve("plugin.properties"),
            "formatVersion=1\nruntime=java\npayload=plugin.jar\n");
        var payload = Files.writeString(root.resolve("plugin.jar"), "payload");
        assertInvalid(Files.createSymbolicLink(work.resolve("root-link"), root));

        Files.move(manifest, work.resolve("manifest"));
        Files.createSymbolicLink(manifest, work.resolve("manifest"));
        assertInvalid(root);
        Files.delete(manifest);
        Files.move(work.resolve("manifest"), manifest);

        Files.move(payload, work.resolve("payload"));
        Files.createSymbolicLink(payload, work.resolve("payload"));
        assertInvalid(root);
        Files.delete(payload);
        Files.move(work.resolve("payload"), payload);

        Files.createSymbolicLink(root.resolve("other"), payload);
        assertInvalid(root);
    }

    private static void assertInvalid(Path path) {
        var failure = assertThrows(ArtifactException.class, () -> ArtifactPackage.read(path));
        assertEquals(ArtifactPhase.VALIDATE, failure.phase());
    }
}
