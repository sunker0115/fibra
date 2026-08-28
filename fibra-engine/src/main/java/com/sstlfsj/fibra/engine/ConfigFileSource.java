package com.sstlfsj.fibra.engine;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

final class ConfigFileSource implements AutoCloseable {
    private static final long POLL_MILLIS = 100;
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigFileSource.class);

    private final Supplier<Set<Path>> pathsSupplier;
    private final long debounceNanos;
    private final Runnable dirtyCallback;
    private final WatchService watchService;
    private final Map<Path, WatchKey> directories = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private Set<Path> files = Set.of();
    private Map<Path, Boolean> existence = Map.of();
    private Thread worker;

    ConfigFileSource(Supplier<Set<Path>> pathsSupplier, Duration debounce,
                     Runnable dirtyCallback) {
        this.pathsSupplier = Objects.requireNonNull(pathsSupplier, "pathsSupplier");
        this.debounceNanos = positive(debounce);
        this.dirtyCallback = Objects.requireNonNull(dirtyCallback, "dirtyCallback");
        WatchService created = null;
        try {
            created = FileSystems.getDefault().newWatchService();
            watchService = created;
            refreshRegistrations();
        } catch (IOException | RuntimeException exception) {
            if (created != null) {
                try {
                    created.close();
                } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            throw new IllegalStateException("cannot create config file source", exception);
        }
    }

    synchronized void start() {
        if (closed.get()) {
            throw new IllegalStateException("config file source is closed");
        }
        if (worker == null) {
            worker = Thread.ofPlatform().daemon().name("fibra-config-source")
                .start(this::run);
        }
    }

    private void run() {
        try {
            while (!closed.get()) {
                try {
                    refreshRegistrations();
                } catch (IOException failure) {
                    LOGGER.warn("Cannot refresh Fibra config source registrations", failure);
                    Thread.sleep(POLL_MILLIS);
                    continue;
                }
                var key = watchService.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                var dirty = key != null && consume(key);
                dirty |= existenceChanged();
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
                }
                if (!closed.get()) {
                    dirtyCallback.run();
                    try {
                        refreshRegistrations();
                    } catch (IOException exception) {
                        throw new IllegalStateException(
                            "cannot refresh config source registrations", exception);
                    }
                }
            }
        } catch (ClosedWatchServiceException ignored) {
            // close 的正常退出路径。
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            LOGGER.warn("Fibra config source stopped unexpectedly", failure);
        }
    }

    private boolean consume(WatchKey key) {
        var directory = (Path) key.watchable();
        var dirty = false;
        for (var event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                dirty = true;
            } else if (files.contains(directory.resolve((Path) event.context())
                .toAbsolutePath().normalize())) {
                dirty = true;
            }
        }
        if (!key.reset()) {
            directories.remove(directory, key);
            dirty = true;
        }
        return dirty;
    }

    private boolean existenceChanged() {
        var current = new LinkedHashMap<Path, Boolean>();
        var changed = false;
        for (var file : files) {
            var exists = Files.exists(file);
            current.put(file, exists);
            var previous = existence.get(file);
            if (previous != null && previous != exists) {
                changed = true;
            }
        }
        existence = Map.copyOf(current);
        return changed;
    }

    private void refreshRegistrations() throws IOException {
        var nextFiles = pathsSupplier.get().stream()
            .map(path -> path.toAbsolutePath().normalize())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var expected = nextFiles.stream().map(Path::getParent)
            .filter(Objects::nonNull).filter(Files::isDirectory)
            .collect(java.util.stream.Collectors.toSet());
        for (var obsolete : directories.keySet().stream()
            .filter(path -> !expected.contains(path)).toList()) {
            directories.remove(obsolete).cancel();
        }
        for (var directory : expected) {
            if (!directories.containsKey(directory)) {
                directories.put(directory, directory.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE));
            }
        }
        files = nextFiles;
        var current = new LinkedHashMap<Path, Boolean>();
        files.forEach(file -> current.put(file,
            existence.getOrDefault(file, Files.exists(file))));
        existence = Map.copyOf(current);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            watchService.close();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot close config file source", exception);
        } finally {
            var current = worker;
            if (current != null && current != Thread.currentThread()) {
                try {
                    current.join();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                        "interrupted while closing config file source", exception);
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
