package com.sstlfsj.fibra.loader.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 监听根配置及其 include 文件并串行触发 refresh。 */
public final class FibraConfigWatcher implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(FibraConfigWatcher.class);
    private static final long FILE_STATE_POLL_MILLIS = 100;

    private final FibraConfigLoader owner;
    private final Duration debounce;
    private final Consumer<FibraConfigReloadFailure> failureSink;
    private final WatchService watchService;
    private final Map<Path, WatchKey> directories = new LinkedHashMap<>();
    private final Thread worker;
    private volatile Set<Path> watchedFiles = Set.of();
    private Map<Path, Boolean> fileExistence = Map.of();
    private volatile boolean closed;
    private boolean closeCompleted;

    FibraConfigWatcher(FibraConfigLoader owner, Duration debounce,
                       Consumer<FibraConfigReloadFailure> failureSink) {
        this.owner = owner;
        this.debounce = debounce;
        this.failureSink = failureSink;
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            refreshRegistrations(false);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot create Fibra config watcher", exception);
        }
        this.worker = Thread.ofPlatform().daemon().name("fibra-config-watcher").start(this::run);
    }

    private void run() {
        var pendingRefresh = false;
        while (!closed) {
            try {
                var key = pendingRefresh ? null
                    : watchService.poll(FILE_STATE_POLL_MILLIS, TimeUnit.MILLISECONDS);
                var dirty = pendingRefresh;
                pendingRefresh = false;
                if (key != null) {
                    dirty |= consume(key);
                }
                dirty |= fileStateChanged();
                if (!dirty) {
                    continue;
                }
                var deadline = System.nanoTime() + debounce.toNanos();
                while (!closed) {
                    var remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    var next = watchService.poll(remaining, TimeUnit.NANOSECONDS);
                    if (next == null) {
                        break;
                    }
                    consume(next);
                }
                if (closed) {
                    break;
                }
                try {
                    owner.refresh();
                    refreshRegistrations(false);
                } catch (FibraConfigException exception) {
                    pendingRefresh = refreshRegistrations(true);
                    publishFailure(exception);
                }
            } catch (ClosedWatchServiceException ignored) {
                return;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException exception) {
                LOGGER.error("Unexpected Fibra config watcher failure", exception);
            }
        }
    }

    private boolean fileStateChanged() {
        var current = new LinkedHashMap<Path, Boolean>();
        var changed = false;
        for (var file : watchedFiles) {
            var exists = Files.exists(file);
            current.put(file, exists);
            var previous = fileExistence.get(file);
            if (previous != null && previous != exists) {
                changed = true;
            }
        }
        fileExistence = Map.copyOf(current);
        return changed;
    }

    private boolean consume(WatchKey key) {
        var directory = (Path) key.watchable();
        var dirty = false;
        for (var event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                dirty = true;
                continue;
            }
            var relative = (Path) event.context();
            if (watchedFiles.contains(directory.resolve(relative).toAbsolutePath().normalize())) {
                dirty = true;
            }
        }
        key.reset();
        return dirty;
    }

    private boolean refreshRegistrations(boolean detectAvailableCandidate) {
        var files = owner.watchedPaths();
        var candidateBecameAvailable = detectAvailableCandidate && files.stream()
            .anyMatch(file -> Files.exists(file)
                && !Boolean.TRUE.equals(fileExistence.get(file)));
        watchedFiles = Set.copyOf(files);
        var expectedDirectories = files.stream()
            .map(Path::getParent)
            .filter(Files::isDirectory)
            .collect(java.util.stream.Collectors.toSet());
        var obsolete = directories.keySet().stream()
            .filter(directory -> !expectedDirectories.contains(directory))
            .toList();
        for (var directory : obsolete) {
            directories.remove(directory).cancel();
        }
        for (var file : files) {
            var directory = file.getParent();
            if (directories.containsKey(directory)) {
                continue;
            }
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try {
                directories.put(directory, directory.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE));
            } catch (IOException exception) {
                throw new IllegalStateException(
                    "cannot watch Fibra config directory " + directory, exception);
            }
        }
        var current = new LinkedHashMap<Path, Boolean>();
        files.forEach(file -> current.put(file, Files.exists(file)));
        fileExistence = Map.copyOf(current);
        return candidateBecameAvailable;
    }

    private void publishFailure(FibraConfigException exception) {
        var path = exception.path() == null ? owner.configPath() : exception.path();
        var failure = new FibraConfigReloadFailure(path, exception, Instant.now());
        LOGGER.warn("Fibra config refresh failed for {}", path, exception);
        try {
            failureSink.accept(failure);
        } catch (RuntimeException sinkFailure) {
            LOGGER.error("Fibra config failure sink failed", sinkFailure);
        }
    }

    boolean isWorkerThread() {
        return Thread.currentThread() == worker;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                awaitCloseCompletion();
                return;
            }
            closed = true;
        }
        try {
            watchService.close();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot close Fibra config watcher", exception);
        } finally {
            try {
                if (Thread.currentThread() != worker) {
                    worker.join();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                    "interrupted while closing Fibra config watcher", exception);
            } finally {
                try {
                    owner.watcherClosed(this);
                } finally {
                    synchronized (this) {
                        closeCompleted = true;
                        notifyAll();
                    }
                }
            }
        }
    }

    private void awaitCloseCompletion() {
        while (!closeCompleted) {
            try {
                wait();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                    "interrupted while waiting for Fibra config watcher close", exception);
            }
        }
    }
}
