package com.sstlfsj.fibra.registry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

public final class FilePluginAuditRepository implements PluginAuditRepository {
    private final Path file;
    private final FileChannel channel;
    private final FilePluginAuditIo io;
    private final List<PluginAuditEntry> entries;
    private boolean closed;
    private IOException appendFailure;
    private IOException closeFailure;

    public FilePluginAuditRepository(Path file) {
        this(file, new FilePluginAuditIo() { });
    }

    FilePluginAuditRepository(Path file, FilePluginAuditIo io) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.io = Objects.requireNonNull(io, "io");
        FileChannel opened = null;
        FileLock lock = null;
        List<PluginAuditEntry> loaded = List.of();
        try {
            var parent = this.file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            opened = FileChannel.open(this.file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            lock = opened.tryLock();
            if (lock == null) throw new IllegalStateException(
                "plugin audit file is already owned " + this.file);
            loaded = new ArrayList<>(read(this.file));
        } catch (IOException | RuntimeException failure) {
            closeOnFailure(lock, failure);
            closeOnFailure(opened, failure);
            throw new IllegalStateException("cannot open plugin audit file " + this.file,
                failure);
        }
        channel = opened;
        entries = loaded;
    }

    @Override
    public synchronized PluginAuditEntry append(String operation, String target,
                                                boolean succeeded,
                                                TargetSaveState targetSaveState,
                                                String viewRevision, String detail) {
        if (closed) {
            throw new IllegalStateException("plugin audit repository is closed");
        }
        if (appendFailure != null) {
            throw new IllegalStateException("cannot append after plugin audit failure",
                appendFailure);
        }
        var entry = PluginAuditEntry.builder().sequence(entries.size() + 1L)
            .timestamp(Instant.now()).operation(operation).target(target)
            .succeeded(succeeded).targetSaveState(targetSaveState)
            .viewRevision(viewRevision).detail(detail).build();
        var bytes = ByteBuffer.wrap(encode(entry).getBytes(StandardCharsets.UTF_8));
        try {
            while (bytes.hasRemaining()) {
                if (io.write(channel, bytes) <= 0) {
                    throw new IOException("plugin audit write made no progress");
                }
            }
            io.force(channel, true);
        } catch (IOException failure) {
            appendFailure = failure;
            throw new IllegalStateException("cannot append plugin audit entry", failure);
        }
        entries.add(entry);
        return entry;
    }

    @Override
    public synchronized List<PluginAuditEntry> history() {
        return List.copyOf(entries);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            if (closeFailure != null) {
                throw new IllegalStateException("cannot close plugin audit file", closeFailure);
            }
            return;
        }
        closed = true;
        try {
            io.close(channel);
        } catch (IOException failure) {
            closeFailure = failure;
            throw new IllegalStateException("cannot close plugin audit file", failure);
        }
    }

    private static List<PluginAuditEntry> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        var content = Files.readString(file, StandardCharsets.UTF_8);
        if (!content.isEmpty() && !content.endsWith("\n")) {
            throw new IllegalStateException("plugin audit file has an unterminated record");
        }
        var result = new ArrayList<PluginAuditEntry>();
        for (var line : content.lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            var fields = line.split("\\t", -1);
            if (fields.length != 8) {
                throw new IllegalStateException("invalid plugin audit record");
            }
            var entry = PluginAuditEntry.builder().sequence(Long.parseLong(fields[0]))
                .timestamp(Instant.parse(fields[1])).operation(decode(fields[2]))
                .target(decode(fields[3])).succeeded(decodeBoolean(fields[4]))
                .targetSaveState(TargetSaveState.valueOf(fields[5]))
                .viewRevision(decode(fields[6])).detail(decode(fields[7])).build();
            if (entry.sequence() != result.size() + 1L) {
                throw new IllegalStateException("non-contiguous plugin audit sequence");
            }
            result.add(entry);
        }
        return result;
    }

    private static String encode(PluginAuditEntry entry) {
        return entry.sequence() + "\t" + entry.timestamp() + "\t"
            + encode(entry.operation()) + "\t" + encode(entry.target()) + "\t"
            + entry.succeeded() + "\t" + entry.targetSaveState() + "\t"
            + encode(entry.viewRevision()) + "\t" + encode(entry.detail()) + "\n";
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static boolean decodeBoolean(String value) {
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IllegalStateException("invalid plugin audit succeeded value");
    }

    private static void closeOnFailure(AutoCloseable resource, Throwable failure) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}

interface FilePluginAuditIo {
    default int write(FileChannel channel, ByteBuffer bytes) throws IOException {
        return channel.write(bytes);
    }

    default void force(FileChannel channel, boolean metaData) throws IOException {
        channel.force(metaData);
    }

    default void close(FileChannel channel) throws IOException {
        channel.close();
    }
}
