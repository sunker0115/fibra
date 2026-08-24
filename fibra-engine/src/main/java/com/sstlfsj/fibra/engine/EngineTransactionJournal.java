package com.sstlfsj.fibra.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

record EngineTransactionJournal(String transactionId, EngineTransactionState state,
                                String deploymentId, String deploymentVersion,
                                String packageDigest, String appliedRevision,
                                List<Artifact> artifacts, List<ConfigFile> configFiles) {
    static final String FILE_NAME = "journal.properties";

    EngineTransactionJournal {
        requireText(transactionId, "transactionId");
        Objects.requireNonNull(state, "state");
        artifacts = List.copyOf(artifacts);
        configFiles = List.copyOf(configFiles);
        requireSortedUnique(artifacts.stream().map(Artifact::id).toList(), "artifact ids");
        requireSortedUnique(configFiles.stream().map(ConfigFile::relativePath).toList(),
            "config paths");
        if (state == EngineTransactionState.PREPARING) {
            if (deploymentId != null || deploymentVersion != null || packageDigest != null
                || appliedRevision != null || !artifacts.isEmpty() || !configFiles.isEmpty()) {
                throw new IllegalArgumentException(
                    "PREPARING journal must not contain prepared payload");
            }
        } else {
            requireText(deploymentId, "deploymentId");
            requireText(deploymentVersion, "deploymentVersion");
            requireDigest(packageDigest, "packageDigest");
            if (state == EngineTransactionState.COMMITTED) {
                requireDigest(appliedRevision, "appliedRevision");
            } else if (appliedRevision != null) {
                throw new IllegalArgumentException(
                    "only COMMITTED journal may contain appliedRevision");
            }
        }
    }

    static EngineTransactionJournal preparing(String transactionId) {
        return new EngineTransactionJournal(transactionId, EngineTransactionState.PREPARING,
            null, null, null, null, List.of(), List.of());
    }

    EngineTransactionJournal prepared(String id, String version, String digest,
                                      List<Artifact> nextArtifacts,
                                      List<ConfigFile> nextConfigFiles) {
        if (state != EngineTransactionState.PREPARING) {
            throw new IllegalStateException("prepared requires PREPARING state");
        }
        return new EngineTransactionJournal(transactionId, EngineTransactionState.PREPARED,
            id, version, digest, null, nextArtifacts, nextConfigFiles);
    }

    EngineTransactionJournal advance(EngineTransactionState next) {
        Objects.requireNonNull(next, "next");
        var valid = switch (state) {
            case PREPARED -> next == EngineTransactionState.COMMITTING_ARTIFACTS;
            case COMMITTING_ARTIFACTS -> next == EngineTransactionState.COMMITTING_CONFIG;
            case COMMITTING_CONFIG -> next == EngineTransactionState.VERIFYING;
            default -> false;
        };
        if (!valid) {
            throw new IllegalStateException("invalid engine transaction transition "
                + state + " -> " + next);
        }
        return new EngineTransactionJournal(transactionId, next, deploymentId,
            deploymentVersion, packageDigest, null, artifacts, configFiles);
    }

    EngineTransactionJournal rollingBack() {
        if (state == EngineTransactionState.PREPARING
            || state == EngineTransactionState.COMMITTED) {
            throw new IllegalStateException("cannot roll back engine transaction in " + state);
        }
        return new EngineTransactionJournal(transactionId,
            EngineTransactionState.ROLLING_BACK, deploymentId, deploymentVersion,
            packageDigest, null, artifacts, configFiles);
    }

    EngineTransactionJournal committed(String revision) {
        if (state != EngineTransactionState.VERIFYING) {
            throw new IllegalStateException("committed requires VERIFYING state");
        }
        return new EngineTransactionJournal(transactionId, EngineTransactionState.COMMITTED,
            deploymentId, deploymentVersion, packageDigest, revision, artifacts,
            configFiles);
    }

    void write(Path transactionRoot) throws IOException {
        Files.createDirectories(transactionRoot);
        var temporary = Files.createTempFile(transactionRoot, ".journal-", ".tmp");
        try {
            var bytes = serialize().getBytes(StandardCharsets.UTF_8);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
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

    static EngineTransactionJournal read(Path transactionRoot) throws IOException {
        var values = parse(Files.readAllLines(transactionRoot.resolve(FILE_NAME),
            StandardCharsets.UTF_8));
        var transactionId = required(values, "transaction.id");
        final EngineTransactionState state;
        try {
            state = EngineTransactionState.valueOf(required(values, "state"));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("invalid engine transaction state", failure);
        }
        if (state == EngineTransactionState.PREPARING) {
            requireExact(values, new LinkedHashSet<>(List.of("transaction.id", "state")));
            return preparing(transactionId);
        }
        var deploymentId = required(values, "deployment.id");
        var deploymentVersion = required(values, "deployment.version");
        var packageDigest = required(values, "package.digest");
        var artifactCount = positiveCount(values, "artifact.count");
        var configCount = positiveCount(values, "config.count");
        var expected = new LinkedHashSet<>(List.of("transaction.id", "state",
            "deployment.id", "deployment.version", "package.digest", "artifact.count",
            "config.count"));
        String revision = null;
        if (state == EngineTransactionState.COMMITTED) {
            revision = required(values, "applied.revision");
            expected.add("applied.revision");
        }
        var artifacts = new ArrayList<Artifact>();
        for (int index = 0; index < artifactCount; index++) {
            var prefix = "artifact." + index + '.';
            var oldExists = booleanValue(values, prefix + "old.exists");
            expected.add(prefix + "id");
            expected.add(prefix + "old.exists");
            expected.add(prefix + "new.digest");
            if (oldExists) {
                expected.add(prefix + "old.digest");
            }
            artifacts.add(new Artifact(required(values, prefix + "id"), oldExists,
                oldExists ? required(values, prefix + "old.digest") : null,
                required(values, prefix + "new.digest")));
        }
        var configs = new ArrayList<ConfigFile>();
        for (int index = 0; index < configCount; index++) {
            var prefix = "config." + index + '.';
            var oldExists = booleanValue(values, prefix + "old.exists");
            expected.add(prefix + "path");
            expected.add(prefix + "old.exists");
            expected.add(prefix + "new.digest");
            if (oldExists) {
                expected.add(prefix + "old.digest");
            }
            configs.add(new ConfigFile(required(values, prefix + "path"), oldExists,
                oldExists ? required(values, prefix + "old.digest") : null,
                required(values, prefix + "new.digest")));
        }
        requireExact(values, expected);
        return new EngineTransactionJournal(transactionId, state, deploymentId,
            deploymentVersion, packageDigest, revision, artifacts, configs);
    }

    static String digest(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                updateFile(digest, path);
            } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                try (var paths = Files.walk(path)) {
                    for (var file : paths.filter(candidate -> Files.isRegularFile(candidate,
                        LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                        digest.update(path.relativize(file).toString()
                            .replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                        updateFile(digest, file);
                    }
                }
            } else {
                throw new IOException("digest path is not a regular file or directory: " + path);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    static void forceDirectory(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private String serialize() {
        var result = new StringBuilder();
        line(result, "transaction.id", transactionId);
        line(result, "state", state.name());
        if (state == EngineTransactionState.PREPARING) {
            return result.toString();
        }
        line(result, "deployment.id", deploymentId);
        line(result, "deployment.version", deploymentVersion);
        line(result, "package.digest", packageDigest);
        if (appliedRevision != null) {
            line(result, "applied.revision", appliedRevision);
        }
        line(result, "artifact.count", Integer.toString(artifacts.size()));
        line(result, "config.count", Integer.toString(configFiles.size()));
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
        for (int index = 0; index < configFiles.size(); index++) {
            var config = configFiles.get(index);
            var prefix = "config." + index + '.';
            line(result, prefix + "path", config.relativePath());
            line(result, prefix + "old.exists", Boolean.toString(config.oldExists()));
            if (config.oldExists()) {
                line(result, prefix + "old.digest", config.oldDigest());
            }
            line(result, prefix + "new.digest", config.newDigest());
        }
        return result.toString();
    }

    private static void updateFile(MessageDigest digest, Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            var buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0; ) {
                digest.update(buffer, 0, read);
            }
        }
    }

    private static Map<String, String> parse(List<String> lines) {
        var values = new LinkedHashMap<String, String>();
        for (var line : lines) {
            if (line.isBlank()) {
                continue;
            }
            var separator = line.indexOf('=');
            if (separator <= 0 || values.putIfAbsent(line.substring(0, separator),
                line.substring(separator + 1)) != null) {
                throw new IllegalArgumentException("invalid or duplicate journal field");
            }
        }
        return values;
    }

    private static int positiveCount(Map<String, String> values, String key) {
        try {
            var count = Integer.parseInt(required(values, key));
            if (count < 0) {
                throw new IllegalArgumentException(key + " must not be negative");
            }
            return count;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " is invalid", failure);
        }
    }

    private static boolean booleanValue(Map<String, String> values, String key) {
        return switch (required(values, key)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(key + " must be true or false");
        };
    }

    private static String required(Map<String, String> values, String key) {
        var value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing engine journal field " + key);
        }
        return value;
    }

    private static void requireExact(Map<String, String> values,
                                     LinkedHashSet<String> expected) {
        if (!values.keySet().equals(expected)) {
            throw new IllegalArgumentException("engine journal fields do not match");
        }
    }

    private static void line(StringBuilder result, String key, String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("journal value must be one line");
        }
        result.append(key).append('=').append(value).append('\n');
    }

    private static void requireSortedUnique(List<String> values, String name) {
        if (!values.equals(values.stream().sorted().toList())
            || new LinkedHashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(name + " must be unique and sorted");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireDigest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
    }

    record Artifact(String id, boolean oldExists, String oldDigest, String newDigest) {
        Artifact {
            requireText(id, "artifact id");
            requireDigest(newDigest, "new artifact digest");
            if (oldExists) {
                requireDigest(oldDigest, "old artifact digest");
            } else if (oldDigest != null) {
                throw new IllegalArgumentException("new artifact must not have old digest");
            }
        }
    }

    record ConfigFile(String relativePath, boolean oldExists, String oldDigest,
                      String newDigest) {
        ConfigFile {
            requireText(relativePath, "config relative path");
            var normalized = Path.of(relativePath).normalize();
            if (normalized.isAbsolute() || normalized.startsWith("..")
                || !normalized.toString().replace('\\', '/').equals(relativePath)) {
                throw new IllegalArgumentException("config path must be normalized and relative");
            }
            requireDigest(newDigest, "new config digest");
            if (oldExists) {
                requireDigest(oldDigest, "old config digest");
            } else if (oldDigest != null) {
                throw new IllegalArgumentException("new config must not have old digest");
            }
        }
    }
}
