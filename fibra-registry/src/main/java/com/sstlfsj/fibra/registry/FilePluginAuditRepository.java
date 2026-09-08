package com.sstlfsj.fibra.registry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
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
    private final FileLock ownershipLock;
    private final List<PluginAuditEntry> entries;
    private boolean closed;

    public FilePluginAuditRepository(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        try {
            var parent = this.file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            channel = FileChannel.open(this.file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            ownershipLock = channel.tryLock();
            if (ownershipLock == null) {
                channel.close();
                throw new IllegalStateException(
                    "plugin audit file is already owned " + this.file);
            }
            entries = new ArrayList<>(read(this.file));
        } catch (IOException | OverlappingFileLockException failure) {
            throw new IllegalStateException("cannot open plugin audit file " + this.file,
                failure);
        }
    }

    @Override
    public synchronized PluginAuditEntry append(String operation, String target,
                                                boolean succeeded,
                                                String engineRevision, String detail) {
        var entry = new PluginAuditEntry(entries.size() + 1L, Instant.now(), operation,
            target, succeeded, engineRevision, detail);
        var bytes = encode(entry).getBytes(StandardCharsets.UTF_8);
        try {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        } catch (IOException failure) {
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
            return;
        }
        closed = true;
        try {
            ownershipLock.release();
            channel.close();
        } catch (IOException failure) {
            throw new IllegalStateException("cannot close plugin audit file", failure);
        }
    }

    private static List<PluginAuditEntry> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        var result = new ArrayList<PluginAuditEntry>();
        for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            var fields = line.split("\\t", -1);
            if (fields.length != 7) {
                throw new IllegalStateException("invalid plugin audit record");
            }
            var entry = new PluginAuditEntry(Long.parseLong(fields[0]),
                Instant.parse(fields[1]), decode(fields[2]), decode(fields[3]),
                Boolean.parseBoolean(fields[4]), decode(fields[5]), decode(fields[6]));
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
            + entry.succeeded() + "\t" + encode(entry.engineRevision()) + "\t"
            + encode(entry.detail()) + "\n";
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
