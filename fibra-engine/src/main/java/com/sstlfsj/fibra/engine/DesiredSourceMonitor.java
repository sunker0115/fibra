package com.sstlfsj.fibra.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 文件事件只产生脏信号；超时轮询提供丢失通知后的周期 resync。 */
final class DesiredSourceMonitor {
    private static final Logger LOGGER = LoggerFactory.getLogger(DesiredSourceMonitor.class);
    private final WatchService watcher;
    private final long intervalMillis;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private final LinkedHashMap<Path, WatchKey> directories = new LinkedHashMap<>();
    private Runnable dirty;
    private Thread worker;

    DesiredSourceMonitor(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("auto refresh interval must be positive");
        }
        intervalMillis = Math.max(1, interval.toMillis());
        try {
            watcher = FileSystems.getDefault().newWatchService();
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot create desired source watcher", failure);
        }
    }

    synchronized void start(Runnable action) {
        if (worker != null) return;
        dirty = java.util.Objects.requireNonNull(action, "action");
        worker = Thread.ofPlatform().daemon().name("fibra-desired-source").start(this::run);
    }

    synchronized void update(Set<Path> sources) {
        if (closed.get()) return;
        var next = new java.util.LinkedHashSet<Path>();
        for (var source : sources) {
            var parent = source.toAbsolutePath().normalize().getParent();
            if (parent != null && Files.isDirectory(parent)) next.add(parent);
        }
        directories.entrySet().removeIf(entry -> {
            if (next.contains(entry.getKey())) return false;
            entry.getValue().cancel();
            return true;
        });
        for (var directory : next) {
            if (directories.containsKey(directory)) continue;
            try {
                directories.put(directory, directory.register(watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE));
            } catch (IOException failure) {
                LOGGER.warn("Cannot watch desired source directory {}: {}",
                    directory, failure.toString());
            } catch (java.nio.file.ClosedWatchServiceException ignored) {
                if (!closed.get()) throw ignored;
            }
        }
    }

    Mono<Void> closeAsync() {
        return Mono.defer(() -> {
            stop();
            return Mono.fromFuture(stopped);
        });
    }

    private synchronized void stop() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            watcher.close();
        } catch (IOException failure) {
            stopped.completeExceptionally(failure);
        }
        if (worker == null) stopped.complete(null);
    }

    private void run() {
        try {
            while (!closed.get()) {
                var key = watcher.poll(intervalMillis, TimeUnit.MILLISECONDS);
                if (key != null) {
                    key.pollEvents();
                    key.reset();
                }
                if (!closed.get()) dirty.run();
            }
            stopped.complete(null);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            stopped.completeExceptionally(failure);
        } catch (java.nio.file.ClosedWatchServiceException ignored) {
            stopped.complete(null);
        } catch (Throwable failure) {
            stopped.completeExceptionally(failure);
        }
    }
}
