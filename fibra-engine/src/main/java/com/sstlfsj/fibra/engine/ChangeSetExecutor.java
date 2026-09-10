package com.sstlfsj.fibra.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ChangeSetExecutor implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ChangeSetExecutor.class);
    private final TransactionJournal journal;
    private final ExecutorService laneExecutor = Executors.newSingleThreadExecutor(
        Thread.ofPlatform().name("fibra-engine-", 0).factory());
    private final Scheduler lane = Schedulers.fromExecutorService(laneExecutor);
    private final Sinks.Many<Operation> requests = Sinks.many().unicast()
        .onBackpressureBuffer();
    private final Disposable subscription;
    private volatile boolean acceptsMutations = true;
    private volatile boolean closed;
    private volatile TransactionState transactionState;

    ChangeSetExecutor(TransactionJournal journal) {
        this.journal = Objects.requireNonNull(journal, "journal");
        recoverCompletedDecisions();
        var unresolved = unresolved(journal.records());
        acceptsMutations = unresolved.isEmpty();
        subscription = requests.asFlux().publishOn(lane).concatMap(operation -> {
            if (operation instanceof Observation observation) {
                return Mono.fromRunnable(observation.action())
                    .onErrorResume(failure -> {
                        log.error("Engine observation failed", failure);
                        return Mono.empty();
                    });
            }
            var request = (Request) operation;
            return executeNow(request.changeSet())
                .doOnSuccess(request.result()::tryEmitValue)
                .doOnError(request.result()::tryEmitError)
                .onErrorResume(ignored -> Mono.empty());
        })
            .subscribe();
    }

    Mono<ChangeSetResult> execute(ChangeSet changeSet) {
        Objects.requireNonNull(changeSet, "changeSet");
        if (closed) {
            return Mono.error(new IllegalStateException("change set executor is closed"));
        }
        if (!acceptsMutations) {
            return Mono.error(new MutationGateClosedException());
        }
        var result = Sinks.<ChangeSetResult>one();
        var emitted = requests.tryEmitNext(new Request(changeSet, result));
        if (emitted.isFailure()) {
            return Mono.error(new IllegalStateException(
                "cannot enqueue change set: " + emitted));
        }
        return result.asMono();
    }

    boolean acceptsMutations() {
        return acceptsMutations && !closed;
    }

    TransactionState transactionState() {
        return transactionState;
    }

    List<TransactionRecord> records() {
        return List.copyOf(journal.records());
    }

    void observe(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (!closed) {
            requests.tryEmitNext(new Observation(action));
        }
    }

    void verifyRecovered() {
        var unresolved = unresolved(journal.records());
        if (!unresolved.isEmpty()) {
            throw new UnresolvedTransactionException(unresolved);
        }
    }

    private Mono<ChangeSetResult> executeNow(ChangeSet changeSet) {
        if (!acceptsMutations) {
            return Mono.error(new MutationGateClosedException());
        }
        var prepared = new ArrayList<PreparedChange>();
        var participantNames = changeSet.participants().stream()
            .map(ChangeParticipant::name).toList();
        var committed = new boolean[1];
        return Flux.fromIterable(changeSet.participants())
            .concatMap(participant -> Mono.defer(participant::prepare)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                    "participant returned no prepared change: " + participant.name())))
                .doOnNext(prepared::add))
            .then(Mono.defer(changeSet::verify))
            .then(record(changeSet, participantNames, TransactionState.PREPARED, null))
            .then(record(changeSet, participantNames, TransactionState.COMMITTING, null))
            .thenMany(Flux.defer(() -> Flux.fromIterable(prepared))
                .concatMap(change -> Mono.defer(change::commit)))
            .then(record(changeSet, participantNames, TransactionState.COMMITTED, null))
            .doOnSuccess(ignored -> committed[0] = true)
            .then(Mono.defer(changeSet::publish))
            .then(record(changeSet, participantNames, TransactionState.PUBLISHED, null))
            .then(Mono.defer(() -> retire(changeSet, participantNames, prepared)))
            .onErrorResume(failure -> committed[0]
                ? committedFailure(changeSet, participantNames, failure)
                : rollback(changeSet, participantNames, prepared, failure));
    }

    private Mono<ChangeSetResult> retire(ChangeSet changeSet, List<String> names,
                                         List<PreparedChange> prepared) {
        var warnings = new ArrayList<String>();
        var reverse = new ArrayList<>(prepared);
        Collections.reverse(reverse);
        return Flux.fromIterable(reverse)
            .concatMap(change -> Mono.defer(change::retire)
                .onErrorResume(failure -> {
                    warnings.add(change.name() + ": " + failure.getMessage());
                    return Mono.empty();
                }))
            .then(record(changeSet, names, TransactionState.RETIRED,
                warnings.isEmpty() ? null : String.join("; ", warnings)))
            .thenReturn(new ChangeSetResult(changeSet.id(), warnings));
    }

    private Mono<ChangeSetResult> rollback(ChangeSet changeSet, List<String> names,
                                           List<PreparedChange> prepared,
                                           Throwable failure) {
        var rollbackFailures = new ArrayList<Throwable>();
        var reverse = new ArrayList<>(prepared);
        Collections.reverse(reverse);
        return Flux.fromIterable(reverse)
            .concatMap(change -> Mono.defer(change::rollback)
                .onErrorResume(rollbackFailure -> {
                    rollbackFailures.add(rollbackFailure);
                    return Mono.empty();
                }))
            .then(Mono.defer(() -> {
                if (rollbackFailures.isEmpty()
                    && !(failure instanceof RecoveryUncertainException)) {
                    return record(changeSet, names, TransactionState.ROLLED_BACK,
                        failure.getMessage());
                }
                acceptsMutations = false;
                rollbackFailures.forEach(failure::addSuppressed);
                return record(changeSet, names, TransactionState.RECOVERY_FAILED,
                    failure.getMessage());
            }))
            .then(Mono.defer(() -> {
                if (prepared.isEmpty() && failure instanceof RuntimeException runtimeFailure) {
                    return Mono.error(runtimeFailure);
                }
                return Mono.error(new ChangeSetException(changeSet.id(),
                    "change set failed: " + changeSet.id(), failure));
            }));
    }

    private Mono<ChangeSetResult> committedFailure(ChangeSet changeSet, List<String> names,
                                                    Throwable failure) {
        acceptsMutations = false;
        return record(changeSet, names, TransactionState.RECOVERY_FAILED,
            failure.getMessage()).then(Mono.error(new ChangeSetException(changeSet.id(),
                "change set failed after commit point: " + changeSet.id(), failure)));
    }

    private Mono<Void> record(ChangeSet changeSet, List<String> participants,
                              TransactionState state, String detail) {
        return Mono.fromRunnable(() -> {
            journal.append(new TransactionRecord(
                changeSet.id(), state, participants, detail, Instant.now()));
            transactionState = state;
        });
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        requests.tryEmitComplete();
        subscription.dispose();
        lane.dispose();
        laneExecutor.shutdown();
        journal.close();
    }

    private static List<TransactionRecord> unresolved(List<TransactionRecord> records) {
        var latest = new LinkedHashMap<String, TransactionRecord>();
        records.forEach(record -> latest.put(record.transactionId(), record));
        return latest.values().stream().filter(record -> switch (record.state()) {
            case RETIRED, ROLLED_BACK -> false;
            case PREPARED, COMMITTING, COMMITTED, PUBLISHED, RECOVERY_FAILED -> true;
        }).toList();
    }

    private void recoverCompletedDecisions() {
        var latest = new LinkedHashMap<String, TransactionRecord>();
        journal.records().forEach(record -> latest.put(record.transactionId(), record));
        for (var record : latest.values()) {
            if (record.state() == TransactionState.PREPARED) {
                journal.append(new TransactionRecord(record.transactionId(),
                    TransactionState.ROLLED_BACK, record.participants(),
                    "recovered before commit on startup", Instant.now()));
            } else if (record.state() == TransactionState.COMMITTED
                || record.state() == TransactionState.PUBLISHED) {
                journal.append(new TransactionRecord(record.transactionId(),
                    TransactionState.RETIRED, record.participants(),
                    "completed durable decision on startup", Instant.now()));
            }
        }
    }

    private sealed interface Operation permits Request, Observation {
    }

    private record Request(ChangeSet changeSet, Sinks.One<ChangeSetResult> result)
        implements Operation {
    }

    private record Observation(Runnable action) implements Operation {
    }
}
