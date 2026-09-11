package com.sstlfsj.fibra.plugins.storage.json;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.DrainingDisposable;
import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.logging.FibraLogger;
import com.sstlfsj.fibra.plugins.storage.ConfigChange;
import com.sstlfsj.fibra.plugins.storage.ConfigChangeListener;
import com.sstlfsj.fibra.plugins.storage.ConfigChangeOperation;
import com.sstlfsj.fibra.plugins.storage.ConfigDocument;
import com.sstlfsj.fibra.plugins.storage.ConfigStore;
import com.sstlfsj.fibra.plugins.storage.StorageErrorCode;
import com.sstlfsj.fibra.plugins.storage.StorageException;
import com.sstlfsj.fibra.value.LiteralValue;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class JsonConfigStore implements ConfigStore, DrainingDisposable {
    private static final StorageIo DEFAULT_STORAGE = new AtomicStorageIo();

    private final Object lifecycle = new Object();
    private final Path target;
    private final FibraLogger logger;
    private final StorageIo storage;
    private final ExecutorService writer;
    private final List<ListenerRegistration> listeners = new ArrayList<>();

    private volatile ConfigDocument document;
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    private boolean accepting = true;
    private boolean released;
    private Mono<Void> drained;
    private Mono<Void> disposed;

    JsonConfigStore(Path target, FibraLogger logger) {
        this(target, logger, DEFAULT_STORAGE);
    }

    JsonConfigStore(Path target, FibraLogger logger, StorageIo storage) {
        this.target = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        this.logger = Objects.requireNonNull(logger, "logger");
        this.storage = Objects.requireNonNull(storage, "storage");
        document = loadInitial(this.target);
        writer = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true)
            .name("fibra-storage-json-writer-", 0).factory());
    }

    static StorageIo defaultStorageIo() {
        return DEFAULT_STORAGE;
    }

    @Override
    public ConfigDocument load(InvocationContext context) {
        Objects.requireNonNull(context, "context");
        synchronized (lifecycle) {
            ensureAccepting();
            return document;
        }
    }

    @Override
    public Mono<ConfigDocument> put(InvocationContext context, String key, LiteralValue value) {
        Objects.requireNonNull(context, "context");
        requireKey(key);
        Objects.requireNonNull(value, "value");
        return enqueue(() -> commitPut(key, value));
    }

    @Override
    public Mono<ConfigDocument> remove(InvocationContext context, String key) {
        Objects.requireNonNull(context, "context");
        requireKey(key);
        return enqueue(() -> commitRemove(key));
    }

    @Override
    public Disposable subscribe(InvocationContext context, ConfigChangeListener listener) {
        Objects.requireNonNull(context, "context");
        var registration = new ListenerRegistration(Objects.requireNonNull(listener, "listener"));
        synchronized (lifecycle) {
            ensureAccepting();
            listeners.add(registration);
            try {
                context.effects().add(registration);
            } catch (RuntimeException | Error failure) {
                listeners.remove(registration);
                registration.release();
                throw failure;
            }
        }
        return registration;
    }

    @Override
    public Mono<Void> drain() {
        synchronized (lifecycle) {
            return beginDrain();
        }
    }

    @Override
    public Mono<Void> dispose() {
        synchronized (lifecycle) {
            if (disposed == null) {
                disposed = beginDrain().then(Mono.<Void>fromRunnable(this::release)).cache();
            }
            return disposed;
        }
    }

    private Mono<Void> beginDrain() {
        if (drained == null) {
            accepting = false;
            drained = Mono.fromFuture(tail, true).cache();
        }
        return drained;
    }

    private void release() {
        List<ListenerRegistration> snapshot;
        synchronized (lifecycle) {
            if (released) return;
            released = true;
            snapshot = List.copyOf(listeners);
            listeners.clear();
        }
        snapshot.forEach(ListenerRegistration::release);
        writer.shutdown();
    }

    private Mono<ConfigDocument> enqueue(Callable<ConfigDocument> operation) {
        return Mono.defer(() -> {
            final CompletableFuture<ConfigDocument> result;
            synchronized (lifecycle) {
                ensureAccepting();
                result = tail.handle((ignored, failure) -> null)
                    .thenApplyAsync(ignored -> call(operation), writer);
                tail = result.handle((ignored, failure) -> null);
            }
            return Mono.fromFuture(result, true);
        });
    }

    private ConfigDocument commitPut(String key, LiteralValue value) {
        var values = new LinkedHashMap<>(document.values());
        values.put(key, value);
        return commit(new ConfigDocument(document.revision() + 1, values),
            new ConfigChange(document.revision() + 1, key, ConfigChangeOperation.PUT, value));
    }

    private ConfigDocument commitRemove(String key) {
        if (!document.values().containsKey(key)) return document;
        var values = new LinkedHashMap<>(document.values());
        values.remove(key);
        return commit(new ConfigDocument(document.revision() + 1, values),
            new ConfigChange(document.revision() + 1, key, ConfigChangeOperation.REMOVED, null));
    }

    private ConfigDocument commit(ConfigDocument candidate, ConfigChange change) {
        final StorageWriteResult write;
        try {
            write = storage.writeAtomic(target, JsonDocumentCodec.encode(candidate));
        } catch (IOException | RuntimeException failure) {
            throw new StorageException(StorageErrorCode.PERSISTENCE_FAILED,
                "cannot persist configuration document " + target, failure);
        }
        document = candidate;
        notifyListeners(change);
        warnAfterPublish(write.durabilityWarning());
        return candidate;
    }

    private void warnAfterPublish(Throwable warning) {
        if (warning == null) return;
        try {
            logger.warn("configuration document was published but directory durability could not be confirmed",
                warning);
        } catch (RuntimeException | Error ignored) {
            // A diagnostic failure cannot reverse an already published document.
        }
    }

    private void notifyListeners(ConfigChange change) {
        final List<ListenerRegistration> snapshot;
        synchronized (lifecycle) {
            snapshot = List.copyOf(listeners);
        }
        snapshot.forEach(registration -> registration.deliver(change));
    }

    private ConfigDocument loadInitial(Path path) {
        if (Files.notExists(path)) return ConfigDocument.empty();
        try {
            return JsonDocumentCodec.decode(Files.readAllBytes(path));
        } catch (StorageException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new StorageException(StorageErrorCode.PERSISTENCE_FAILED,
                "cannot read configuration document " + path, failure);
        }
    }

    private ConfigDocument call(Callable<ConfigDocument> operation) {
        try {
            return operation.call();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void ensureAccepting() {
        if (!accepting) {
            throw new StorageException(StorageErrorCode.CLOSED, "configuration store is closed");
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("config key must not be blank");
        }
    }

    interface StorageIo {
        StorageWriteResult writeAtomic(Path target, byte[] content) throws IOException;
    }

    record StorageWriteResult(Throwable durabilityWarning) {
        static StorageWriteResult durable() {
            return new StorageWriteResult(null);
        }
    }

    private final class ListenerRegistration implements Disposable {
        private final ConfigChangeListener listener;
        private boolean inactive;

        private ListenerRegistration(ConfigChangeListener listener) {
            this.listener = listener;
        }

        @Override
        public Mono<Void> dispose() {
            return Mono.fromRunnable(() -> {
                release();
                synchronized (lifecycle) {
                    listeners.remove(this);
                }
            });
        }

        private synchronized void deliver(ConfigChange change) {
            if (inactive) return;
            try {
                listener.changed(change);
            } catch (RuntimeException | Error failure) {
                try {
                    logger.warn("configuration change listener failed", failure);
                } catch (RuntimeException | Error ignored) {
                    // Logging must not reverse a durable commit either.
                }
            }
        }

        private synchronized void release() {
            inactive = true;
        }
    }

    private static final class AtomicStorageIo implements StorageIo {
        @Override
        public StorageWriteResult writeAtomic(Path target, byte[] content) throws IOException {
            var parent = target.getParent();
            if (parent == null) throw new IOException("configuration target has no parent");
            createDirectoriesDurably(parent);
            Path staged = null;
            try {
                staged = Files.createTempFile(parent, ".fibra-config-", ".tmp");
                try (var channel = FileChannel.open(staged, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                    var buffer = ByteBuffer.wrap(content);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
                staged = null;
                try {
                    forceDirectory(parent);
                    return StorageWriteResult.durable();
                } catch (IOException failure) {
                    return new StorageWriteResult(failure);
                }
            } finally {
                if (staged != null) Files.deleteIfExists(staged);
            }
        }

        private static void createDirectoriesDurably(Path directory) throws IOException {
            var missing = new ArrayDeque<Path>();
            var current = directory;
            while (current != null && Files.notExists(current)) {
                missing.push(current);
                current = current.getParent();
            }
            if (current != null && !Files.isDirectory(current)) {
                throw new IOException("configuration parent is not a directory: " + current);
            }
            while (!missing.isEmpty()) {
                var created = missing.pop();
                try {
                    Files.createDirectory(created);
                } catch (java.nio.file.FileAlreadyExistsException failure) {
                    if (!Files.isDirectory(created)) throw failure;
                }
                var parent = created.getParent();
                if (parent != null) forceDirectory(parent);
            }
        }

        private static void forceDirectory(Path directory) throws IOException {
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) return;
            try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
    }
}
