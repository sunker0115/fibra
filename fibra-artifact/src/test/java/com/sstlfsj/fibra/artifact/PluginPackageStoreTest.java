package com.sstlfsj.fibra.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginPackageStoreTest {
    private static final PluginId PLUGIN = new PluginId("example.tools");

    @Test
    void atomicallySavesOneMultiFacetPackageRecord(@TempDir Path work) throws Exception {
        var source = canonicalPackage(work.resolve("source"));
        var expectedRevision = PluginPackage.read(source).packageDigest();
        var storeRoot = work.resolve("store");

        try (var store = new PluginPackageStore(storeRoot);
             var transaction = store.prepareInstall(source)) {
            var candidate = transaction.candidate();
            assertEquals(ArtifactState.STAGED, candidate.state());
            assertEquals(expectedRevision, candidate.packageRevision());
            assertEquals(3, candidate.managedPackage().facets().size());
            assertTrue(store.history(PLUGIN).isEmpty());
            assertEquals(0, regularFileCount(storeRoot.resolve("records")));
            for (var facet : candidate.managedPackage().facets()) {
                assertEquals(expectedRevision + ":" + facet.facet().facetId(),
                    facet.artifactId().value());
                assertTrue(facet.facet().payload().startsWith(candidate.location()));
                assertTrue(Files.exists(facet.facet().payload()));
            }

            var installed = transaction.save();
            assertEquals(ArtifactState.INSTALLED, installed.state());
            assertEquals(installed, transaction.save());
            assertEquals(installed,
                store.find(PLUGIN, expectedRevision).orElseThrow());
            assertEquals(List.of(installed), store.history(PLUGIN));
            assertEquals(1, regularFileCount(storeRoot.resolve("records")));
            assertFalse(Files.exists(storeRoot.resolve("records/current")));
        }
    }

    @Test
    void rollbackNeverPublishesARecordAndIsIdempotent(@TempDir Path work)
        throws Exception {
        var source = canonicalPackage(work.resolve("source"));
        try (var store = new PluginPackageStore(work.resolve("store"))) {
            var transaction = store.prepareInstall(source);
            var immutableObject = transaction.candidate().location();

            transaction.rollback();
            transaction.rollback();
            transaction.close();

            assertTrue(store.history(PLUGIN).isEmpty());
            assertTrue(Files.isDirectory(immutableObject));
            assertThrows(IllegalStateException.class, transaction::save);
        }
    }

    @Test
    void repeatedRevisionReusesStableObjectIdsAndRecord(@TempDir Path work)
        throws Exception {
        var source = canonicalPackage(work.resolve("source"));
        try (var store = new PluginPackageStore(work.resolve("store"));
             var first = store.prepareInstall(source)) {
            var installed = first.save();
            try (var repeated = store.prepareInstall(source)) {
                assertEquals(installed.location(), repeated.candidate().location());
                assertEquals(installed.managedPackage().facets().stream()
                        .map(ManagedFacet::artifactId).toList(),
                    repeated.candidate().managedPackage().facets().stream()
                        .map(ManagedFacet::artifactId).toList());
                assertEquals(installed, repeated.save());
            }
            assertEquals(List.of(installed), store.history(PLUGIN));
        }
    }

    @Test
    void rejectsLegacyPackageWithoutPublishing(@TempDir Path work) throws Exception {
        var source = Files.createDirectory(work.resolve("legacy"));
        Files.writeString(source.resolve("plugin.properties"),
            "formatVersion=1\nruntime=java\npayload=plugin.jar\n");
        Files.writeString(source.resolve("plugin.jar"), "payload");
        var storeRoot = work.resolve("store");

        try (var store = new PluginPackageStore(storeRoot)) {
            var failure = assertThrows(ArtifactException.class,
                () -> store.prepareInstall(source));
            assertEquals(ArtifactPhase.VALIDATE, failure.phase());
            assertEquals(0, regularFileCount(storeRoot.resolve("records")));
        }
    }

    @Test
    void stagingMutationNeverPublishesARecord(@TempDir Path work) throws Exception {
        var source = canonicalPackage(work.resolve("source"));
        var storeRoot = work.resolve("store");
        var io = new PluginPackageStore.StorageIo() {
            @Override
            public void force(Path path) { }

            @Override
            public void copy(Path from, Path target) throws IOException {
                PluginPackageStore.StorageIo.super.copy(from, target);
                Files.writeString(target.resolve("client/index.mjs"),
                    "export default 'mutated';\n");
            }
        };

        try (var store = new PluginPackageStore(storeRoot, io)) {
            var failure = assertThrows(ArtifactException.class,
                () -> store.prepareInstall(source));
            assertEquals(ArtifactPhase.DIGEST, failure.phase());
            assertEquals(0, regularFileCount(storeRoot.resolve("records")));
            assertEquals(0, childCount(storeRoot.resolve("objects")));
        }
    }

    @Test
    void partialCopyNeverPublishesARecord(@TempDir Path work) throws Exception {
        var source = canonicalPackage(work.resolve("source"));
        var storeRoot = work.resolve("store");
        var io = new PluginPackageStore.StorageIo() {
            @Override
            public void force(Path path) { }

            @Override
            public void copy(Path from, Path target) throws IOException {
                Files.createDirectories(target);
                Files.copy(from.resolve(PluginPackage.MANIFEST),
                    target.resolve(PluginPackage.MANIFEST));
                throw new IOException("injected partial copy");
            }
        };

        try (var store = new PluginPackageStore(storeRoot, io)) {
            var failure = assertThrows(ArtifactException.class,
                () -> store.prepareInstall(source));
            assertEquals(ArtifactPhase.STAGE, failure.phase());
            assertEquals(0, regularFileCount(storeRoot.resolve("records")));
            assertEquals(0, childCount(storeRoot.resolve("objects")));
        }
    }

    @Test
    void recoversAnAbandonedPreparationWithoutPublishingIt(@TempDir Path work)
        throws Exception {
        var source = canonicalPackage(work.resolve("source"));
        var storeRoot = work.resolve("store");
        var first = new PluginPackageStore(storeRoot);
        var candidate = first.prepareInstall(source).candidate();
        first.close();

        try (var recovered = new PluginPackageStore(storeRoot)) {
            assertEquals(0, childCount(storeRoot.resolve("transactions")));
            assertTrue(recovered.history(PLUGIN).isEmpty());
            assertTrue(Files.isDirectory(candidate.location()));
        }
    }

    @Test
    void enforcesExclusiveProcessOwnership(@TempDir Path work) {
        var storeRoot = work.resolve("store");
        try (var owner = new PluginPackageStore(storeRoot)) {
            var failure = assertThrows(ArtifactException.class,
                () -> new PluginPackageStore(storeRoot));
            assertEquals(ArtifactPhase.RECOVER, failure.phase());
        }
    }

    @Test
    void rejectsUnknownStoredFieldsAndTamperedObjects(@TempDir Path work)
        throws Exception {
        var source = canonicalPackage(work.resolve("source"));
        var storeRoot = work.resolve("store");
        try (var store = new PluginPackageStore(storeRoot);
             var transaction = store.prepareInstall(source)) {
            var installed = transaction.save();
            var record = onlyRegularFile(storeRoot.resolve("records"));
            Files.writeString(record, "unknown=true\n", java.nio.file.StandardOpenOption.APPEND);
            assertRecoverFailure(() -> store.find(PLUGIN, installed.packageRevision()));
        }

        var secondRoot = work.resolve("second-store");
        try (var store = new PluginPackageStore(secondRoot);
             var transaction = store.prepareInstall(source)) {
            var installed = transaction.save();
            Files.writeString(installed.location().resolve("host/plugin.jar"), "tampered");
            assertRecoverFailure(() -> store.find(PLUGIN, installed.packageRevision()));
        }
    }

    @Test
    void rejectsInvalidStoredRecordFileNameAsRecoveryFailure(@TempDir Path work)
        throws Exception {
        var source = canonicalPackage(work.resolve("source"));
        var storeRoot = work.resolve("store");
        try (var store = new PluginPackageStore(storeRoot);
             var transaction = store.prepareInstall(source)) {
            transaction.save();
            var recordDirectory = onlyRegularFile(storeRoot.resolve("records")).getParent();
            Files.writeString(recordDirectory.resolve("invalid.properties"), "invalid=true\n");

            assertRecoverFailure(() -> store.history(PLUGIN));
        }
    }

    @Test
    void rejectsAnObjectDirectoryLinkOutsideTheStore(@TempDir Path work)
        throws Exception {
        var source = canonicalPackage(work.resolve("source"));
        var storeRoot = work.resolve("store");
        try (var store = new PluginPackageStore(storeRoot);
             var first = store.prepareInstall(source)) {
            var objectDirectory = first.candidate().location().getParent();
            first.rollback();
            var externalObject = work.resolve("external-object");
            Files.move(objectDirectory, externalObject);
            Files.createSymbolicLink(objectDirectory, externalObject);

            var failure = assertThrows(ArtifactException.class,
                () -> store.prepareInstall(source));
            assertEquals(ArtifactPhase.DIGEST, failure.phase());
            assertTrue(Files.isDirectory(externalObject.resolve("content")));
            assertTrue(store.history(PLUGIN).isEmpty());
        }
    }

    @Test
    void contentMutationBeforeSaveNeverPublishesARecord(@TempDir Path work)
        throws Exception {
        var source = canonicalPackage(work.resolve("source"));
        try (var store = new PluginPackageStore(work.resolve("store"));
             var transaction = store.prepareInstall(source)) {
            Files.writeString(transaction.candidate().location().resolve("client/index.mjs"),
                "export default 'tampered';\n");

            var failure = assertThrows(ArtifactException.class, transaction::save);
            assertEquals(ArtifactPhase.COMMIT, failure.phase());
            assertTrue(store.history(PLUGIN).isEmpty());
        }
    }

    @Test
    void forceFailureNeverPublishesARecord(@TempDir Path work) throws Exception {
        var source = canonicalPackage(work.resolve("source"));
        var io = new PluginPackageStore.StorageIo() {
            @Override
            public void force(Path path) throws IOException {
                if (path.getFileName() != null
                    && path.getFileName().toString().equals("content")) {
                    throw new IOException("injected force failure");
                }
            }
        };
        try (var store = new PluginPackageStore(work.resolve("store"), io);
             var transaction = store.prepareInstall(source)) {
            var failure = assertThrows(ArtifactException.class, transaction::save);
            assertEquals(ArtifactPhase.COMMIT, failure.phase());
            assertTrue(store.history(PLUGIN).isEmpty());
        }
    }

    @Test
    void invalidRecoveryJournalIsRetainedForInvestigation(@TempDir Path work)
        throws Exception {
        var storeRoot = work.resolve("store");
        new PluginPackageStore(storeRoot).close();
        var transaction = Files.createDirectory(
            storeRoot.resolve("transactions/abandoned"));
        var journal = transaction.resolve("transaction.properties");
        var values = new Properties();
        values.setProperty("pluginId", PLUGIN.value());
        values.setProperty("packageRevision", "../../outside");
        try (var output = Files.newOutputStream(journal)) {
            values.store(output, null);
        }

        var failure = assertThrows(ArtifactException.class,
            () -> new PluginPackageStore(storeRoot));
        assertEquals(ArtifactPhase.RECOVER, failure.phase());
        assertTrue(Files.exists(journal));
    }

    @Test
    void closeFailureRemainsVisibleAndReleasesOwnership(@TempDir Path work) {
        var ioFailure = new IOException("channel close completion failed");
        var closes = new AtomicInteger();
        var io = new PluginPackageStore.StorageIo() {
            @Override
            public void force(Path path) { }

            @Override
            public void close(FileChannel channel) throws IOException {
                PluginPackageStore.StorageIo.super.close(channel);
                closes.incrementAndGet();
                throw ioFailure;
            }
        };
        var storeRoot = work.resolve("store");
        var store = new PluginPackageStore(storeRoot, io);

        var first = assertThrows(ArtifactException.class, store::close);
        assertSame(ioFailure, first.getCause());
        assertSame(first, assertThrows(ArtifactException.class, store::close));
        assertEquals(1, closes.get());
        assertThrows(IllegalStateException.class, () -> store.history(PLUGIN));
        try (var reopened = new PluginPackageStore(storeRoot)) {
            assertTrue(reopened.history(PLUGIN).isEmpty());
        }
    }

    private static Path canonicalPackage(Path root) throws IOException {
        Files.createDirectory(root);
        Files.createDirectories(root.resolve("host"));
        Files.createDirectories(root.resolve("command"));
        Files.createDirectories(root.resolve("client"));
        Files.writeString(root.resolve("host/plugin.jar"), "host");
        Files.writeString(root.resolve("command/index.mjs"), "export default 'command';\n");
        Files.writeString(root.resolve("client/index.mjs"), "export default 'client';\n");
        Files.writeString(root.resolve(PluginPackage.MANIFEST), """
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
                capabilities:
                  - dom
            """);
        return root;
    }

    private static long regularFileCount(Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private static long childCount(Path root) throws IOException {
        try (var paths = Files.list(root)) {
            return paths.count();
        }
    }

    private static Path onlyRegularFile(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
    }

    private static void assertRecoverFailure(Runnable operation) {
        var failure = assertThrows(ArtifactException.class, operation::run);
        assertEquals(ArtifactPhase.RECOVER, failure.phase());
    }
}
