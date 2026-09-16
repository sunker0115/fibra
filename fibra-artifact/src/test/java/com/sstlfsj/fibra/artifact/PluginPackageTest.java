package com.sstlfsj.fibra.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginPackageTest {
    @Test
    void readsCanonicalThreeFacetPackageAndComputesContentIdentities(@TempDir Path work)
        throws Exception {
        var root = canonicalPackage(work);

        var plugin = PluginPackage.read(root);

        assertEquals(root.toRealPath(), plugin.root());
        assertEquals(new PluginId("example.tools"), plugin.pluginId());
        assertEquals("1.2.3", plugin.version());
        assertEquals(digest(root), plugin.packageDigest());
        assertEquals(3, plugin.facets().size());

        var host = plugin.facets().get(0);
        assertEquals(new FacetId("host"), host.facetId());
        assertEquals(FacetRole.HOST, host.role());
        assertEquals(new RuntimeId("java"), host.runtimeId());
        assertEquals(new ExecutionTarget("host"), host.executionTarget());
        assertEquals(root.resolve("host/plugin.jar").toRealPath(), host.payload());
        assertEquals(digest(root.resolve("host/plugin.jar")), host.payloadDigest());
        assertEquals(java.util.List.of(), host.dependencies());
        assertEquals(java.util.List.of(), host.requiredCapabilities());

        var command = plugin.facets().get(1);
        assertEquals(FacetRole.COMMAND, command.role());
        assertEquals(java.util.List.of(
            new FacetDependency(new PluginId("example.tools"), new FacetId("host"))),
            command.dependencies());

        var client = plugin.facets().get(2);
        assertEquals(FacetRole.CLIENT, client.role());
        assertEquals(new ExecutionTarget("client:web"), client.executionTarget());
        assertEquals(java.util.List.of(
            new FacetDependency(new PluginId("example.tools"), new FacetId("command")),
            new FacetDependency(new PluginId("shared.contract"), new FacetId("public-api"))),
            client.dependencies());
        assertEquals(java.util.List.of("dom", "client.web.module.blob.v1"),
            client.requiredCapabilities());
        assertThrows(UnsupportedOperationException.class,
            () -> plugin.facets().add(host));
        assertThrows(UnsupportedOperationException.class,
            () -> client.dependencies().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> client.requiredCapabilities().clear());
    }

    @Test
    void contentChangesProduceNewComputedDigests(@TempDir Path work) throws Exception {
        var root = canonicalPackage(work);
        var first = PluginPackage.read(root);

        Files.writeString(root.resolve("client/index.mjs"), "export default 'changed';\n");
        var second = PluginPackage.read(root);

        assertNotEquals(first.packageDigest(), second.packageDigest());
        assertEquals(first.facets().get(0).payloadDigest(),
            second.facets().get(0).payloadDigest());
        assertNotEquals(first.facets().get(2).payloadDigest(),
            second.facets().get(2).payloadDigest());
    }

    @Test
    void rejectsContentChangedBetweenSnapshotPasses(@TempDir Path work) throws Exception {
        var root = canonicalPackage(work);
        var payload = root.resolve("client/index.mjs");
        var replacement = "export default 'server';\n";
        assertEquals(Files.size(payload), replacement.getBytes(StandardCharsets.UTF_8).length);

        var failure = assertThrows(ArtifactException.class, () -> PluginPackage.read(root, () -> {
            try {
                Files.writeString(payload, replacement);
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }));

        assertEquals(ArtifactPhase.DIGEST, failure.phase());
    }

    @Test
    void directoryDigestFramesFileContentFromFollowingEntries(@TempDir Path work)
        throws Exception {
        var firstRoot = Files.createDirectory(work.resolve("first-package"));
        var firstPayload = Files.createDirectory(firstRoot.resolve("payload"));
        Files.writeString(firstPayload.resolve("a"), "Fb\0x");
        Files.writeString(firstRoot.resolve("fibra-package.yaml"),
            singleFacetManifest("", "payload"));
        var secondRoot = Files.createDirectory(work.resolve("second-package"));
        var secondPayload = Files.createDirectory(secondRoot.resolve("payload"));
        Files.writeString(secondPayload.resolve("a"), "");
        Files.writeString(secondPayload.resolve("b"), "x");
        Files.writeString(secondRoot.resolve("fibra-package.yaml"),
            singleFacetManifest("", "payload"));

        assertNotEquals(digest(firstPayload), digest(secondPayload));
        assertNotEquals(PluginPackage.read(firstRoot).facets().get(0).payloadDigest(),
            PluginPackage.read(secondRoot).facets().get(0).payloadDigest());
    }

    @Test
    void digestUsesUtf8PathOrderAndIgnoresModificationTime(@TempDir Path work)
        throws Exception {
        var root = Files.createDirectory(work.resolve("package"));
        var payload = Files.createDirectory(root.resolve("payload"));
        var supplementary = Files.writeString(payload.resolve("\uD800\uDC00"), "supplementary");
        Files.writeString(payload.resolve("\uE000"), "private-use");
        Files.writeString(root.resolve("fibra-package.yaml"),
            singleFacetManifest("", "payload"));

        var first = PluginPackage.read(root);
        assertEquals(digest(payload), first.facets().get(0).payloadDigest());

        Files.setLastModifiedTime(supplementary,
            FileTime.fromMillis(Files.getLastModifiedTime(supplementary).toMillis() + 60_000));
        var second = PluginPackage.read(root);

        assertEquals(first.packageDigest(), second.packageDigest());
        assertEquals(first.facets().get(0).payloadDigest(),
            second.facets().get(0).payloadDigest());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "format: 1\nid: example.tools\nversion: 1.0.0\n",
        "format: 2\nid: example.tools\nversion: 1.0.0\nfacets: []\n",
        "format: '1'\nid: example.tools\nversion: 1.0.0\nfacets: []\n",
        "format: 1.0\nid: example.tools\nversion: 1.0.0\nfacets: []\n",
        "format: 1\nid: ''\nversion: 1.0.0\nfacets: []\n",
        "format: 1\nid: example.tools\nversion: ''\nfacets: []\n",
        "format: 1\nid: example.tools\nversion: 1.0.0\nfacets: []\n",
        "format: 1\nid: example.tools\nid: duplicate\nversion: 1.0.0\nfacets: []\n",
        "format: 1\nid: example.tools\nversion: 1.0.0\nfacets: []\nunknown: true\n",
        "format: 1\nid: example.tools\nversion: 1.0.0\npackageDigest: wrong\nfacets: []\n"
    })
    void rejectsMissingInvalidUnknownDuplicateAndSelfReportedPackageFields(
        String manifest, @TempDir Path work) throws Exception {
        Files.writeString(work.resolve("fibra-package.yaml"), manifest);
        assertInvalid(work);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "    unknown: true\n",
        "    id: duplicate\n",
        "    payloadDigest: wrong\n",
        "    id: ''\n",
        "    role: ''\n",
        "    runtime: ''\n",
        "    target: ''\n",
        "    payload: ''\n",
        "    dependencies: {}\n",
        "    capabilities: {}\n"
    })
    void rejectsUnknownDuplicateEmptyAndSelfReportedFacetFields(
        String extra, @TempDir Path work) throws Exception {
        Files.writeString(work.resolve("payload.bin"), "payload");
        Files.writeString(work.resolve("fibra-package.yaml"), singleFacetManifest(extra));
        assertInvalid(work);
    }

    @Test
    void rejectsDuplicateFacetIds(@TempDir Path work) throws Exception {
        Files.writeString(work.resolve("one.bin"), "one");
        Files.writeString(work.resolve("two.bin"), "two");
        Files.writeString(work.resolve("fibra-package.yaml"), """
            format: 1
            id: example.tools
            version: 1.0.0
            facets:
              - id: duplicate
                role: host
                runtime: java
                target: host
                payload: one.bin
                dependencies: []
                capabilities: []
              - id: duplicate
                role: client
                runtime: client
                target: client:web
                payload: two.bin
                dependencies: []
                capabilities: []
            """);

        assertInvalid(work);
    }

    @Test
    void rejectsDuplicateCapabilities(@TempDir Path work) throws Exception {
        Files.writeString(work.resolve("payload.bin"), "payload");
        Files.writeString(work.resolve("fibra-package.yaml"), """
            format: 1
            id: example.tools
            version: 1.0.0
            facets:
              - id: client
                role: client
                runtime: client
                target: client:web
                payload: payload.bin
                dependencies: []
                capabilities:
                  - dom
                  - dom
            """);

        assertInvalid(work);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "missing.bin", "../outside.bin", "sub/../../outside.bin", ".", "sub/..",
        "fibra-package.yaml"
    })
    void rejectsAbsentAndEscapingPayloads(String payload, @TempDir Path work)
        throws Exception {
        Files.writeString(work.resolve("fibra-package.yaml"),
            singleFacetManifest("", payload));
        assertInvalid(work);
    }

    @Test
    void rejectsAbsolutePayloadEvenInsideRoot(@TempDir Path work) throws Exception {
        var payload = Files.writeString(work.resolve("payload.bin"), "payload");
        Files.writeString(work.resolve("fibra-package.yaml"),
            singleFacetManifest("", payload.toString()));
        assertInvalid(work);
    }

    @Test
    void rejectsRootManifestPayloadAndOtherTreeSymlinks(@TempDir Path work) throws Exception {
        var root = canonicalPackage(Files.createDirectory(work.resolve("source")));
        assertInvalid(Files.createSymbolicLink(work.resolve("root-link"), root));

        var manifest = root.resolve("fibra-package.yaml");
        var savedManifest = Files.move(manifest, work.resolve("manifest"));
        Files.createSymbolicLink(manifest, savedManifest);
        assertInvalid(root);
        Files.delete(manifest);
        Files.move(savedManifest, manifest);

        var payload = root.resolve("host/plugin.jar");
        var savedPayload = Files.move(payload, work.resolve("payload"));
        Files.createSymbolicLink(payload, savedPayload);
        assertInvalid(root);
        Files.delete(payload);
        Files.move(savedPayload, payload);

        Files.createSymbolicLink(root.resolve("other"), payload);
        assertInvalid(root);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "      - pluginId: example.tools\n        facetId: host\n        artifactId: physical\n",
        "      - pluginId: example.tools\n        facetId: host\n        packageRevision: abc\n",
        "      - pluginId: example.tools\n        facetId: host\n        versionConstraint: 1.x\n",
        "      - pluginId: example.tools\n        facetId: host\n        unknown: true\n",
        "      - facetId: host\n",
        "      - pluginId: example.tools\n",
        "      - pluginId: ''\n        facetId: host\n",
        "      - pluginId: example.tools\n        facetId: ''\n"
    })
    void rejectsPhysicalVersionedUnknownAndEmptyDependencyFields(
        String dependencies, @TempDir Path work) throws Exception {
        Files.writeString(work.resolve("payload.bin"), "payload");
        Files.writeString(work.resolve("fibra-package.yaml"), """
            format: 1
            id: example.tools
            version: 1.0.0
            facets:
              - id: client
                role: client
                runtime: client
                target: client:web
                payload: payload.bin
                dependencies:
            """ + dependencies + "    capabilities: []\n");

        assertInvalid(work);
    }

    @Test
    void rejectsLegacySingleFacetPackage(@TempDir Path work) throws Exception {
        Files.writeString(work.resolve("plugin.properties"),
            "formatVersion=1\nruntime=java\npayload=plugin.jar\n");
        Files.writeString(work.resolve("plugin.jar"), "payload");

        assertInvalid(work);
    }

    private static Path canonicalPackage(Path work) throws IOException {
        var root = Files.createDirectory(work.resolve("package"));
        Files.createDirectories(root.resolve("host"));
        Files.createDirectories(root.resolve("command"));
        Files.createDirectories(root.resolve("client"));
        Files.writeString(root.resolve("host/plugin.jar"), "host");
        Files.writeString(root.resolve("command/index.mjs"), "export default 'command';\n");
        Files.writeString(root.resolve("client/index.mjs"), "export default 'client';\n");
        Files.writeString(root.resolve("fibra-package.yaml"), """
            format: 1
            id: example.tools
            version: 1.2.3
            facets:
              - id: host
                role: host
                runtime: java
                target: host
                payload: host/plugin.jar
                dependencies: []
                capabilities: []
              - id: command
                role: command
                runtime: node
                target: host
                payload: command
                dependencies:
                  - pluginId: example.tools
                    facetId: host
                capabilities: []
              - id: client
                role: client
                runtime: client
                target: client:web
                payload: client
                dependencies:
                  - pluginId: example.tools
                    facetId: command
                  - pluginId: shared.contract
                    facetId: public-api
                capabilities:
                  - dom
                  - client.web.module.blob.v1
            """);
        return root;
    }

    private static String singleFacetManifest(String extra) {
        return singleFacetManifest(extra, "payload.bin");
    }

    private static String singleFacetManifest(String extra, String payload) {
        return """
            format: 1
            id: example.tools
            version: 1.0.0
            facets:
              - id: host
                role: host
                runtime: java
                target: host
                payload: %s
                dependencies: []
                capabilities: []
            """.formatted(payload) + extra;
    }

    private static void assertInvalid(Path path) {
        var failure = assertThrows(ArtifactException.class, () -> PluginPackage.read(path));
        assertEquals(ArtifactPhase.VALIDATE, failure.phase());
    }

    private static String digest(Path source) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update("fibra-content-v1\0".getBytes(StandardCharsets.UTF_8));
        if (Files.isRegularFile(source)) {
            digest.update((byte) 'F');
            updateLength(digest, Files.size(source));
            digest.update(Files.readAllBytes(source));
        } else {
            digest.update((byte) 'T');
            try (var paths = Files.walk(source)) {
                var entries = paths.filter(path -> !path.equals(source))
                    .sorted(Comparator.comparing(path -> relative(source, path),
                        PluginPackageTest::compareUtf8))
                    .toList();
                for (var path : entries) {
                    var relative = source.relativize(path).toString().replace('\\', '/')
                        .getBytes(StandardCharsets.UTF_8);
                    digest.update((byte) (Files.isDirectory(path) ? 'D' : 'F'));
                    updateLength(digest, relative.length);
                    digest.update(relative);
                    if (Files.isRegularFile(path)) {
                        updateLength(digest, Files.size(path));
                        digest.update(Files.readAllBytes(path));
                    }
                }
            }
        }
        var result = HexFormat.of().formatHex(digest.digest());
        assertTrue(result.matches("[0-9a-f]{64}"));
        return result;
    }

    private static void updateLength(MessageDigest digest, long length) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(length).array());
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static int compareUtf8(String left, String right) {
        return Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8),
            right.getBytes(StandardCharsets.UTF_8));
    }
}
