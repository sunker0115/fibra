package com.sstlfsj.fibra.runtime.node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class NodeSidecar implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(NodeSidecar.class);
    private static final TypeReference<Map<String, Object>> MESSAGE_TYPE =
        new TypeReference<>() { };

    private final NodeRuntimeOptions options;
    private final Path sessionDirectory;
    private final Process process;
    private final BufferedWriter writer;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicLong requestIds = new AtomicLong();
    private final Map<Long, Sinks.One<Object>> pending = new ConcurrentHashMap<>();
    private final Sinks.One<Void> termination = Sinks.one();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final ScheduledExecutorService heartbeats;

    private NodeSidecar(NodeRuntimeOptions options, Path sessionDirectory,
                        Process process) {
        this.options = options;
        this.sessionDirectory = sessionDirectory;
        this.process = process;
        this.writer = new BufferedWriter(new OutputStreamWriter(
            process.getOutputStream(), StandardCharsets.UTF_8));
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(runnable ->
            Thread.ofPlatform().daemon().name("fibra-node-heartbeat").unstarted(runnable));
        startReaders();
        process.onExit().thenAccept(ignored -> onExit(process.exitValue()));
    }

    public static Mono<NodeSidecar> start(Path entrypoint, NodeRuntimeOptions options) {
        Objects.requireNonNull(entrypoint, "entrypoint");
        Objects.requireNonNull(options, "options");
        return Mono.fromCallable(() -> launch(entrypoint, options))
            .flatMap(sidecar -> sidecar.handshake()
                .thenReturn(sidecar)
                .doOnSuccess(ignored -> sidecar.startHeartbeat())
                .onErrorResume(failure -> {
                    sidecar.close();
                    return Mono.error(failure);
                }));
    }

    public Mono<Object> request(String method, Object parameters) {
        return request(method, parameters, options.defaultRequestTimeout());
    }

    public Mono<Object> request(String method, Object parameters, Duration timeout) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(timeout, "timeout");
        if (method.isBlank()) {
            return Mono.error(new IllegalArgumentException("method must not be blank"));
        }
        if (timeout.isZero() || timeout.isNegative()) {
            return Mono.error(new IllegalArgumentException("timeout must be positive"));
        }
        return Mono.defer(() -> {
            if (!isAlive() || closing.get()) {
                return Mono.error(new NodeRpcException(NodeRpcPhase.REQUEST,
                    "Node sidecar is not available"));
            }
            var id = requestIds.incrementAndGet();
            var sink = Sinks.<Object>one();
            pending.put(id, sink);
            try {
                send(requestMessage(id, method, parameters));
            } catch (RuntimeException failure) {
                pending.remove(id);
                return Mono.error(failure);
            }
            return sink.asMono()
                .doOnCancel(() -> cancel(id))
                .timeout(timeout)
                .onErrorMap(TimeoutException.class, failure -> {
                    cancel(id);
                    return new NodeRpcException(NodeRpcPhase.TIMEOUT,
                        "Node request timed out: " + method, failure);
                })
                .doFinally(ignored -> pending.remove(id));
        });
    }

    public Mono<Void> termination() {
        return termination.asMono();
    }

    public boolean isAlive() {
        return process.isAlive() && !failed.get();
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        heartbeats.shutdownNow();
        var unavailable = new NodeRpcException(NodeRpcPhase.TERMINATE,
            "Node sidecar was closed");
        pending.values().forEach(sink -> sink.tryEmitError(unavailable));
        pending.clear();
        try {
            writer.close();
        } catch (IOException failure) {
            LOGGER.debug("Failed to close Node sidecar input", failure);
        }
        terminateProcessTree();
        termination.tryEmitEmpty();
        deleteSessionDirectory();
    }

    private static NodeSidecar launch(Path entrypoint, NodeRuntimeOptions options) {
        try {
            var canonicalEntrypoint = entrypoint.toRealPath();
            if (!Files.isRegularFile(canonicalEntrypoint)
                || Files.isSymbolicLink(entrypoint)) {
                throw new NodeRpcException(NodeRpcPhase.START,
                    "Node entrypoint must be a regular non-symbolic file");
            }
            Files.createDirectories(options.sessionRoot());
            var session = Files.createTempDirectory(options.sessionRoot(), "node-");
            var process = new ProcessBuilder(List.of(options.nodeExecutable().toString(),
                canonicalEntrypoint.toString()))
                .directory(session.toFile())
                .redirectErrorStream(false)
                .start();
            return new NodeSidecar(options, session, process);
        } catch (NodeRpcException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new NodeRpcException(NodeRpcPhase.START,
                "Failed to start Node sidecar", failure);
        }
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
        try (var input = process.getInputStream()) {
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
        try (var reader = process.errorReader(StandardCharsets.UTF_8)) {
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
            var rawId = message.get("id");
            if (!(rawId instanceof Number number)) {
                return;
            }
            var sink = pending.remove(number.longValue());
            if (sink == null) {
                return;
            }
            if (message.containsKey("error")) {
                sink.tryEmitError(new NodeRpcException(NodeRpcPhase.REQUEST,
                    "Node request failed: " + message.get("error")));
            } else if (!message.containsKey("result")) {
                sink.tryEmitError(new NodeRpcException(NodeRpcPhase.PROTOCOL,
                    "Node response contains neither result nor error"));
            } else {
                sink.tryEmitValue(message.get("result"));
            }
        } catch (NodeRpcException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new NodeRpcException(NodeRpcPhase.PROTOCOL,
                "Invalid JSON-RPC frame from Node sidecar", failure);
        }
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

    private void cancel(long id) {
        if (pending.remove(id) == null || closing.get()) {
            return;
        }
        try {
            send(Map.of("jsonrpc", "2.0", "method", "$/cancelRequest",
                "params", Map.of("id", id)));
        } catch (RuntimeException failure) {
            LOGGER.debug("Failed to cancel Node request {}", id, failure);
        }
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
            return;
        }
        var exception = failure instanceof NodeRpcException nodeFailure
            ? nodeFailure : new NodeRpcException(NodeRpcPhase.PROTOCOL,
            "Node sidecar failed", failure);
        pending.values().forEach(sink -> sink.tryEmitError(exception));
        pending.clear();
        termination.tryEmitError(exception);
        close();
    }

    private void onExit(int exitCode) {
        if (!closing.get()) {
            fail(new NodeRpcException(NodeRpcPhase.EXIT,
                "Node sidecar exited unexpectedly with code " + exitCode));
        }
    }

    private void terminateProcessTree() {
        List<ProcessHandle> descendants;
        try {
            descendants = new ArrayList<>(process.descendants().toList());
        } catch (RuntimeException failure) {
            descendants = List.of();
            LOGGER.warn("Cannot enumerate Node sidecar descendants; terminating parent only",
                failure);
        }
        descendants.reversed().forEach(ProcessHandle::destroy);
        descendants.reversed().forEach(handle ->
            awaitOrForceExit(handle, options.terminateTimeout()));
        process.destroy();
        awaitOrForceExit(process.toHandle(), options.terminateTimeout());
    }

    private static void awaitOrForceExit(ProcessHandle handle, Duration timeout) {
        if (!handle.isAlive()) {
            return;
        }
        if (waitForExit(handle, timeout)) {
            return;
        }
        handle.destroyForcibly();
        if (!waitForExit(handle, timeout)) {
            LOGGER.warn("Process {} did not terminate within {}", handle.pid(), timeout);
        }
    }

    private static boolean waitForExit(ProcessHandle handle, Duration timeout) {
        if (!handle.isAlive()) {
            return true;
        }
        try {
            handle.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return !handle.isAlive();
        } catch (Exception failure) {
            return !handle.isAlive();
        }
    }

    private void deleteSessionDirectory() {
        try (var paths = Files.walk(sessionDirectory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException failure) {
                    LOGGER.debug("Failed to remove Node session path {}", path, failure);
                }
            });
        } catch (IOException failure) {
            LOGGER.debug("Failed to clean Node session directory {}",
                sessionDirectory, failure);
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
}
