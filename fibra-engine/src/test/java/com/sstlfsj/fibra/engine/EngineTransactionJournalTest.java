package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EngineTransactionJournalTest {
    @Test
    void roundTripsPreparedParticipantsAndEnforcesTransitions(@TempDir Path work)
        throws Exception {
        var journal = EngineTransactionJournal.preparing("tx-1")
            .prepared("deployment", "1.0.0", "0".repeat(64),
                List.of(new EngineTransactionJournal.Artifact(
                    "provider", true, "1".repeat(64), "2".repeat(64))),
                List.of(new EngineTransactionJournal.ConfigFile(
                    "fibra.yaml", true, "3".repeat(64), "4".repeat(64))));

        journal.write(work);

        assertEquals(journal, EngineTransactionJournal.read(work));
        assertEquals(EngineTransactionState.COMMITTING_ARTIFACTS,
            journal.advance(EngineTransactionState.COMMITTING_ARTIFACTS).state());
        assertThrows(IllegalStateException.class,
            () -> journal.advance(EngineTransactionState.VERIFYING));
    }

    @Test
    void rejectsCorruptOrDuplicateFields(@TempDir Path work) throws Exception {
        Files.writeString(work.resolve("journal.properties"), """
            transaction.id=tx
            transaction.id=duplicate
            state=PREPARING
            """);

        assertThrows(IllegalArgumentException.class,
            () -> EngineTransactionJournal.read(work));
    }
}
