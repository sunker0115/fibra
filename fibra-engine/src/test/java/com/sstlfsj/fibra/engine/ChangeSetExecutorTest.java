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
    void interruptedCloseWaiterDoesNotPoisonTheSharedCloseResult() throws Exception {
        var release = reactor.core.publisher.Sinks.<Void>one();
        var preparing = new java.util.concurrent.CountDownLatch(1);
        var executor = new ChangeSetExecutor(new InMemoryTransactionJournal(), () -> { });
        var result = executor.execute(ChangeSet.builder("interrupted-close")
            .participant(new ChangeParticipant() {
                @Override public String name() { return "blocked"; }
                @Override public Mono<PreparedChange> prepare() {
                    preparing.countDown();
                    return release.asMono().then(Mono.defer(() ->
                        participant("blocked", new ArrayList<>()).prepare()));
                }
            }).build()).toFuture();
        assertTrue(preparing.await(5, java.util.concurrent.TimeUnit.SECONDS));
        var waiter = new java.util.concurrent.CompletableFuture<Throwable>();
        var thread = Thread.ofPlatform().daemon().start(() -> {
            try {
                executor.close();
                waiter.complete(null);
            } catch (Throwable failure) {
                waiter.complete(failure);
            }
        });
        try {
            reactor.core.publisher.Flux.interval(java.time.Duration.ofMillis(1))
                .filter(ignored -> !executor.acceptsMutations()).next()
                .block(java.time.Duration.ofSeconds(5));
            thread.interrupt();
            org.junit.jupiter.api.Assertions.assertNotNull(
                waiter.get(5, java.util.concurrent.TimeUnit.SECONDS));
            release.tryEmitEmpty();
            result.get(5, java.util.concurrent.TimeUnit.SECONDS);
            executor.close();
        } finally {
            release.tryEmitEmpty();
            thread.join(5000);
            try { executor.close(); } catch (RuntimeException expectedBeforeFix) { }
        }
    }

    @Test
    void projectsCommandResultsBeforeQuiescenceEvenWithoutAResultSubscriber() {
        var projected = new java.util.concurrent.atomic.AtomicInteger();
        var executor = new ChangeSetExecutor(new InMemoryTransactionJournal(), () -> { });
        var result = executor.execute(ChangeSet.builder("projection").build(), value -> {
            projected.incrementAndGet();
            return value.transactionId();
        });
        executor.close();
        assertEquals(1, projected.get());
        assertEquals("projection", result.block(java.time.Duration.ofSeconds(5)));
        assertEquals(1, projected.get());
    }

    @Test
    void resultCallbacksDoNotHoldTheCommandCompletionStack() {
        try (var executor = new ChangeSetExecutor(new InMemoryTransactionJournal(), () -> { })) {
            var preparing = new java.util.concurrent.CountDownLatch(1);
            var release = reactor.core.publisher.Sinks.<Void>one();
            var calls = new java.util.concurrent.CopyOnWriteArrayList<String>();
            var result = executor.execute(ChangeSet.builder("callback")
                .participant(new ChangeParticipant() {
                    @Override public String name() { return "blocked"; }
                    @Override public Mono<PreparedChange> prepare() {
                        preparing.countDown();
                        return release.asMono().then(Mono.defer(() -> participant("callback", calls).prepare()));
                    }
                }).build()).doOnSuccess(ignored -> executor.quiesce()
                    .block(java.time.Duration.ofMillis(200))).toFuture();
            try {
                assertTrue(preparing.await(5, java.util.concurrent.TimeUnit.SECONDS));
                release.tryEmitEmpty();
                assertEquals("callback", result.get(5, java.util.concurrent.TimeUnit.SECONDS).transactionId());
            } catch (Exception failure) {
                throw new AssertionError("result notification must not prevent executor quiescence", failure);
            } finally {
                release.tryEmitEmpty();
            }
        }
    }

    @Test
    void closeDrainsEveryAcceptedCommandWithoutCancellingPreparation() throws Exception {
        var calls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var preparing = new java.util.concurrent.CountDownLatch(1);
        var release = reactor.core.publisher.Sinks.<Void>one();
        var executor = new ChangeSetExecutor(new InMemoryTransactionJournal(), () -> { });
        try {
            var first = executor.execute(ChangeSet.builder("first")
                .participant(new ChangeParticipant() {
                    @Override public String name() { return "blocked"; }
                    @Override public Mono<PreparedChange> prepare() {
                        preparing.countDown();
                        return release.asMono().then(Mono.defer(() -> participant("first", calls).prepare()));
                    }
                }).build()).toFuture();
            assertTrue(preparing.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var second = executor.execute(ChangeSet.builder("second")
                .participant(participant("second", calls)).build()).toFuture();
            var closing = java.util.concurrent.CompletableFuture.runAsync(executor::close);
            reactor.core.publisher.Flux.interval(java.time.Duration.ofMillis(1))
                .filter(ignored -> !executor.acceptsMutations()).next()
                .block(java.time.Duration.ofSeconds(5));
            assertThrows(IllegalStateException.class,
                () -> executor.execute(ChangeSet.builder("late").build()).block());
            release.tryEmitEmpty();
            closing.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(first.isDone(), "accepted in-flight command needs a terminal result");
            assertTrue(second.isDone(), "accepted queued command needs a terminal result");
            assertEquals("first", first.get().transactionId());
            assertEquals("second", second.get().transactionId());
            assertEquals(List.of("prepare:first", "commit:first", "retire:first",
                "prepare:second", "commit:second", "retire:second"), calls);
        } finally {
            release.tryEmitEmpty();
            executor.close();
        }
    }

    @Test
    void uncertainPrepareDoesNotReleasePreviouslyPreparedPrerequisites() {
        var calls = new ArrayList<String>();
        var journal = new InMemoryTransactionJournal();
        try (var executor = new ChangeSetExecutor(journal, () -> { })) {
            assertThrows(ChangeSetException.class, () -> executor.execute(ChangeSet.builder("uncertain")
                .participant(participant("artifact", calls)).participant(new ChangeParticipant() {
                    @Override public String name() { return "generation"; }
                    @Override public Mono<PreparedChange> prepare() {
                        return Mono.error(new RecoveryUncertainException("candidate still open", null));
                    }
                }).build()).block());
            assertEquals(List.of("prepare:artifact"), calls);
            assertFalse(executor.acceptsMutations());
            assertEquals(TransactionState.RECOVERY_FAILED, journal.records().getLast().state());
        }
    }

    @Test
    void failedRollbackDoesNotReleaseEarlierPrerequisites() {
        var calls = new ArrayList<String>();
        var journal = new InMemoryTransactionJournal();
        try (var executor = new ChangeSetExecutor(journal, () -> { })) {
            assertThrows(ChangeSetException.class, () -> executor.execute(ChangeSet.builder("rollback-failure")
                .participant(participant("artifact", calls))
                .participant(participant("generation", calls, false, true))
                .verify(() -> Mono.error(new IllegalStateException("reject"))).build()).block());
            assertEquals(List.of("prepare:artifact", "prepare:generation", "rollback:generation"), calls);
            assertFalse(executor.acceptsMutations());
            assertEquals(TransactionState.RECOVERY_FAILED, journal.records().getLast().state());
        }
    }

    @Test
    void retirementFailureKeepsCommittedResultButStopsReclaimingPrerequisites() {
        var calls = new ArrayList<String>();
        var journal = new InMemoryTransactionJournal();
        var generation = new ChangeParticipant() {
            @Override public String name() { return "generation"; }
            @Override public Mono<PreparedChange> prepare() {
                return Mono.just(new PreparedChange() {
                    @Override public String name() { return "generation"; }
                    @Override public Mono<Void> commit() { return Mono.empty(); }
                    @Override public Mono<Void> rollback() { return Mono.empty(); }
                    @Override public Mono<Void> retire() {
                        return Mono.error(new IllegalStateException("runtime still open"));
                    }
                });
            }
        };
        try (var executor = new ChangeSetExecutor(journal, () -> { })) {
            var result = executor.execute(ChangeSet.builder("retirement-failure")
                .participant(participant("artifact", calls)).participant(generation)
                .publish(() -> action(calls, "published")).build()).block();

            assertFalse(calls.contains("retire:artifact"));
            assertTrue(calls.contains("published"));
            assertFalse(result.warnings().isEmpty());
            assertFalse(executor.acceptsMutations());
            assertEquals(TransactionState.RECOVERY_FAILED, journal.records().getLast().state());
        }
    }

    @Test
    void serializesPrepareCommitVerifyPublishAndRetire() {
        var calls = new ArrayList<String>();
        var journal = new InMemoryTransactionJournal();
        try (var executor = new ChangeSetExecutor(journal, () -> { })) {
            var result = executor.execute(ChangeSet.builder("change-1")
                .participant(participant("first", calls))
                .participant(participant("second", calls))
                .verify(() -> action(calls, "verify"))
                .publish(() -> action(calls, "publish"))
                .build()).block();

            assertEquals(List.of(
                "prepare:first", "prepare:second",
                "verify", "commit:first", "commit:second",
                "publish", "retire:second", "retire:first"), calls);
            assertEquals(List.of(TransactionState.PREPARED, TransactionState.COMMITTING,
                TransactionState.COMMITTED, TransactionState.PUBLISHED,
                TransactionState.RETIRED), journal.records().stream()
                .map(TransactionRecord::state).toList());
            assertEquals(List.of(), result.warnings());
            assertTrue(executor.acceptsMutations());
        }
    }

    @Test
    void verificationFailureRollsBackPreparedResourcesWithoutCommitting() {
        var calls = new ArrayList<String>();
        var journal = new InMemoryTransactionJournal();
        try (var executor = new ChangeSetExecutor(journal, () -> { })) {
            assertThrows(ChangeSetException.class, () -> executor.execute(
                ChangeSet.builder("rejected-candidate")
                    .participant(participant("candidate", calls))
                    .verify(() -> action(calls, "verify")
                        .then(Mono.error(new IllegalStateException("not publishable"))))
                    .build()).block());

            assertEquals(List.of("prepare:candidate", "verify", "rollback:candidate"),
                calls);
            assertEquals(List.of(TransactionState.ROLLED_BACK), journal.records().stream()
                .map(TransactionRecord::state).toList());
            assertTrue(executor.acceptsMutations());
        }
    }

    @Test
    void rollsBackInReverseAndPermanentlyClosesGateWhenRecoveryIsUncertain() {
        var calls = new ArrayList<String>();
        var journal = new InMemoryTransactionJournal();
        try (var executor = new ChangeSetExecutor(journal, () -> { })) {
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
        try (var executor = new ChangeSetExecutor(journal, () -> { })) {
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
