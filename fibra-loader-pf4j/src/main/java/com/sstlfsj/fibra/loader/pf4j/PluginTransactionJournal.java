package com.sstlfsj.fibra.loader.pf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

record PluginTransactionJournal(String transactionId, PluginTransactionState state,
                                List<Artifact> artifacts, CleanupOutcome cleanupOutcome) {
    static final String FILE_NAME = "journal.properties";

    PluginTransactionJournal {
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("transaction id must not be blank");
        }
        Objects.requireNonNull(state, "state");
        if (state == PluginTransactionState.COMMITTED && cleanupOutcome != null) {
            throw new IllegalArgumentException(
                "committed plugin transaction cannot declare rollback cleanup");
        }
        artifacts = List.copyOf(artifacts);
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException("transaction artifacts must not be empty");
        }
        var ids = artifacts.stream().map(Artifact::id).toList();
        if (!ids.equals(ids.stream().sorted().toList())
            || new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException(
                "transaction artifact ids must be unique and sorted");
        }
    }

    static PluginTransactionJournal prepared(String transactionId, List<Artifact> artifacts) {
        return new PluginTransactionJournal(transactionId, PluginTransactionState.PREPARED,
            artifacts, null);
    }

    static PluginTransactionJournal read(Path transactionRoot) throws IOException {
        var values = parse(Files.readAllLines(transactionRoot.resolve(FILE_NAME),
            StandardCharsets.UTF_8));
        var transactionId = required(values, "transaction.id");
        final PluginTransactionState state;
        final int count;
        try {
            state = PluginTransactionState.valueOf(required(values, "state"));
            count = Integer.parseInt(required(values, "artifact.count"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid plugin transaction journal", exception);
        }
        if (count <= 0) {
            throw new IllegalArgumentException("artifact.count must be positive");
        }
        var artifacts = new ArrayList<Artifact>(count);
        var expected = new LinkedHashSet<>(List.of(
            "transaction.id", "state", "artifact.count"));
        CleanupOutcome cleanupOutcome = null;
        if (values.containsKey("cleanup.outcome")) {
            expected.add("cleanup.outcome");
            try {
                cleanupOutcome = CleanupOutcome.valueOf(required(values, "cleanup.outcome"));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("invalid plugin transaction cleanup outcome",
                    exception);
            }
        }
        for (int index = 0; index < count; index++) {
            var prefix = "artifact." + index + '.';
            var idKey = prefix + "id";
            var existsKey = prefix + "old.exists";
            var oldKey = prefix + "old.digest";
            var nextKey = prefix + "new.digest";
            expected.add(idKey);
            expected.add(existsKey);
            expected.add(nextKey);
            var oldExists = switch (required(values, existsKey)) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new IllegalArgumentException(
                    existsKey + " must be true or false");
            };
            if (oldExists) {
                expected.add(oldKey);
            }
            artifacts.add(new Artifact(required(values, idKey), oldExists,
                oldExists ? required(values, oldKey) : null, required(values, nextKey)));
        }
        if (!values.keySet().equals(expected)) {
            throw new IllegalArgumentException("plugin transaction journal fields do not match");
        }
        return new PluginTransactionJournal(transactionId, state, artifacts, cleanupOutcome);
    }

    PluginTransactionJournal advance(PluginTransactionState next) {
        Objects.requireNonNull(next, "next");
        if (state.next() != next) {
            throw new IllegalStateException("invalid plugin transaction transition "
                + state + " -> " + next);
        }
        return new PluginTransactionJournal(transactionId, next, artifacts, null);
    }

    PluginTransactionJournal markRollbackCleanup() {
        if (state == PluginTransactionState.COMMITTED) {
            throw new IllegalStateException("committed transaction cannot roll back");
        }
        return new PluginTransactionJournal(transactionId, state, artifacts,
            CleanupOutcome.ROLLBACK);
    }

    void write(Path transactionRoot) throws IOException {
        Files.createDirectories(transactionRoot);
        var temporary = Files.createTempFile(transactionRoot, ".journal-", ".tmp");
        try {
            var content = serialize().getBytes(StandardCharsets.UTF_8);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, transactionRoot.resolve(FILE_NAME),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(transactionRoot);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String serialize() {
        var result = new StringBuilder();
        line(result, "transaction.id", transactionId);
        line(result, "state", state.name());
        line(result, "artifact.count", Integer.toString(artifacts.size()));
        if (cleanupOutcome != null) {
            line(result, "cleanup.outcome", cleanupOutcome.name());
        }
        for (int index = 0; index < artifacts.size(); index++) {
            var artifact = artifacts.get(index);
            var prefix = "artifact." + index + '.';
            line(result, prefix + "id", artifact.id());
            line(result, prefix + "old.exists", Boolean.toString(artifact.oldExists()));
            if (artifact.oldExists()) {
                line(result, prefix + "old.digest", artifact.oldDigest());
            }
            line(result, prefix + "new.digest", artifact.newDigest());
        }
        return result.toString();
    }

    static void moveDurably(Path source, Path target) throws IOException {
        var sourceParent = source.getParent();
        var targetParent = target.getParent();
        Files.createDirectories(targetParent);
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(sourceParent);
        if (!sourceParent.equals(targetParent)) {
            forceDirectory(targetParent);
        }
    }

    static void forceDirectory(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static Map<String, String> parse(List<String> lines) {
        var values = new LinkedHashMap<String, String>();
        for (var line : lines) {
            if (line.isBlank()) {
                continue;
            }
            var separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("invalid plugin transaction journal line");
            }
            var key = line.substring(0, separator);
            var value = line.substring(separator + 1);
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException(
                    "duplicate plugin transaction journal field " + key);
            }
        }
        return values;
    }

    private static String required(Map<String, String> values, String key) {
        var value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing plugin transaction journal field " + key);
        }
        return value;
    }

    private static void line(StringBuilder target, String key, String value) {
        target.append(key).append('=').append(value).append('\n');
    }

    record Artifact(String id, boolean oldExists, String oldDigest, String newDigest) {
        Artifact {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("artifact id must not be blank");
            }
            if (newDigest == null || newDigest.isBlank()) {
                throw new IllegalArgumentException("new artifact digest must not be blank");
            }
            if (oldExists != (oldDigest != null && !oldDigest.isBlank())) {
                throw new IllegalArgumentException(
                    "old artifact digest must match old existence");
            }
        }
    }

    enum CleanupOutcome {
        ROLLBACK
    }
}
