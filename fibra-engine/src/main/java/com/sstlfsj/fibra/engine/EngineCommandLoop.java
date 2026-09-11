package com.sstlfsj.fibra.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** 串行持有已接受命令，不让结果订阅者的取消传播到执行链。 */
final class EngineCommandLoop {
    private static final Logger log = LoggerFactory.getLogger(EngineCommandLoop.class);
    private final Scheduler lane = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(
        Thread.ofPlatform().name("fibra-engine-", 0).factory()));
    private final Sinks.Many<Request<?>> requests = Sinks.many().unicast().onBackpressureBuffer();
    private final Sinks.One<Void> drained = Sinks.one();
    private boolean stopping;
    private final Mono<Void> close = Mono.defer(this::quiesce)
        .then(Mono.<Void>fromRunnable(lane::dispose)).cache();

    EngineCommandLoop() {
        requests.asFlux().publishOn(lane).concatMap(this::execute)
            .subscribe(ignored -> { }, drained::tryEmitError, drained::tryEmitEmpty);
    }

    synchronized <T> Mono<T> submit(Supplier<Mono<T>> action) {
        Objects.requireNonNull(action, "action");
        if (stopping) return Mono.error(new IllegalStateException("engine command loop is closing"));
        var result = Sinks.<T>one();
        var emitted = requests.tryEmitNext(new Request<>(action, result));
        if (emitted.isFailure()) return Mono.error(new IllegalStateException("cannot enqueue command: " + emitted));
        return result.asMono().publishOn(Schedulers.boundedElastic());
    }

    <T> Mono<T> call(Supplier<Mono<T>> action) {
        return Mono.defer(action).subscribeOn(lane).publishOn(lane);
    }

    Mono<Void> nextTurn() { return Mono.<Void>empty().publishOn(lane); }

    synchronized void observe(Runnable action) {
        if (!stopping) lane.schedule(() -> {
            try { action.run(); }
            catch (RuntimeException failure) { log.error("Engine observation failed", failure); }
        });
    }

    synchronized Mono<Void> quiesce() {
        if (!stopping) {
            stopping = true;
            requests.tryEmitComplete();
        }
        return drained.asMono();
    }

    Mono<Void> closeAsync() { return close; }

    private <T> Mono<Void> execute(Request<T> request) {
        return call(request.action()).doOnSuccess(value -> {
            if (value == null) request.result().tryEmitEmpty();
            else request.result().tryEmitValue(value);
        }).doOnError(request.result()::tryEmitError).onErrorResume(ignored -> Mono.empty()).then();
    }

    private record Request<T>(Supplier<Mono<T>> action, Sinks.One<T> result) { }
}
