package com.sstlfsj.fibra.loader.pf4j;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginTransactionJournalTest {

    @Test
    void writesReadsAndAdvancesAStableJournal(@TempDir Path work) throws Exception {
        var tx = Files.createDirectory(work.resolve("tx-1"));
        var prepared = PluginTransactionJournal.prepared("tx-1", List.of(
            new PluginTransactionJournal.Artifact("consumer", true, "old-c", "new-c"),
            new PluginTransactionJournal.Artifact("provider", false, null, "new-p")
        ));

        prepared.write(tx);

        assertEquals(prepared, PluginTransactionJournal.read(tx));
        var text = Files.readString(tx.resolve("journal.properties"));
        assertFalse(text.startsWith("#"));
        assertTrue(text.indexOf("artifact.0.id=consumer")
            < text.indexOf("artifact.1.id=provider"));

        var installing = prepared.advance(PluginTransactionState.INSTALLING);
        installing.write(tx);
        assertEquals(PluginTransactionState.INSTALLING,
            PluginTransactionJournal.read(tx).state());
        var cleanup = installing.markRollbackCleanup();
        cleanup.write(tx);
        assertEquals(PluginTransactionJournal.CleanupOutcome.ROLLBACK,
            PluginTransactionJournal.read(tx).cleanupOutcome());
        assertThrows(IllegalStateException.class,
            () -> installing.advance(PluginTransactionState.COMMITTED));
    }

    @Test
    void rejectsUnsortedDuplicateOrIncompleteArtifacts(@TempDir Path work) throws Exception {
        assertThrows(IllegalArgumentException.class,
            () -> PluginTransactionJournal.prepared("tx", List.of(
                new PluginTransactionJournal.Artifact("z", false, null, "new-z"),
                new PluginTransactionJournal.Artifact("a", false, null, "new-a")
            )));
        assertThrows(IllegalArgumentException.class,
            () -> PluginTransactionJournal.prepared("tx", List.of(
                new PluginTransactionJournal.Artifact("a", false, null, "new-a"),
                new PluginTransactionJournal.Artifact("a", false, null, "new-a")
            )));

        var tx = Files.createDirectory(work.resolve("broken"));
        Files.writeString(tx.resolve("journal.properties"), """
            transaction.id=broken
            state=PREPARED
            artifact.count=1
            artifact.0.id=fixture
            artifact.0.old.exists=true
            artifact.0.new.digest=new
            """);

        assertThrows(IllegalArgumentException.class,
            () -> PluginTransactionJournal.read(tx));
    }
}
