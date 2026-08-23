package com.sstlfsj.fibra.loader.pf4j;

import org.pf4j.DefaultVersionManager;
import org.pf4j.VersionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 监听外部候选目录，并按插件 ID 去抖触发单包事务更新。 */
public final class FibraPluginWatcher implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(FibraPluginWatcher.class);
    private static final VersionManager VERSIONS = new DefaultVersionManager();

    private final FibraPluginLoader loader;
    private final Path incomingRoot;
    private final Duration debounce;
    private final WatchService watchService;
    private final ScheduledExecutorService scheduler;
    private final Map<String, PendingUpdate> pending = new ConcurrentHashMap<>();
    private final Object schedulingLock = new Object();
    private final AtomicReference<FibraPluginWatchFailure> lastFailure =
        new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Thread watchThread;

    public FibraPluginWatcher(FibraPluginLoader loader, Path incomingRoot, Duration debounce) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.incomingRoot = validateIncomingRoot(incomingRoot);
        this.debounce = validateDebounce(debounce);
        try {
            this.watchService = this.incomingRoot.getFileSystem().newWatchService();
            this.incomingRoot.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot watch incoming plugin root "
                + this.incomingRoot, exception);
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable ->
            Thread.ofPlatform().daemon().name("fibra-plugin-reload").unstarted(runnable));
    }

    public FibraPluginWatcher start() {
        if (closed.get()) {
            throw new IllegalStateException("FibraPluginWatcher is closed");
        }
        if (running.compareAndSet(false, true)) {
            watchThread = Thread.ofVirtual().name("fibra-plugin-watch").start(this::watchLoop);
        }
        return this;
    }

    public boolean isRunning() {
        return running.get();
    }

    public Optional<FibraPluginWatchFailure> lastFailure() {
        return Optional.ofNullable(lastFailure.get());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running.set(false);
        synchronized (schedulingLock) {
            for (var update : pending.values()) {
                update.future.cancel(false);
            }
            pending.clear();
        }
        try {
            watchService.close();
        } catch (IOException exception) {
            recordFailure(incomingRoot, exception);
        }

        var thread = watchThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                recordFailure(incomingRoot, exception);
            }
        }

        scheduler.close();
    }

    private void watchLoop() {
        try {
            while (running.get()) {
                var key = watchService.take();
                for (var event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        recordFailure(incomingRoot,
                            new IllegalStateException("incoming plugin watch events overflowed"));
                        continue;
                    }
                    var relative = (Path) event.context();
                    var candidate = incomingRoot.resolve(relative).toAbsolutePath().normalize();
                    if (candidate.getFileName().toString().endsWith(".zip")) {
                        schedule(candidate);
                    }
                }
                if (!key.reset()) {
                    recordFailure(incomingRoot,
                        new IllegalStateException("incoming plugin root is no longer watchable"));
                    break;
                }
            }
        } catch (ClosedWatchServiceException ignored) {
            // close() 的正常退出路径。
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (running.get()) {
                recordFailure(incomingRoot, exception);
            }
        } finally {
            running.set(false);
        }
    }

    private void schedule(Path candidate) {
        final FibraPluginCandidate inspected;
        try {
            inspected = loader.inspectCandidate(candidate);
        } catch (RuntimeException exception) {
            recordFailure(candidate, exception);
            return;
        }

        var currentVersion = loader.currentPluginVersion(inspected.pluginId());
        if (currentVersion != null
            && VERSIONS.compareVersions(inspected.version(), currentVersion) <= 0) {
            return;
        }

        synchronized (schedulingLock) {
            if (!running.get()) {
                return;
            }
            pending.compute(inspected.pluginId(), (id, previous) -> {
                if (previous != null && compare(inspected, previous) < 0) {
                    return previous;
                }
                if (previous != null) {
                    previous.future.cancel(false);
                }
                var update = new PendingUpdate(candidate, inspected.version(),
                    inspected.modifiedAt().toMillis());
                update.future = scheduler.schedule(() -> reload(id, update),
                    debounce.toNanos(), TimeUnit.NANOSECONDS);
                return update;
            });
        }
    }

    private void reload(String pluginId, PendingUpdate update) {
        pending.remove(pluginId, update);
        try {
            var currentVersion = loader.currentPluginVersion(pluginId);
            if (currentVersion != null
                && VERSIONS.compareVersions(update.version, currentVersion) <= 0) {
                return;
            }
            loader.applyArtifacts(java.util.List.of(update.candidate));
        } catch (FibraPluginLoaderBusyException exception) {
            schedule(update.candidate);
        } catch (RuntimeException exception) {
            recordFailure(update.candidate, exception);
        }
    }

    private void recordFailure(Path candidate, Throwable cause) {
        var failure = new FibraPluginWatchFailure(candidate, cause);
        lastFailure.set(failure);
        LOGGER.error("Fibra plugin candidate failed: {}", failure.candidate(), cause);
    }

    private static int compare(FibraPluginCandidate candidate, PendingUpdate pending) {
        var version = VERSIONS.compareVersions(candidate.version(), pending.version);
        return version != 0
            ? version
            : Long.compare(candidate.modifiedAt().toMillis(), pending.modifiedAtMillis);
    }

    private static Path validateIncomingRoot(Path path) {
        Objects.requireNonNull(path, "incomingRoot");
        var normalized = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("incomingRoot must be an existing directory: "
                + normalized);
        }
        return normalized;
    }

    private static Duration validateDebounce(Duration debounce) {
        Objects.requireNonNull(debounce, "debounce");
        if (debounce.isZero() || debounce.isNegative()) {
            throw new IllegalArgumentException("debounce must be positive");
        }
        return debounce;
    }

    private static final class PendingUpdate {
        private final Path candidate;
        private final String version;
        private final long modifiedAtMillis;
        private ScheduledFuture<?> future;

        private PendingUpdate(Path candidate, String version, long modifiedAtMillis) {
            this.candidate = candidate;
            this.version = version;
            this.modifiedAtMillis = modifiedAtMillis;
        }
    }
}
