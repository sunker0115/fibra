package com.sstlfsj.fibra.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final Runnable settledObserver;
    private final ExecutorService laneExecutor = Executors.newSingleThreadExecutor(
        Thread.ofPlatform().name("fibra-engine-", 0).factory());
    private final Scheduler lane = Schedulers.fromExecutorService(laneExecutor);
    private final Sinks.Many<Operation> requests = Sinks.many().unicast()
        .onBackpressureBuffer();
    private final Sinks.One<Void> quiescent = Sinks.one();
    private final Mono<Void> closeSignal = Mono.defer(this::quiesce)
        .then(Mono.<Void>fromRunnable(this::closeInternal)).cache();
    private volatile boolean acceptsMutations = true;
    private volatile boolean stopping;
    private volatile boolean closed;
    private volatile TransactionState transactionState;

    ChangeSetExecutor(TransactionJournal journal, Runnable settledObserver) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.settledObserver = Objects.requireNonNull(settledObserver, "settledObserver");
        recoverCompletedDecisions();
        var unresolved = unresolved(journal.records());
        acceptsMutations = unresolved.isEmpty();
        requests.asFlux().publishOn(lane).concatMap(operation -> {
            if (operation instanceof Observation observation) {
                return Mono.fromRunnable(observation.action())
                    .onErrorResume(failure -> {
                        log.error("Engine observation failed", failure);
                        return Mono.empty();
                    });
            }
            return executeRequest((Request<?>) operation);
        })
            .subscribe(ignored -> { }, quiescent::tryEmitError, quiescent::tryEmitEmpty);
    }

    private <R> Mono<Void> executeRequest(Request<R> request) {
        return executeNow(request.changeSet())
                .doOnEach(signal -> {
                    if (signal.isOnNext() || signal.isOnError()) {
                        try {
                            settledObserver.run();
                        } catch (RuntimeException failure) {
                            log.error("Engine settled observation failed", failure);
                        }
                    }
                })
                .map(request.projection())
                .doOnNext(request.result()::tryEmitValue)
                .doOnError(request.result()::tryEmitError)
                .onErrorResume(ignored -> Mono.empty()).then();
    }

    Mono<ChangeSetResult> execute(ChangeSet changeSet) {
        return execute(changeSet, java.util.function.Function.identity());
    }

    synchronized <R> Mono<R> execute(ChangeSet changeSet,
                                    java.util.function.Function<ChangeSetResult, R> projection) {
        Objects.requireNonNull(changeSet, "changeSet");
        Objects.requireNonNull(projection, "projection");
        if (stopping || closed) {
            return Mono.error(new IllegalStateException("change set executor is closed"));
        }
        if (!acceptsMutations) {
            return Mono.error(new MutationGateClosedException());
        }
        var result = Sinks.<R>one();
        var emitted = requests.tryEmitNext(new Request<>(changeSet, projection, result));
        if (emitted.isFailure()) {
            return Mono.error(new IllegalStateException(
                "cannot enqueue change set: " + emitted));
        }
        return result.asMono().publishOn(Schedulers.boundedElastic());
    }

    boolean acceptsMutations() {
        return acceptsMutations && !stopping && !closed;
    }

    TransactionState transactionState() {
        return transactionState;
    }

    List<TransactionRecord> records() {
        return List.copyOf(journal.records());
    }

    synchronized void observe(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (!stopping && !closed) {
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
        var reverse = new ArrayList<>(prepared);
        Collections.reverse(reverse);
        return Flux.fromIterable(reverse)
            .concatMap(change -> Mono.defer(change::retire))
            .then(record(changeSet, names, TransactionState.RETIRED, null))
            .thenReturn(new ChangeSetResult(changeSet.id(), List.of()))
            .onErrorResume(failure -> {
                acceptsMutations = false;
                var warning = "retirement incomplete: " + failure.getMessage();
                return record(changeSet, names, TransactionState.RECOVERY_FAILED, warning)
                    .thenReturn(new ChangeSetResult(changeSet.id(), List.of(warning)));
            });
    }

    private Mono<ChangeSetResult> rollback(ChangeSet changeSet, List<String> names,
                                           List<PreparedChange> prepared,
                                           Throwable failure) {
        var rollbackFailures = new ArrayList<Throwable>();
        var reverse = new ArrayList<>(prepared);
        Collections.reverse(reverse);
        var uncertain = failure instanceof RecoveryUncertainException;
        if (uncertain) acceptsMutations = false;
        return (uncertain ? Mono.<Void>empty() : Flux.fromIterable(reverse)
            .concatMap(change -> Mono.defer(change::rollback)).then())
            .onErrorResume(rollbackFailure -> {
                acceptsMutations = false;
                rollbackFailures.add(rollbackFailure);
                return Mono.empty();
            })
            .then(Mono.defer(() -> {
                if (rollbackFailures.isEmpty()
                    && !uncertain) {
                    return record(changeSet, names, TransactionState.ROLLED_BACK,
                        failure.getMessage());
                }
                acceptsMutations = false;
                rollbackFailures.stream().filter(value -> value != failure).forEach(failure::addSuppressed);
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
        closeSignal.block();
    }

    Mono<Void> closeAsync() {
        return closeSignal;
    }

    synchronized Mono<Void> quiesce() {
        if (!stopping) {
            stopping = true;
            requests.tryEmitComplete();
        }
        return quiescent.asMono();
    }

    private void closeInternal() {
        closed = true;
        try {
            journal.close();
        } finally {
            laneExecutor.shutdown();
        }
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

    private record Request<R>(ChangeSet changeSet,
                              java.util.function.Function<ChangeSetResult, R> projection,
                              Sinks.One<R> result)
        implements Operation {
    }

    private record Observation(Runnable action) implements Operation {
    }
}
