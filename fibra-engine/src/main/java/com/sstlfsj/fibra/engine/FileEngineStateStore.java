package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.value.LiteralValue;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 单写者完整目标文件；不从制品 current 指针或事务日志推断目标。 */
public final class FileEngineStateStore implements EngineStateStore {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    private static final StorageIo FILES = new StorageIo() {
        @Override
        public void force(Path path) throws IOException {
            try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }

        @Override
        public void replace(Path staged, Path target) throws IOException {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    };

    private final Path root;
    private final Path target;
    private final StorageIo io;
    private final FileChannel ownershipChannel;
    private boolean closed;
    private EngineStateStoreException closeFailure;

    public FileEngineStateStore(Path root) { this(root, FILES); }

    FileEngineStateStore(Path root, StorageIo io) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.target = this.root.resolve("target.json");
        this.io = Objects.requireNonNull(io, "io");
        FileChannel channel = null;
        FileLock lock = null;
        try {
            createRoot();
            channel = FileChannel.open(this.root.resolve("state.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) throw new IOException("engine state store is already owned");
            io.force(this.root);
        } catch (IOException | RuntimeException failure) {
            closeOnFailure(lock, failure);
            closeOnFailure(channel, failure);
            throw new EngineStateStoreException("cannot open engine state store", this.root, failure);
        }
        ownershipChannel = channel;
    }

    @Override
    public synchronized Optional<DeploymentManifest> load() {
        ensureOpen();
        if (Files.notExists(target)) return Optional.empty();
        try {
            return Optional.of(decode(Files.readAllBytes(target)));
        } catch (IOException | RuntimeException failure) {
            throw new EngineStateStoreException("cannot load deployment target", target, failure);
        }
    }

    @Override
    public synchronized void save(DeploymentManifest manifest) {
        ensureOpen();
        var bytes = encode(Objects.requireNonNull(manifest, "manifest"));
        if (!manifest.equals(decode(bytes))) {
            throw new IllegalArgumentException("deployment target does not round-trip through its storage format");
        }
        Path staged = null;
        var replacementAttempted = false;
        try {
            staged = Files.createTempFile(root, ".target-", ".tmp");
            Files.write(staged, bytes);
            io.force(staged);
            replacementAttempted = true;
            io.replace(staged, target);
            staged = null;
            io.force(root);
        } catch (IOException | RuntimeException failure) {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (replacementAttempted) throw new SaveUnconfirmedException(target, failure);
            throw new EngineStateStoreException("cannot save deployment target", target, failure);
        }
    }

    @Override
    public synchronized void close() {
        if (closeFailure != null) throw closeFailure;
        if (closed) return;
        closed = true;
        try {
            io.close(ownershipChannel);
        } catch (IOException failure) {
            closeFailure = new EngineStateStoreException("cannot close engine state store", root, failure);
            throw closeFailure;
        }
    }

    private void createRoot() throws IOException {
        Files.createDirectories(root);
        // 已存在的目录也可能刚由其他创建者建立，存在性不能证明父目录项已经落盘。
        for (var directory = root; directory != null; directory = directory.getParent()) {
            io.force(directory);
        }
    }

    private static byte[] encode(DeploymentManifest manifest) {
        var content = DeploymentManifestCodec.encode(manifest);
        return ("{\"manifest\":" + new String(content, StandardCharsets.UTF_8)
            + ",\"revision\":\"" + DeploymentManifest.digest(content) + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static DeploymentManifest decode(byte[] bytes) {
        var value = LiteralValue.of(JSON.readValue(bytes, Object.class));
        if (!(value instanceof LiteralValue.ObjectValue envelope)
            || !envelope.values().keySet().equals(Set.of("revision", "manifest"))) {
            throw new IllegalArgumentException("invalid deployment target envelope");
        }
        if (!(envelope.values().get("revision") instanceof LiteralValue.StringValue revision)) {
            throw new IllegalArgumentException("invalid deployment target revision");
        }
        DeploymentManifest.validateRevision(revision.value());
        var content = envelope.values().get("manifest").canonicalJson().getBytes(StandardCharsets.UTF_8);
        if (!revision.value().equals(DeploymentManifest.digest(content))) {
            throw new IllegalArgumentException("deployment target digest mismatch");
        }
        var manifest = DeploymentManifestCodec.decode(content);
        if (!Arrays.equals(bytes, encode(manifest))) {
            throw new IllegalArgumentException("deployment target encoding is not canonical");
        }
        return manifest;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("engine state store is closed");
    }

    private static void closeOnFailure(AutoCloseable resource, Throwable failure) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    interface StorageIo {
        void force(Path path) throws IOException;
        void replace(Path staged, Path target) throws IOException;

        default void close(FileChannel channel) throws IOException {
            channel.close();
        }
    }
}
