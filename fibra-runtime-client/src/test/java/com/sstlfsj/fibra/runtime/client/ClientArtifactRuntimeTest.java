package com.sstlfsj.fibra.runtime.client;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.ManagedPluginPackage;
import com.sstlfsj.fibra.artifact.PluginPackage;
import com.sstlfsj.fibra.engine.ArtifactRuntime.ResourceState;
import com.sstlfsj.fibra.engine.DeploymentTarget;
import com.sstlfsj.fibra.engine.DeploymentTargetCompiler;
import com.sstlfsj.fibra.engine.PluginSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientArtifactRuntimeTest {
    @Test
    void preparesManagedClientPayloadAsStableDescriptors(@TempDir Path work)
        throws Exception {
        var source = packageWithClientPayload(work, """
            format: 1
            entryModule: index.js
            resources:
              - path: index.js
                digest: %s
                byteLength: %d
              - path: assets/message.txt
                digest: %s
                byteLength: %d
            """.formatted(digest("export default {};\n"),
            "export default {};\n".getBytes(StandardCharsets.UTF_8).length,
            digest("hello\n"), "hello\n".getBytes(StandardCharsets.UTF_8).length));
        var managed = ManagedPluginPackage.from(source,
            Map.of(new FacetId("web"), new ArtifactId("example-web")));
        var compiled = compiled(source, managed);
        var runtime = new ClientArtifactRuntime();

        runtime.probe(managed.facets().getFirst().facet()).block();
        var update = runtime.createUpdate(compiled);
        update.prepareAsync().block();

        var prepared = assertInstanceOf(ClientPreparedArtifact.class,
            update.preparedArtifacts().get(new ArtifactId("example-web")));
        assertEquals("index.js", prepared.entryModule());
        assertEquals(managed.facets().getFirst().facet().executionTarget(),
            prepared.executionTarget());
        assertEquals(List.of("client.web.module.blob.v1"),
            prepared.requiredCapabilities());
        assertEquals(List.of("assets/message.txt", "index.js"),
            prepared.resources().stream().map(resource -> resource.path()).toList());
        update.adopt();
        var entry = prepared.resources().stream()
            .filter(resource -> resource.path().equals("index.js")).findFirst().orElseThrow();
        assertEquals("export default {};\n", new String(runtime.read(
            new ArtifactId("example-web"), entry, 1024 * 1024),
            StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> runtime.read(
            new ArtifactId("example-web"), entry, entry.byteLength() - 1));
        update.closeAsync().block();
    }

    @Test
    void rejectsAnUnlistedPayloadFileBeforeClientExecutionExists(@TempDir Path work)
        throws Exception {
        var source = packageWithClientPayload(work, """
            format: 1
            entryModule: index.js
            resources:
              - path: index.js
                digest: %s
                byteLength: %d
            """.formatted(digest("export default {};\n"),
            "export default {};\n".getBytes(StandardCharsets.UTF_8).length));
        var managed = ManagedPluginPackage.from(source,
            Map.of(new FacetId("web"), new ArtifactId("example-web")));
        var update = new ClientArtifactRuntime().createUpdate(compiled(source, managed));

        assertThrows(IllegalArgumentException.class, () -> update.prepareAsync().block());
    }

    @Test
    void rejectsResourceDigestThatDoesNotMatchPayloadBytes(@TempDir Path work)
        throws Exception {
        var source = packageWithClientPayload(work, """
            format: 1
            entryModule: index.js
            resources:
              - path: index.js
                digest: %s
                byteLength: %d
              - path: assets/message.txt
                digest: %s
                byteLength: %d
            """.formatted("0".repeat(64),
            "export default {};\n".getBytes(StandardCharsets.UTF_8).length,
            digest("hello\n"), "hello\n".getBytes(StandardCharsets.UTF_8).length));
        var managed = ManagedPluginPackage.from(source,
            Map.of(new FacetId("web"), new ArtifactId("example-web")));
        var update = new ClientArtifactRuntime().createUpdate(compiled(source, managed));

        assertThrows(IllegalArgumentException.class, () -> update.prepareAsync().block());
    }

    @Test
    void rejectsDescriptorControlDataAndHostPaths(@TempDir Path work) throws Exception {
        var source = packageWithClientPayload(work, """
            format: 1
            entryModule: https://example.test/index.js
            resources:
              - path: https://example.test/index.js
                digest: %s
                byteLength: 0
            url: https://example.test/package
            """.formatted("0".repeat(64)));
        var managed = ManagedPluginPackage.from(source,
            Map.of(new FacetId("web"), new ArtifactId("example-web")));
        var update = new ClientArtifactRuntime().createUpdate(compiled(source, managed));

        assertThrows(IllegalArgumentException.class, () -> update.prepareAsync().block());
    }

    @Test
    void rechecksTheManagedPayloadDigestAtPrepareTime(@TempDir Path work)
        throws Exception {
        var source = packageWithClientPayload(work, """
            format: 1
            entryModule: index.js
            resources:
              - path: index.js
                digest: %s
                byteLength: %d
              - path: assets/message.txt
                digest: %s
                byteLength: %d
            """.formatted(digest("export default {};\n"),
            "export default {};\n".getBytes(StandardCharsets.UTF_8).length,
            digest("hello\n"), "hello\n".getBytes(StandardCharsets.UTF_8).length));
        var managed = ManagedPluginPackage.from(source,
            Map.of(new FacetId("web"), new ArtifactId("example-web")));
        Files.writeString(source.facets().getFirst().payload().resolve("index.js"),
            "export default { changed: true };\n");
        var update = new ClientArtifactRuntime().createUpdate(compiled(source, managed));

        assertThrows(IllegalArgumentException.class, () -> update.prepareAsync().block());
    }

    @Test
    void borrowsUnchangedPreparedArtifactsAcrossUpdates(@TempDir Path work)
        throws Exception {
        var source = packageWithClientPayload(work, """
            format: 1
            entryModule: index.js
            resources:
              - path: index.js
                digest: %s
                byteLength: %d
              - path: assets/message.txt
                digest: %s
                byteLength: %d
            """.formatted(digest("export default {};\n"),
            "export default {};\n".getBytes(StandardCharsets.UTF_8).length,
            digest("hello\n"), "hello\n".getBytes(StandardCharsets.UTF_8).length));
        var managed = ManagedPluginPackage.from(source,
            Map.of(new FacetId("web"), new ArtifactId("example-web")));
        var runtime = new ClientArtifactRuntime();
        var first = runtime.createUpdate(compiled(source, managed));
        first.prepareAsync().block();
        var firstPrepared = first.preparedArtifacts().get(new ArtifactId("example-web"));
        first.adopt();
        first.closeAsync().block();

        var second = runtime.createUpdate(compiled(source, managed));
        second.prepareAsync().block();

        assertSame(firstPrepared, second.preparedArtifacts().get(
            new ArtifactId("example-web")));
        assertEquals(List.of(ResourceState.ACTIVE), runtime.snapshot().resources().stream()
            .map(resource -> resource.state()).toList());
        second.closeAsync().block();
    }

    @Test
    void createsOnePendingUpdateUntilItsCloseReleasesTheRuntime(@TempDir Path work)
        throws Exception {
        var source = validPackage(work);
        var managed = ManagedPluginPackage.from(source,
            Map.of(new FacetId("web"), new ArtifactId("example-web")));
        var runtime = new ClientArtifactRuntime();
        var first = runtime.createUpdate(compiled(source, managed));

        assertThrows(IllegalStateException.class,
            () -> runtime.createUpdate(compiled(source, managed)));
        first.closeAsync().block();
        runtime.createUpdate(compiled(source, managed)).closeAsync().block();
    }

    @Test
    void excludesBorrowedArtifactsFromTheAffectedClosure(@TempDir Path work)
        throws Exception {
        var source = validPackage(work);
        var managed = ManagedPluginPackage.from(source,
            Map.of(new FacetId("web"), new ArtifactId("example-web")));
        var runtime = new ClientArtifactRuntime();
        var first = runtime.createUpdate(compiled(source, managed));
        first.prepareAsync().block();
        first.adopt();
        first.closeAsync().block();

        var unchanged = runtime.createUpdate(compiled(source, managed));

        assertEquals(Set.of(), unchanged.affectedArtifacts());
        unchanged.closeAsync().block();
    }

    @Test
    void snapshotReportsOnlyFreshPendingCandidateAsPrepared(@TempDir Path work)
        throws Exception {
        var firstSource = validPackage(work.resolve("first"));
        var secondSource = validPackage(work.resolve("second"), "export default { changed: true };\n");
        var firstManaged = ManagedPluginPackage.from(firstSource,
            Map.of(new FacetId("web"), new ArtifactId("example-web")));
        var secondManaged = ManagedPluginPackage.from(secondSource,
            Map.of(new FacetId("web"), new ArtifactId("example-web")));
        var runtime = new ClientArtifactRuntime();
        var first = runtime.createUpdate(compiled(firstSource, firstManaged));
        first.prepareAsync().block();
        first.adopt();
        first.closeAsync().block();

        var pending = runtime.createUpdate(compiled(secondSource, secondManaged));
        pending.prepareAsync().block();

        assertEquals(List.of(ResourceState.ACTIVE, ResourceState.PREPARED), runtime.snapshot()
            .resources().stream().map(resource -> resource.state()).toList());
        pending.closeAsync().block();
    }

    @Test
    void snapshotReportsReplacedResourceAsRetiredUntilPendingUpdateCloses(@TempDir Path work)
        throws Exception {
        var firstSource = validPackage(work.resolve("first"));
        var secondSource = validPackage(work.resolve("second"), "export default { changed: true };\n");
        var firstManaged = ManagedPluginPackage.from(firstSource,
            Map.of(new FacetId("web"), new ArtifactId("example-web")));
        var secondManaged = ManagedPluginPackage.from(secondSource,
            Map.of(new FacetId("web"), new ArtifactId("example-web")));
        var runtime = new ClientArtifactRuntime();
        var first = runtime.createUpdate(compiled(firstSource, firstManaged));
        first.prepareAsync().block();
        first.adopt();
        first.closeAsync().block();

        var pending = runtime.createUpdate(compiled(secondSource, secondManaged));
        pending.prepareAsync().block();
        pending.adopt();

        assertEquals(List.of(ResourceState.ACTIVE, ResourceState.RETIRED), runtime.snapshot()
            .resources().stream().map(resource -> resource.state()).toList());
        pending.closeAsync().block();
        assertEquals(List.of(ResourceState.ACTIVE), runtime.snapshot().resources().stream()
            .map(resource -> resource.state()).toList());
    }

    @Test
    void runtimeCloseClosesPreparedPendingCandidateBeforeClearingActive(@TempDir Path work)
        throws Exception {
        var firstSource = validPackage(work.resolve("first"));
        var secondSource = validPackage(work.resolve("second"), "export default { changed: true };\n");
        var firstManaged = ManagedPluginPackage.from(firstSource,
            Map.of(new FacetId("web"), new ArtifactId("example-web")));
        var secondManaged = ManagedPluginPackage.from(secondSource,
            Map.of(new FacetId("web"), new ArtifactId("example-web")));
        var runtime = new ClientArtifactRuntime();
        var first = runtime.createUpdate(compiled(firstSource, firstManaged));
        first.prepareAsync().block();
        first.adopt();
        first.closeAsync().block();
        var pending = runtime.createUpdate(compiled(secondSource, secondManaged));
        pending.prepareAsync().block();

        runtime.closeAsync().block();

        assertEquals(List.of(), runtime.snapshot().resources());
        assertThrows(IllegalStateException.class, () -> pending.prepareAsync().block());
        assertThrows(IllegalStateException.class, pending::preparedArtifacts);
    }

    private static PluginPackage validPackage(Path work) throws Exception {
        return validPackage(work, "export default {};\n");
    }

    private static PluginPackage validPackage(Path work, String entry) throws Exception {
        return packageWithClientPayload(work, """
            format: 1
            entryModule: index.js
            resources:
              - path: index.js
                digest: %s
                byteLength: %d
              - path: assets/message.txt
                digest: %s
                byteLength: %d
            """.formatted(digest(entry), entry.getBytes(StandardCharsets.UTF_8).length,
            digest("hello\n"), "hello\n".getBytes(StandardCharsets.UTF_8).length), entry);
    }

    private static List<DeploymentTargetCompiler.CompiledFacet> compiled(
        PluginPackage source, ManagedPluginPackage managed) {
        return new DeploymentTargetCompiler().compile(
            DeploymentTarget.of(1, List.of(new PluginSelection(source.pluginId(),
                source.packageDigest(), true)),
                new com.sstlfsj.fibra.config.DesiredInputGraph(List.of())),
            List.of(managed), List.of()).facets().values().stream().toList();
    }

    private static PluginPackage packageWithClientPayload(Path work, String descriptor)
        throws Exception {
        return packageWithClientPayload(work, descriptor, "export default {};\n");
    }

    private static PluginPackage packageWithClientPayload(Path work, String descriptor,
                                                          String entry) throws Exception {
        var root = work.resolve("package");
        var payload = root.resolve("web");
        Files.createDirectories(payload.resolve("assets"));
        Files.writeString(root.resolve("fibra-package.yaml"), """
            format: 1
            id: example
            version: 1.0.0
            facets:
              - id: web
                role: client
                runtime: client
                target: client:web
                payload: web
                dependencies: []
                capabilities: [client.web.module.blob.v1]
            """);
        Files.writeString(payload.resolve("fibra-client.yaml"), descriptor);
        Files.writeString(payload.resolve("index.js"), entry);
        Files.writeString(payload.resolve("assets/message.txt"), "hello\n");
        return PluginPackage.read(root);
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
