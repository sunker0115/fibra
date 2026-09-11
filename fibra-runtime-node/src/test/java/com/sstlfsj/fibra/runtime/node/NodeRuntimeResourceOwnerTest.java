package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactState;
import com.sstlfsj.fibra.bridge.ContributionCodec;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.engine.RuntimeResourceSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeRuntimeResourceOwnerTest {
    private static final ContributionKind<EchoDescriptor, String, String> ECHO =
        ContributionKind.remote("echo", EchoDescriptor.class, String.class, String.class,
            new EchoCodec());

    @Test
    void updatesOnlyChangedArtifactsAndReusesUnaffectedCatalogEntries(@TempDir Path work)
        throws Exception {
        var first = artifact(work, "first", "1");
        var second = artifact(work, "second", "1");
        var adapter = adapter(work);
        var owner = adapter.create();
        install(owner, List.of(first, second));
        var retained = owner.catalog().plugins().find("first").orElseThrow();
        var replaced = owner.catalog().plugins().find("second").orElseThrow();
        var revisedSecond = second.toBuilder().revision("2").build();

        var update = owner.createUpdate(List.of(first, revisedSecond));
        update.prepareAsync().block();

        assertEquals(List.of(new ArtifactId("second")), update.affectedArtifacts().stream()
            .sorted(java.util.Comparator.comparing(ArtifactId::value)).toList());
        assertSame(retained, update.catalog().plugins().find("first").orElseThrow());
        assertSame(replaced, owner.catalog().plugins().find("second").orElseThrow());
        assertNotSame(replaced, update.catalog().plugins().find("second").orElseThrow());
        assertEquals(List.of("node:second:2"), update.snapshot().resources().stream()
            .map(RuntimeResourceSnapshot.Resource::identity).toList());

        update.adopt();
        update.closeAsync().block();
        assertSame(retained, owner.catalog().plugins().find("first").orElseThrow());
        assertNotSame(replaced, owner.catalog().plugins().find("second").orElseThrow());
        owner.closeAsync().block();
    }

    @Test
    void removesAllNodeResourcesWhenTheTargetIsEmpty(@TempDir Path work) throws Exception {
        var artifact = artifact(work, "only", "1");
        var owner = adapter(work).create();
        install(owner, List.of(artifact));

        var update = owner.createUpdate(List.of());
        update.prepareAsync().block();
        update.adopt();
        update.closeAsync().block();

        assertTrue(owner.catalog().plugins().entries().isEmpty());
        assertTrue(owner.snapshot().resources().isEmpty());
        owner.closeAsync().block();
    }

    @Test
    void failedPreparationDoesNotReplaceTheCurrentCatalog(@TempDir Path work) throws Exception {
        var stable = artifact(work, "stable", "1");
        var invalid = invalidArtifact(work, "stable", "2");
        var owner = adapter(work).create();
        install(owner, List.of(stable));
        var current = owner.catalog().plugins().find("stable").orElseThrow();

        var update = owner.createUpdate(List.of(invalid));
        assertThrows(NodeRuntimeException.class, () -> update.prepareAsync().block());
        assertSame(current, owner.catalog().plugins().find("stable").orElseThrow());
        update.closeAsync().block();
        owner.closeAsync().block();
    }

    @Test
    void snapshotAndAdoptKeepResourceMetadataIdentityUniqueUnderConcurrency(@TempDir Path work)
        throws Exception {
        var artifact = artifact(work, "concurrent", "1");
        var owner = adapter(work).create();
        install(owner, List.of(artifact));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var active = true;
            for (var round = 0; round < 32; round++) {
                var update = owner.createUpdate(active ? List.of() : List.of(artifact));
                update.prepareAsync().block();
                var snapshotsStarted = new CountDownLatch(1);
                var snapshots = workers.submit(() -> {
                    snapshotsStarted.countDown();
                    for (var index = 0; index < 256; index++) {
                        assertMetadataIdentities(owner.snapshot());
                    }
                });
                assertTrue(snapshotsStarted.await(5, TimeUnit.SECONDS));
                workers.submit(update::adopt).get(5, TimeUnit.SECONDS);
                snapshots.get(5, TimeUnit.SECONDS);
                update.closeAsync().block();
                active = !active;
            }
        } finally {
            owner.closeAsync().block();
        }
    }

    private static void install(com.sstlfsj.fibra.engine.RuntimeResourceOwner owner,
                                List<ArtifactRecord> artifacts) {
        var update = owner.createUpdate(artifacts);
        update.prepareAsync().block();
        update.adopt();
        update.closeAsync().block();
    }

    private static void assertMetadataIdentities(RuntimeResourceSnapshot snapshot) {
        var resources = snapshot.resources();
        assertEquals(resources.size(), resources.stream()
            .map(RuntimeResourceSnapshot.Resource::identity).distinct().count());
        resources.forEach(resource -> assertEquals("node:" + resource.artifact().id().value()
            + ':' + resource.artifact().revision(), resource.identity()));
    }

    private static NodePluginRuntimeAdapter adapter(Path work) {
        return new NodePluginRuntimeAdapter(name -> "echo".equals(name)
            ? Optional.of(ECHO) : Optional.empty(),
            NodeRuntimeOptions.defaults(node(), work.resolve("sessions")));
    }

    private static ArtifactRecord artifact(Path work, String id, String revision) throws Exception {
        var root = work.resolve(id + '-' + revision);
        Files.createDirectories(root);
        Files.writeString(root.resolve("index.mjs"), "export {};\n");
        Files.writeString(root.resolve("fibra-plugin.yaml"), """
            id: %s
            version: 1.0.0
            protocol: 1
            entrypoint: index.mjs
            contributions:
              - name: say
                kind: echo
                schemaVersion: 1
                method: echo
                descriptor: { title: Echo }
            """.formatted(id));
        return record(id, revision, root);
    }

    private static ArtifactRecord invalidArtifact(Path work, String id, String revision)
        throws Exception {
        var root = work.resolve(id + '-' + revision + "-invalid");
        Files.createDirectories(root);
        Files.writeString(root.resolve("index.mjs"), "export {};\n");
        Files.writeString(root.resolve("fibra-plugin.yaml"), """
            id: %s
            version: 1.0.0
            protocol: 1
            entrypoint: index.mjs
            contributions:
              - name: say
                kind: missing
                schemaVersion: 1
                method: echo
                descriptor: { title: Echo }
            """.formatted(id));
        return record(id, revision, root);
    }

    private static ArtifactRecord record(String id, String revision, Path root) {
        return ArtifactRecord.builder().id(new ArtifactId(id))
            .runtimeId(NodePluginRuntimeAdapter.RUNTIME_ID).version("1.0.0")
            .checksum("checksum-" + revision).revision(revision).location(root)
            .state(ArtifactState.INSTALLED).updatedAt(Instant.EPOCH).build();
    }

    private static Path node() {
        return Path.of(System.getProperty("fibra.test.node", "node"));
    }

    private record EchoDescriptor(String title) {
    }

    private static final class EchoCodec implements ContributionCodec<EchoDescriptor, String, String> {
        @Override public int schemaVersion() { return 1; }
        @Override public EchoDescriptor decodeDescriptor(Object descriptor) {
            return new EchoDescriptor(((Map<?, ?>) descriptor).get("title").toString());
        }
        @Override public Object encodeInput(String input) { return input; }
        @Override public String decodeInput(Object input) { return input.toString(); }
        @Override public Object encodeOutput(String output) { return output; }
        @Override public String decodeOutput(Object output) { return output.toString(); }
    }
}
