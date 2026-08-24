package com.sstlfsj.fibra.engine;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ArtifactDirectorySource implements AutoCloseable {
    private final Path root;
    private final long debounceNanos;
    private final Runnable dirtyCallback;
    private final WatchService watchService;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Thread worker;

    ArtifactDirectorySource(Path root, Duration debounce, Runnable dirtyCallback) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        if (!Files.isDirectory(this.root)) {
            throw new IllegalArgumentException("artifact source root must be an existing directory: "
                + this.root);
        }
        this.debounceNanos = positive(debounce);
        this.dirtyCallback = Objects.requireNonNull(dirtyCallback, "dirtyCallback");
        try {
            watchService = this.root.getFileSystem().newWatchService();
            this.root.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot create artifact directory source", exception);
        }
    }

    synchronized void start() {
        if (closed.get()) {
            throw new IllegalStateException("artifact directory source is closed");
        }
        if (worker == null) {
            worker = Thread.ofPlatform().daemon().name("fibra-artifact-source")
                .start(this::run);
        }
    }

    private void run() {
        try {
            while (!closed.get()) {
                var key = watchService.take();
                var dirty = consume(key);
                if (!key.reset()) {
                    return;
                }
                if (!dirty) {
                    continue;
                }
                var deadline = System.nanoTime() + debounceNanos;
                while (!closed.get()) {
                    var remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    var next = watchService.poll(remaining, TimeUnit.NANOSECONDS);
                    if (next == null) {
                        break;
                    }
                    consume(next);
                    if (!next.reset()) {
                        return;
                    }
                }
                if (!closed.get()) {
                    dirtyCallback.run();
                }
            }
        } catch (ClosedWatchServiceException ignored) {
            // close 的正常退出路径。
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean consume(java.nio.file.WatchKey key) {
        var dirty = false;
        for (var event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                dirty = true;
            } else if (((Path) event.context()).getFileName().toString().endsWith(".zip")) {
                dirty = true;
            }
        }
        return dirty;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            watchService.close();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot close artifact directory source", exception);
        } finally {
            var current = worker;
            if (current != null && current != Thread.currentThread()) {
                try {
                    current.join();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                        "interrupted while closing artifact directory source", exception);
                }
            }
        }
    }

    private static long positive(Duration value) {
        Objects.requireNonNull(value, "debounce");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("debounce must be positive");
        }
        return value.toNanos();
    }
}
