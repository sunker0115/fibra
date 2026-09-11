package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileTransactionJournalTest {
    @Test
    void persistsOrderedRecordsAcrossProcessOwnership(@TempDir Path work) {
        var first = new TransactionRecord("change-1", TransactionState.PREPARED,
            List.of("artifact", "core"), null, Instant.parse("2026-09-08T00:00:00Z"));
        var second = new TransactionRecord("change-1", TransactionState.ROLLED_BACK,
            List.of("artifact", "core"), "failure", Instant.parse("2026-09-08T00:00:01Z"));

        try (var journal = new FileTransactionJournal(work)) {
            journal.append(first);
            journal.append(second);
            assertThrows(TransactionJournalException.class,
                () -> new FileTransactionJournal(work));
        }
        try (var recovered = new FileTransactionJournal(work)) {
            assertEquals(List.of(first, second), recovered.records());
        }
    }

    @Test
    void committedDurableTransactionCompletesRecoveryOnStartup(
        @TempDir Path work) {
        try (var journal = new FileTransactionJournal(work)) {
            journal.append(new TransactionRecord("change-1", TransactionState.COMMITTED,
                List.of("artifact"), null, Instant.now()));
        }

        var journal = new FileTransactionJournal(work);
        try (var executor = new ChangeSetExecutor(journal, () -> { })) {
            executor.verifyRecovered();
            assertEquals(TransactionState.RETIRED,
                journal.records().getLast().state());
        }
    }

    @Test
    void indeterminateCommitClosesAReplacementExecutorsGate(@TempDir Path work) {
        try (var journal = new FileTransactionJournal(work)) {
            journal.append(new TransactionRecord("change-1", TransactionState.COMMITTING,
                List.of("artifact"), null, Instant.now()));
        }

        var journal = new FileTransactionJournal(work);
        try (var executor = new ChangeSetExecutor(journal, () -> { })) {
            assertThrows(UnresolvedTransactionException.class, executor::verifyRecovered);
            assertThrows(MutationGateClosedException.class, () -> executor.execute(
                ChangeSet.builder("change-2").build()).block());
        }
    }
}
