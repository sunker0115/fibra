package com.sstlfsj.fibra.loader.pf4j;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginCrashRecoveryTest {

    @Test
    void removesPreflightGarbageAndEmptyFormalTransactions(@TempDir Path plugins)
        throws Exception {
        var preflight = Files.createDirectories(plugins.resolve(".fibra-preflight/abandoned"));
        Files.writeString(preflight.resolve("candidate.zip"), "garbage");
        var emptyTx = Files.createDirectories(plugins.resolve(".fibra-transactions/empty"));

        new PluginCrashRecovery(plugins).recover();

        assertFalse(Files.exists(plugins.resolve(".fibra-preflight")));
        assertFalse(Files.exists(emptyTx));
    }

    @Test
    void rejectsFormalTransactionWithoutJournalButWithPayload(@TempDir Path plugins)
        throws Exception {
        packageDirectory(plugins.resolve(".fibra-transactions/tx/previous/fixture"),
            "fixture", "1.0.0");

        var error = assertThrows(FibraArtifactException.class,
            () -> new PluginCrashRecovery(plugins).recover());

        assertEquals(FibraArtifactErrorStage.ROLLBACK, error.stage());
        assertTrue(Files.exists(plugins.resolve(
            ".fibra-transactions/tx/previous/fixture")));
    }

    @Test
    void installingRecoveryRestoresOldDirectoryAndRemovesNewInstall(@TempDir Path plugins)
        throws Exception {
        var previous = packageDirectory(
            plugins.resolve(".fibra-transactions/tx/previous/fixture"),
            "fixture", "1.0.0");
        var installed = packageDirectory(plugins.resolve("fixture"), "fixture", "2.0.0");
        var oldDigest = digest(previous);
        var newDigest = digest(installed);
        writeJournal(plugins, PluginTransactionState.INSTALLING,
            new PluginTransactionJournal.Artifact("fixture", true, oldDigest, newDigest));

        new PluginCrashRecovery(plugins).recover();

        assertEquals(oldDigest, digest(plugins.resolve("fixture")));
        assertFalse(Files.exists(plugins.resolve(".fibra-transactions/tx")));
    }

    @Test
    void applyingRecoveryWithdrawsAPluginThatDidNotExistBefore(@TempDir Path plugins)
        throws Exception {
        var installed = packageDirectory(plugins.resolve("new-plugin"),
            "new-plugin", "1.0.0");
        writeJournal(plugins, PluginTransactionState.APPLYING,
            new PluginTransactionJournal.Artifact("new-plugin", false, null,
                digest(installed)));

        new PluginCrashRecovery(plugins).recover();

        assertFalse(Files.exists(plugins.resolve("new-plugin")));
        assertFalse(Files.exists(plugins.resolve(".fibra-transactions/tx")));
    }

    @Test
    void committedRecoveryKeepsNewGraphAndFinishesJournalLastCleanup(@TempDir Path plugins)
        throws Exception {
        var installed = packageDirectory(plugins.resolve("fixture"), "fixture", "2.0.0");
        var previous = packageDirectory(
            plugins.resolve(".fibra-transactions/tx/previous/fixture"),
            "fixture", "1.0.0");
        writeJournal(plugins, PluginTransactionState.COMMITTED,
            new PluginTransactionJournal.Artifact("fixture", true, digest(previous),
                digest(installed)));

        new PluginCrashRecovery(plugins).recover();

        assertEquals("2.0.0", version(plugins.resolve("fixture")));
        assertFalse(Files.exists(plugins.resolve(".fibra-transactions/tx")));
    }

    @Test
    void rollbackCleanupMarkerMakesPayloadDeletionCrashRepeatable(@TempDir Path plugins)
        throws Exception {
        var installed = packageDirectory(plugins.resolve("fixture"), "fixture", "1.0.0");
        var tx = Files.createDirectories(plugins.resolve(".fibra-transactions/tx"));
        var journal = PluginTransactionJournal.prepared("tx", List.of(
                new PluginTransactionJournal.Artifact("fixture", true, digest(installed),
                    "new-digest")))
            .advance(PluginTransactionState.INSTALLING)
            .markRollbackCleanup();
        journal.write(tx);

        new PluginCrashRecovery(plugins).recover();

        assertEquals("1.0.0", version(installed));
        assertFalse(Files.exists(tx));
    }

    @Test
    void rejectsAmbiguousNewDirectoryLocationsWithoutDeletingEvidence(@TempDir Path plugins)
        throws Exception {
        var previous = packageDirectory(
            plugins.resolve(".fibra-transactions/tx/previous/fixture"),
            "fixture", "1.0.0");
        var installed = packageDirectory(plugins.resolve("fixture"), "fixture", "2.0.0");
        var next = packageDirectory(
            plugins.resolve(".fibra-transactions/tx/next/fixture"),
            "fixture", "2.0.0");
        writeJournal(plugins, PluginTransactionState.INSTALLING,
            new PluginTransactionJournal.Artifact("fixture", true, digest(previous),
                digest(installed)));

        var error = assertThrows(FibraArtifactException.class,
            () -> new PluginCrashRecovery(plugins).recover());

        assertEquals(FibraArtifactErrorStage.ROLLBACK, error.stage());
        assertTrue(Files.exists(installed));
        assertTrue(Files.exists(next));
        assertTrue(Files.exists(previous));
    }

    private static void writeJournal(Path plugins, PluginTransactionState state,
                                     PluginTransactionJournal.Artifact... artifacts)
        throws IOException {
        var tx = Files.createDirectories(plugins.resolve(".fibra-transactions/tx"));
        var journal = PluginTransactionJournal.prepared("tx", List.of(artifacts));
        while (journal.state() != state) {
            journal = journal.advance(journal.state().next());
        }
        journal.write(tx);
    }

    private static Path packageDirectory(Path root, String id, String version)
        throws IOException {
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("plugin.properties"), """
            plugin.id=%s
            plugin.version=%s
            """.formatted(id, version));
        try (var output = new JarOutputStream(Files.newOutputStream(
            root.resolve("lib").resolve(id + '-' + version + ".jar")))) {
            // An empty contract-only main JAR is a valid package fixture.
        }
        return root;
    }

    private static String digest(Path root) {
        return new PluginPackageInspector().inspectDirectory(root).digest();
    }

    private static String version(Path root) throws IOException {
        var properties = new java.util.Properties();
        try (var input = Files.newInputStream(root.resolve("plugin.properties"))) {
            properties.load(input);
        }
        return properties.getProperty("plugin.version");
    }
}
