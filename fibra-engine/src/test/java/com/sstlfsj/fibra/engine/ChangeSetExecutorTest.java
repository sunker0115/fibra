package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeSetExecutorTest {
    @Test
    void serializesPrepareCommitVerifyPublishAndRetire() {
        var calls = new ArrayList<String>();
        var journal = new InMemoryTransactionJournal();
        try (var executor = new ChangeSetExecutor(journal)) {
            var result = executor.execute(ChangeSet.builder("change-1")
                .participant(participant("first", calls))
                .participant(participant("second", calls))
                .verify(() -> action(calls, "verify"))
                .publish(() -> action(calls, "publish"))
                .build()).block();

            assertEquals(List.of(
                "prepare:first", "prepare:second",
                "commit:first", "commit:second",
                "verify", "publish", "retire:second", "retire:first"), calls);
            assertEquals(List.of(TransactionState.PREPARED, TransactionState.COMMITTING,
                TransactionState.COMMITTED, TransactionState.PUBLISHED,
                TransactionState.RETIRED), journal.records().stream()
                .map(TransactionRecord::state).toList());
            assertEquals(List.of(), result.warnings());
            assertTrue(executor.acceptsMutations());
        }
    }

    @Test
    void rollsBackInReverseAndPermanentlyClosesGateWhenRecoveryIsUncertain() {
        var calls = new ArrayList<String>();
        var journal = new InMemoryTransactionJournal();
        try (var executor = new ChangeSetExecutor(journal)) {
            var first = participant("first", calls, false, true);
            var second = participant("second", calls, true, false);

            assertThrows(ChangeSetException.class, () -> executor.execute(
                ChangeSet.builder("broken").participant(first).participant(second)
                    .build()).block());

            assertEquals(List.of("prepare:first", "prepare:second",
                "commit:first", "commit:second", "rollback:second", "rollback:first"),
                calls);
            assertFalse(executor.acceptsMutations());
            assertEquals(TransactionState.RECOVERY_FAILED,
                journal.records().getLast().state());
            assertThrows(MutationGateClosedException.class,
                () -> executor.execute(ChangeSet.builder("later").build()).block());
        }
    }

    @Test
    void compensatesAPartialCommitAndKeepsTheGateOpenWhenRollbackCompletes() {
        var calls = new ArrayList<String>();
        var journal = new InMemoryTransactionJournal();
        try (var executor = new ChangeSetExecutor(journal)) {
            assertThrows(ChangeSetException.class, () -> executor.execute(
                ChangeSet.builder("partial")
                    .participant(participant("first", calls, false, false))
                    .participant(participant("second", calls, true, false))
                    .build()).block());

            assertEquals(List.of("prepare:first", "prepare:second",
                "commit:first", "commit:second", "rollback:second", "rollback:first"),
                calls);
            assertTrue(executor.acceptsMutations());
            assertEquals(TransactionState.ROLLED_BACK,
                journal.records().getLast().state());
        }
    }

    private static ChangeParticipant participant(String name, List<String> calls) {
        return participant(name, calls, false, false);
    }

    private static ChangeParticipant participant(String name, List<String> calls,
                                                 boolean commitFails,
                                                 boolean rollbackFails) {
        return new ChangeParticipant() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Mono<PreparedChange> prepare() {
                calls.add("prepare:" + name);
                return Mono.just(new PreparedChange() {
                    @Override
                    public String name() {
                        return name;
                    }

                    @Override
                    public Mono<Void> commit() {
                        calls.add("commit:" + name);
                        return commitFails ? Mono.error(new IllegalStateException("commit"))
                            : Mono.empty();
                    }

                    @Override
                    public Mono<Void> rollback() {
                        calls.add("rollback:" + name);
                        return rollbackFails ? Mono.error(new IllegalStateException("rollback"))
                            : Mono.empty();
                    }

                    @Override
                    public Mono<Void> retire() {
                        calls.add("retire:" + name);
                        return Mono.empty();
                    }
                });
            }
        };
    }

    private static Mono<Void> action(List<String> calls, String value) {
        calls.add(value);
        return Mono.empty();
    }
}
