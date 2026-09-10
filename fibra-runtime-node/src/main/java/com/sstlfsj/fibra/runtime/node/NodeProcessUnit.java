package com.sstlfsj.fibra.runtime.node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class NodeProcessUnit implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(NodeProcessUnit.class);
    private static final String SUPERVISOR_RESOURCE =
        "/com/sstlfsj/fibra/runtime/node/node-process-supervisor.mjs";

    private final Path sessionDirectory;
    private final Process supervisor;
    private final Duration terminateTimeout;
    private final AtomicBoolean closing = new AtomicBoolean();

    private NodeProcessUnit(Path sessionDirectory, Process supervisor,
                            Duration terminateTimeout) {
        this.sessionDirectory = sessionDirectory;
        this.supervisor = supervisor;
        this.terminateTimeout = terminateTimeout;
    }

    static NodeProcessUnit launch(Path entrypoint, NodeRuntimeOptions options) {
        Path session = null;
        try {
            var canonicalEntrypoint = entrypoint.toRealPath();
            if (!Files.isRegularFile(canonicalEntrypoint)
                || Files.isSymbolicLink(entrypoint)) {
                throw new NodeRpcException(NodeRpcPhase.START,
                    "Node entrypoint must be a regular non-symbolic file");
            }
            Files.createDirectories(options.sessionRoot());
            session = Files.createTempDirectory(options.sessionRoot(), "node-");
            var supervisorScript = materializeSupervisor(session);
            var process = new ProcessBuilder(List.of(
                options.nodeExecutable().toString(),
                supervisorScript.toString(),
                canonicalEntrypoint.toString(),
                Long.toString(options.terminateTimeout().toMillis())))
                .directory(session.toFile())
                .redirectErrorStream(false)
                .start();
            return new NodeProcessUnit(session, process, options.terminateTimeout());
        } catch (NodeRpcException failure) {
            deleteDirectory(session);
            throw failure;
        } catch (IOException failure) {
            deleteDirectory(session);
            throw new NodeRpcException(NodeRpcPhase.START,
                "Failed to start Node process unit", failure);
        }
    }

    OutputStream input() {
        return supervisor.getOutputStream();
    }

    InputStream output() {
        return supervisor.getInputStream();
    }

    InputStream error() {
        return supervisor.getErrorStream();
    }

    boolean isAlive() {
        return supervisor.isAlive();
    }

    int exitValue() {
        return supervisor.exitValue();
    }

    CompletableFuture<Process> onExit() {
        return supervisor.onExit();
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        var cooperativeTimeout = terminateTimeout.plusMillis(250);
        if (!waitForExit(cooperativeTimeout)) {
            supervisor.destroy();
            if (!waitForExit(terminateTimeout)) {
                supervisor.destroyForcibly();
                if (!waitForExit(terminateTimeout)) {
                    LOGGER.warn("Node process supervisor {} did not terminate within {}",
                        supervisor.pid(), terminateTimeout);
                }
            }
        }
        deleteDirectory(sessionDirectory);
    }

    private boolean waitForExit(Duration timeout) {
        if (!supervisor.isAlive()) {
            return true;
        }
        try {
            supervisor.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return !supervisor.isAlive();
        } catch (Exception failure) {
            return !supervisor.isAlive();
        }
    }

    private static Path materializeSupervisor(Path session) throws IOException {
        var target = session.resolve("node-process-supervisor.mjs");
        try (var source = NodeProcessUnit.class.getResourceAsStream(SUPERVISOR_RESOURCE)) {
            if (source == null) {
                throw new IOException("Missing bundled Node process supervisor");
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static void deleteDirectory(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException failure) {
                    LOGGER.debug("Failed to remove Node session path {}", path, failure);
                }
            });
        } catch (IOException failure) {
            LOGGER.debug("Failed to clean Node session directory {}", directory, failure);
        }
    }
}
