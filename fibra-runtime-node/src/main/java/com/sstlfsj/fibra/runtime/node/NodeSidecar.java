package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.CancellationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Owns one process session and its single, asynchronous cleanup barrier. */
final class NodeSidecar implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(NodeSidecar.class);
    private final NodeRuntimeOptions options;
    private final NodeProcessUnit processUnit;
    private final NodeRpcChannel rpc;
    private final ScheduledExecutorService heartbeats;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean handshakeComplete = new AtomicBoolean();
    private final AtomicReference<NodeRpcException> failure = new AtomicReference<>();
    private final CompletableFuture<Void> shutdownCompletion = new CompletableFuture<>();
    private final CompletableFuture<Void> stderrCompletion = new CompletableFuture<>();
    private final Sinks.One<Void> termination = Sinks.one();

    private NodeSidecar(NodeRuntimeOptions options, NodeProcessUnit processUnit,
                        Runnable disableRequest, ScheduledExecutorService scheduler) {
        this.options = options;
        this.processUnit = processUnit;
        heartbeats = Objects.requireNonNull(scheduler, "scheduler");
        rpc = new NodeRpcChannel(options, processUnit.output(), processUnit.input(), scheduler,
            this::isAlive, handshakeComplete::get, () ->
                Schedulers.boundedElastic().schedule(() -> {
                    try {
                        disableRequest.run();
                    } catch (Throwable cause) {
                        fail(cause);
                    }
                }), this::fail);
        rpc.start();
        Thread.ofPlatform().daemon().name("fibra-node-stderr").start(this::readStderr);
        processUnit.onExit().thenRun(() -> rpc.outputCompletion().thenRun(() -> {
            if (!closing.get()) {
                fail(new NodeRpcException(NodeRpcPhase.EXIT,
                    "Node sidecar exited unexpectedly with code " + processUnit.exitValue()));
            }
        }));
    }

    static Mono<NodeSidecar> start(Path entrypoint, NodeRuntimeOptions options,
                                   Runnable disableRequest) {
        return start(entrypoint, options, disableRequest, null);
    }

    static Mono<NodeSidecar> start(Path entrypoint, NodeRuntimeOptions options,
                                   Runnable disableRequest, ScheduledExecutorService scheduler) {
        return Mono.usingWhen(Mono.fromCallable(() -> launch(entrypoint, options, disableRequest, scheduler)),
            sidecar -> sidecar.initialize().thenReturn(sidecar),
            sidecar -> Mono.empty(),
            (sidecar, failure) -> sidecar.closeAsync(),
            NodeSidecar::closeAsync);
    }

    static NodeSidecar launch(Path entrypoint, NodeRuntimeOptions options, Runnable disableRequest) {
        return launch(entrypoint, options, disableRequest, null);
    }

    private static NodeSidecar launch(Path entrypoint, NodeRuntimeOptions options,
                                      Runnable disableRequest, ScheduledExecutorService scheduler) {
        Objects.requireNonNull(entrypoint, "entrypoint");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(disableRequest, "disableRequest");
        return new NodeSidecar(options, NodeProcessUnit.launch(entrypoint, options),
            disableRequest, scheduler == null ? requestScheduler() : scheduler);
    }

    Mono<Void> initialize() {
        return request("fibra.handshake", Map.of("protocol", 1), options.handshakeTimeout())
            .flatMap(result -> {
                if (!(result instanceof Map<?, ?> values)
                    || !(values.get("protocol") instanceof Number protocol)
                    || protocol.intValue() != 1) {
                    return Mono.error(new NodeRpcException(NodeRpcPhase.HANDSHAKE,
                        "Node sidecar returned an incompatible handshake"));
                }
                handshakeComplete.set(true);
                return Mono.empty();
            }).onErrorMap(failure -> failure instanceof NodeRpcException nodeFailure
                && nodeFailure.phase() == NodeRpcPhase.HANDSHAKE
                ? failure : new NodeRpcException(NodeRpcPhase.HANDSHAKE,
                "Node sidecar handshake failed", failure))
            .then(Mono.fromRunnable(this::startHeartbeat));
    }

    Mono<Object> request(String method, Object parameters) {
        return rpc.request(method, parameters);
    }

    Mono<Object> request(String method, Object parameters, Duration timeout) {
        return rpc.request(method, parameters, timeout);
    }

    NodeRpcChannel.Request beginRequest(String method, Object parameters, CancellationToken cancellation) {
        return rpc.beginRequest(method, parameters, cancellation);
    }

    Mono<Void> termination() {
        return termination.asMono().publishOn(Schedulers.boundedElastic());
    }

    boolean isAlive() {
        return processUnit.isAlive() && !closing.get();
    }

    Mono<Void> closeAsync() {
        return Mono.defer(() -> {
            terminate();
            return Mono.fromFuture(shutdownCompletion, true);
        });
    }

    @Override
    public void close() {
        closeAsync().block();
    }

    private void fail(Throwable cause) {
        var exception = cause instanceof NodeRpcException nodeFailure
            ? nodeFailure : new NodeRpcException(NodeRpcPhase.PROTOCOL, "Node sidecar failed", cause);
        failure.compareAndSet(null, exception);
        terminate();
    }

    private void terminate() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        heartbeats.shutdown();
        rpc.stopWrites();
        Thread.ofPlatform().daemon().name("fibra-node-close").start(this::finishClose);
    }

    private void finishClose() {
        NodeRpcException cleanupFailure = null;
        try {
            processUnit.close();
        } catch (NodeRpcException cause) {
            cleanupFailure = cause;
            var original = failure.get();
            if (original != null && original != cause) {
                cause.addSuppressed(original);
            }
        }
        if (cleanupFailure == null) {
            // Confirmed process exit allows draining the complete tail without truncation.
            rpc.outputCompletion().join();
            rpc.writeCompletion().join();
            stderrCompletion.join();
        }
        var terminal = cleanupFailure != null ? cleanupFailure : failure.get();
        rpc.settle(terminal == null
            ? new NodeRpcException(NodeRpcPhase.TERMINATE, "Node sidecar was closed") : terminal,
            cleanupFailure == null);
        if (terminal == null) {
            termination.tryEmitEmpty();
        } else {
            termination.tryEmitError(terminal);
        }
        if (cleanupFailure == null) {
            shutdownCompletion.complete(null);
        } else {
            shutdownCompletion.completeExceptionally(cleanupFailure);
        }
    }

    private static ScheduledExecutorService requestScheduler() {
        var scheduler = new ScheduledThreadPoolExecutor(1, runnable ->
            Thread.ofPlatform().daemon().name("fibra-node-scheduler").unstarted(runnable));
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    private void startHeartbeat() {
        if (closing.get()) {
            return;
        }
        heartbeats.scheduleWithFixedDelay(() -> request("fibra.ping", Map.of(), options.heartbeatTimeout())
            .subscribe(ignored -> { }, this::fail), options.heartbeatInterval().toMillis(),
            options.heartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void readStderr() {
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
            processUnit.error(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOGGER.warn("Node sidecar stderr: {}", line.length() <= 2048
                    ? line : line.substring(0, 2048) + "...");
            }
        } catch (IOException failure) {
            if (!closing.get()) {
                LOGGER.debug("Failed to read Node sidecar stderr", failure);
            }
        } finally {
            stderrCompletion.complete(null);
        }
    }
}
