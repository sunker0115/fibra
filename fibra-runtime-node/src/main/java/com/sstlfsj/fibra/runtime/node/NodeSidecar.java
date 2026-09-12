package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.CancellationToken;
import com.sstlfsj.fibra.DrainingDisposable;
import com.sstlfsj.fibra.bridge.RemoteContributionFailure;
import com.sstlfsj.fibra.value.LiteralValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class NodeSidecar implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(NodeSidecar.class);
    private static final TypeReference<Map<String, Object>> MESSAGE_TYPE =
        new TypeReference<>() { };

    private final NodeRuntimeOptions options;
    private final NodeProcessUnit processUnit;
    private final Runnable disableRequest;
    private final BufferedWriter writer;
    private final JsonMapper json = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .build();
    private final AtomicLong requestIds = new AtomicLong();
    private final Map<Long, Request> pending = new ConcurrentHashMap<>();
    private final Sinks.One<Void> termination = Sinks.one();
    private final CompletableFuture<Void> shutdownCompletion = new CompletableFuture<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final AtomicReference<NodeRpcException> cleanupFailure = new AtomicReference<>();
    private final AtomicBoolean handshakeComplete = new AtomicBoolean();
    private final ScheduledExecutorService heartbeats;

    private NodeSidecar(NodeRuntimeOptions options, NodeProcessUnit processUnit,
                        Runnable disableRequest, ScheduledExecutorService scheduler) {
        this.options = options;
        this.processUnit = processUnit;
        this.disableRequest = disableRequest;
        this.writer = new BufferedWriter(new OutputStreamWriter(
            processUnit.input(), StandardCharsets.UTF_8));
        this.heartbeats = Objects.requireNonNull(scheduler, "scheduler");
        startReaders();
        processUnit.onExit().thenAccept(ignored -> onExit(processUnit.exitValue()));
    }

    static Mono<NodeSidecar> start(Path entrypoint, NodeRuntimeOptions options,
                                   Runnable disableRequest) {
        return start(entrypoint, options, disableRequest, null);
    }

    static Mono<NodeSidecar> start(Path entrypoint, NodeRuntimeOptions options,
                                   Runnable disableRequest,
                                   ScheduledExecutorService scheduler) {
        Objects.requireNonNull(entrypoint, "entrypoint");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(disableRequest, "disableRequest");
        return Mono.fromCallable(() -> launch(entrypoint, options, disableRequest, scheduler))
            .flatMap(sidecar -> sidecar.handshake()
                .thenReturn(sidecar)
                .doOnSuccess(ignored -> sidecar.startHeartbeat())
                .onErrorResume(failure -> {
                    sidecar.close();
                    return Mono.error(failure);
                }));
    }

    Mono<Object> request(String method, Object parameters) {
        return request(method, parameters, options.defaultRequestTimeout());
    }

    Mono<Object> request(String method, Object parameters, Duration timeout) {
        return Mono.defer(() -> beginRequest(method, parameters, timeout, null).result()
            .flatMap(response -> response.value() == null
                ? Mono.error(new NodeRpcException(NodeRpcPhase.PROTOCOL,
                    "Node response contains a null result: " + method))
                : Mono.just(response.value())));
    }

    Request beginRequest(String method, Object parameters, CancellationToken cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        return beginRequest(method, parameters, options.defaultRequestTimeout(), cancellation);
    }

    private Request beginRequest(String method, Object parameters, Duration timeout,
                                 CancellationToken cancellation) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(timeout, "timeout");
        if (method.isBlank()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (cancellation != null && cancellation.isCancelled()) {
            throw cancelledBeforeSend(method);
        }
        if (!isAlive() || closing.get()) {
            throw new NodeRpcException(NodeRpcPhase.REQUEST,
                "Node sidecar is not available");
        }
        var request = new Request(requestIds.incrementAndGet(), method);
        pending.put(request.id, request);
        try {
            request.observe(cancellation);
            request.scheduleExecutionTimeout(timeout);
            if (!startRequest(request, parameters)) {
                request.completeFailure(cancelledBeforeSend(method));
                return request;
            }
            return request;
        } catch (RuntimeException | Error failure) {
            pending.remove(request.id, request);
            request.completeFailure(failure);
            throw failure;
        }
    }

    Mono<Void> termination() {
        return termination.asMono().publishOn(Schedulers.boundedElastic());
    }

    boolean isAlive() {
        return processUnit.isAlive() && !failed.get() && !closing.get();
    }

    @Override
    public void close() {
        terminate(null);
    }

    private void terminate(NodeRpcException failure) {
        if (!closing.compareAndSet(false, true)) {
            shutdownCompletion.join();
            if (cleanupFailure.get() != null) {
                throw cleanupFailure.get();
            }
            return;
        }
        heartbeats.shutdown();
        try {
            writer.close();
        } catch (IOException closeFailure) {
            LOGGER.debug("Failed to close Node sidecar input", closeFailure);
        }
        NodeRpcException terminalFailure = failure;
        try {
            processUnit.close();
        } catch (NodeRpcException processFailure) {
            if (failure != null && failure != processFailure) {
                processFailure.addSuppressed(failure);
            }
            cleanupFailure.set(processFailure);
            terminalFailure = processFailure;
            failed.set(true);
        }
        var completedFailure = terminalFailure == null
            ? new NodeRpcException(NodeRpcPhase.TERMINATE, "Node sidecar was closed")
            : terminalFailure;
        if (cleanupFailure.get() == null) {
            pending.values().forEach(request -> request.completeFailure(completedFailure));
        } else {
            pending.values().forEach(request -> request.completeCleanupFailure(completedFailure));
        }
        pending.clear();
        if (terminalFailure == null) {
            termination.tryEmitEmpty();
        } else {
            termination.tryEmitError(terminalFailure);
        }
        shutdownCompletion.complete(null);
        if (cleanupFailure.get() != null) {
            throw cleanupFailure.get();
        }
    }

    private static NodeSidecar launch(Path entrypoint, NodeRuntimeOptions options,
                                      Runnable disableRequest,
                                      ScheduledExecutorService scheduler) {
        return new NodeSidecar(options, NodeProcessUnit.launch(entrypoint, options),
            disableRequest, scheduler == null ? requestScheduler() : scheduler);
    }

    private static ScheduledExecutorService requestScheduler() {
        var scheduler = new ScheduledThreadPoolExecutor(1, runnable ->
            Thread.ofPlatform().daemon().name("fibra-node-scheduler").unstarted(runnable));
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    private Mono<Void> handshake() {
        return request("fibra.handshake", Map.of("protocol", 1),
            options.handshakeTimeout()).flatMap(result -> {
                if (!(result instanceof Map<?, ?> values)
                    || !(values.get("protocol") instanceof Number protocol)
                    || protocol.intValue() != 1) {
                    return Mono.error(new NodeRpcException(NodeRpcPhase.HANDSHAKE,
                        "Node sidecar returned an incompatible handshake"));
                }
                handshakeComplete.set(true);
                return Mono.empty();
            }).onErrorMap(failure -> failure instanceof NodeRpcException
                && ((NodeRpcException) failure).phase() == NodeRpcPhase.HANDSHAKE
                ? failure : new NodeRpcException(NodeRpcPhase.HANDSHAKE,
                "Node sidecar handshake failed", failure)).then();
    }

    private void startHeartbeat() {
        heartbeats.scheduleWithFixedDelay(() -> request("fibra.ping", Map.of(),
                options.heartbeatTimeout())
            .subscribe(ignored -> { }, this::fail),
            options.heartbeatInterval().toMillis(),
            options.heartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void startReaders() {
        Thread.ofPlatform().daemon().name("fibra-node-stdout")
            .start(this::readStdout);
        Thread.ofPlatform().daemon().name("fibra-node-stderr")
            .start(this::readStderr);
    }

    private void readStdout() {
        try (var input = processUnit.output()) {
            var frame = new ByteArrayOutputStream();
            int next;
            while ((next = input.read()) >= 0) {
                if (next == '\n') {
                    if (frame.size() > 0) {
                        acceptFrame(frame.toByteArray());
                        frame.reset();
                    }
                    continue;
                }
                if (frame.size() >= options.maxMessageBytes()) {
                    throw new NodeRpcException(NodeRpcPhase.PROTOCOL,
                        "Node sidecar frame exceeds " + options.maxMessageBytes()
                            + " bytes");
                }
                frame.write(next);
            }
            if (frame.size() > 0 && !closing.get()) {
                throw new NodeRpcException(NodeRpcPhase.PROTOCOL,
                    "Node sidecar ended with an incomplete frame");
            }
        } catch (Throwable failure) {
            if (!closing.get()) {
                fail(asProtocolFailure(failure));
            }
        }
    }

    private void readStderr() {
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
            processUnit.error(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOGGER.warn("Node sidecar stderr: {}", abbreviate(line, 2048));
            }
        } catch (IOException failure) {
            if (!closing.get()) {
                LOGGER.debug("Failed to read Node sidecar stderr", failure);
            }
        }
    }

    private void acceptFrame(byte[] bytes) {
        try {
            var message = json.readValue(bytes, MESSAGE_TYPE);
            if (!"2.0".equals(message.get("jsonrpc"))) {
                throw new NodeRpcException(NodeRpcPhase.PROTOCOL,
                    "Node sidecar message is not JSON-RPC 2.0");
            }
            if ("fibra.disable".equals(message.get("method"))) {
                acceptDisable(message);
                return;
            }
            var id = longInteger(message.get("id"));
            if (id == null) {
                return;
            }
            var hasResult = message.containsKey("result");
            var hasError = message.containsKey("error");
            if (hasResult == hasError) {
                throw new NodeRpcException(NodeRpcPhase.PROTOCOL,
                    hasResult ? "Node response contains both result and error"
                        : "Node response contains neither result nor error");
            }
            var request = removePending(id);
            if (request == null) {
                return;
            }
            if (hasError) {
                var failure = remoteFailure(message.get("error"));
                if (failure == null) {
                    request.completeFailure(new NodeRpcException(NodeRpcPhase.PROTOCOL,
                        "Invalid JSON-RPC error from Node sidecar"));
                } else {
                    request.completeFailure(new NodeRpcException(NodeRpcPhase.REQUEST, failure));
                }
            } else {
                request.complete(message.get("result"));
            }
        } catch (NodeRpcException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new NodeRpcException(NodeRpcPhase.PROTOCOL,
                "Invalid JSON-RPC frame from Node sidecar", failure);
        }
    }

    private static RemoteContributionFailure remoteFailure(Object value) {
        if (!(value instanceof Map<?, ?> error) || !error.keySet().stream()
            .allMatch(key -> key instanceof String name && (name.equals("code")
                || name.equals("message") || name.equals("data")))
            || !error.containsKey("code") || !error.containsKey("message")
            || !(error.get("message") instanceof String message)) {
            return null;
        }
        var code = integer(error.get("code"));
        if (code == null) {
            return null;
        }
        try {
            return new RemoteContributionFailure(code, message, LiteralValue.of(error.get("data")));
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private static Integer integer(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        try {
            return new java.math.BigDecimal(number.toString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException failure) {
            return null;
        }
    }

    private static Long longInteger(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        try {
            return new java.math.BigDecimal(number.toString()).longValueExact();
        } catch (NumberFormatException | ArithmeticException failure) {
            return null;
        }
    }

    private void acceptDisable(Map<String, Object> message) {
        if (!handshakeComplete.get()) {
            throw new NodeRpcException(NodeRpcPhase.PROTOCOL,
                "Node sidecar requested disable before handshake completed");
        }
        if (!message.keySet().equals(Set.of("jsonrpc", "method", "params"))
            || !(message.get("params") instanceof Map<?, ?> parameters)
            || !parameters.isEmpty()) {
            throw new NodeRpcException(NodeRpcPhase.PROTOCOL,
                "Invalid fibra.disable notification from Node sidecar");
        }
        disableRequest.run();
    }

    private synchronized void send(Map<String, Object> message) {
        try {
            var encoded = json.writeValueAsString(message);
            if (encoded.getBytes(StandardCharsets.UTF_8).length
                > options.maxMessageBytes()) {
                throw new NodeRpcException(NodeRpcPhase.PROTOCOL,
                    "Host JSON-RPC frame exceeds " + options.maxMessageBytes()
                        + " bytes");
            }
            writer.write(encoded);
            writer.newLine();
            writer.flush();
        } catch (NodeRpcException failure) {
            throw failure;
        } catch (IOException failure) {
            var exception = new NodeRpcException(NodeRpcPhase.REQUEST,
                "Failed to write Node request", failure);
            fail(exception);
            throw exception;
        }
    }

    private synchronized boolean startRequest(Request request, Object parameters) {
        if (request.cancellationRequested.get()) {
            return false;
        }
        if (closing.get() || failed.get()) {
            throw new NodeRpcException(NodeRpcPhase.REQUEST,
                "Node sidecar is not available");
        }
        request.started.set(true);
        try {
            send(requestMessage(request.id, request.method, parameters));
            return true;
        } catch (RuntimeException failure) {
            pending.remove(request.id, request);
            throw failure;
        }
    }

    private void cancel(Request request) {
        synchronized (this) {
            if (pending.get(request.id) != request || failed.get() || closing.get()
                || !request.cancellationRequested.compareAndSet(false, true)) {
                return;
            }
            if (!request.started.get()) {
                return;
            }
            try {
                send(Map.of("jsonrpc", "2.0", "method", "$/cancelRequest",
                    "params", Map.of("id", request.id)));
                request.scheduleCancellationTimeout();
            } catch (RuntimeException failure) {
                LOGGER.debug("Failed to cancel Node request {}", request.id, failure);
            }
        }
    }

    private synchronized Request removePending(long id) {
        return closing.get() ? null : pending.remove(id);
    }

    private static NodeRpcException cancelledBeforeSend(String method) {
        return NodeRpcException.cancelledBeforeSend(method);
    }

    private static Map<String, Object> requestMessage(long id, String method,
                                                       Object parameters) {
        var message = new LinkedHashMap<String, Object>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", parameters == null ? Map.of() : parameters);
        return message;
    }

    private void fail(Throwable failure) {
        if (!failed.compareAndSet(false, true)) {
            shutdownCompletion.join();
            return;
        }
        var exception = failure instanceof NodeRpcException nodeFailure
            ? nodeFailure : new NodeRpcException(NodeRpcPhase.PROTOCOL,
            "Node sidecar failed", failure);
        terminate(exception);
    }

    private void onExit(int exitCode) {
        if (!closing.get()) {
            fail(new NodeRpcException(NodeRpcPhase.EXIT,
                "Node sidecar exited unexpectedly with code " + exitCode));
        }
    }

    private static NodeRpcException asProtocolFailure(Throwable failure) {
        return failure instanceof NodeRpcException nodeFailure ? nodeFailure
            : new NodeRpcException(NodeRpcPhase.PROTOCOL,
            "Failed to read Node sidecar output", failure);
    }

    private static String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value
            : value.substring(0, maxLength) + "...";
    }

    final class Request implements DrainingDisposable {
        private final long id;
        private final String method;
        private final Sinks.One<Response> result = Sinks.one();
        private final Sinks.One<Void> settled = Sinks.one();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private final AtomicBoolean timedOut = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile reactor.core.Disposable cancellationSubscription;
        private volatile ScheduledFuture<?> executionDeadline;
        private volatile ScheduledFuture<?> cancellationDeadline;

        private Request(long id, String method) {
            this.id = id;
            this.method = method;
        }

        Mono<Response> result() {
            return result.asMono().doOnCancel(this::requestCancellation)
                .publishOn(Schedulers.boundedElastic());
        }

        private void observe(CancellationToken cancellation) {
            if (cancellation == null) {
                return;
            }
            var subscription = Objects.requireNonNull(cancellation.cancelled(),
                    "cancellation token returned null signal")
                .then(Mono.fromRunnable(this::requestCancellation))
                .subscribe(ignored -> { }, NodeSidecar.this::fail);
            cancellationSubscription = subscription;
            if (finished.get()) {
                subscription.dispose();
            }
        }

        private void scheduleExecutionTimeout(Duration timeout) {
            executionDeadline = heartbeats.schedule(() -> {
                if (finished.get()) {
                    return;
                }
                timedOut.set(true);
                requestCancellation();
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (finished.get()) {
                executionDeadline.cancel(false);
            }
        }

        private void scheduleCancellationTimeout() {
            cancellationDeadline = heartbeats.schedule(() -> {
                if (pending.get(id) != this || finished.get()) {
                    return;
                }
                fail(cancellationTimeoutFailure());
            }, options.requestCancellationTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (finished.get()) {
                cancellationDeadline.cancel(false);
            }
        }

        private void requestCancellation() {
            cancel(this);
        }

        private void complete(Object value) {
            if (!finish()) {
                return;
            }
            if (timedOut.get()) {
                result.tryEmitError(requestTimeoutFailure());
            } else {
                result.tryEmitValue(new Response(value));
            }
            settled.tryEmitEmpty();
        }

        private void completeFailure(Throwable failure) {
            if (!finish()) {
                return;
            }
            result.tryEmitError(timedOut.get() ? requestTimeoutFailure() : failure);
            settled.tryEmitEmpty();
        }

        private void completeCleanupFailure(Throwable failure) {
            if (!finish()) {
                return;
            }
            result.tryEmitError(failure);
            settled.tryEmitError(failure);
        }

        private boolean finish() {
            if (!finished.compareAndSet(false, true)) {
                return false;
            }
            pending.remove(id, this);
            if (executionDeadline != null) {
                executionDeadline.cancel(false);
            }
            if (cancellationDeadline != null) {
                cancellationDeadline.cancel(false);
            }
            if (cancellationSubscription != null) {
                cancellationSubscription.dispose();
            }
            return true;
        }

        private NodeRpcException requestTimeoutFailure() {
            return new NodeRpcException(NodeRpcPhase.TIMEOUT,
                "Node request timed out: " + method,
                new TimeoutException("Node request timed out"));
        }

        private NodeRpcException cancellationTimeoutFailure() {
            if (timedOut.get()) {
                return requestTimeoutFailure();
            }
            return new NodeRpcException(NodeRpcPhase.TIMEOUT,
                "Node request did not settle after cancellation: " + method,
                new TimeoutException("Node request cancellation timed out"));
        }

        @Override
        public Mono<Void> drain() {
            requestCancellation();
            return settled.asMono().publishOn(Schedulers.boundedElastic());
        }

        @Override
        public Mono<Void> dispose() {
            return drain();
        }
    }

    record Response(Object value) { }
}
