package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.loader.config.FibraConfigEntry;
import com.sstlfsj.fibra.loader.config.FibraConfigSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

final class EngineRevision {
    private static final int STREAM_BUFFER_SIZE = 8192;

    private EngineRevision() { }

    static String artifacts(Collection<RevisionArtifact> artifacts) {
        var digest = digest();
        text(digest, "fibra-artifacts-v1");
        artifacts.stream().sorted(java.util.Comparator.comparing(RevisionArtifact::id))
            .forEach(artifact -> {
                text(digest, artifact.id());
                text(digest, artifact.version());
                text(digest, artifact.sha256());
            });
        return hex(digest);
    }

    static String config(FibraConfigSnapshot snapshot) {
        var digest = digest();
        text(digest, "fibra-config-v1");
        snapshot.entries().forEach(entry -> entry(digest, entry));
        return hex(digest);
    }

    static String sourceFiles(Collection<Path> paths) {
        var digest = digest();
        text(digest, "fibra-source-files-v2");
        for (var path : paths.stream().map(candidate -> candidate.toAbsolutePath().normalize())
            .sorted().toList()) {
            text(digest, path.toString());
            try {
                if (Files.isRegularFile(path)) {
                    stream(digest, path);
                } else {
                    text(digest, "<missing>");
                }
            } catch (IOException exception) {
                throw new IllegalStateException(
                    "cannot calculate Fibra source revision for " + path, exception);
            }
        }
        return hex(digest);
    }

    private static void stream(MessageDigest digest, Path path) throws IOException {
        var expectedSize = Files.size(path);
        longInteger(digest, expectedSize);
        var actualSize = 0L;
        var buffer = new byte[STREAM_BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                digest.update(buffer, 0, read);
                actualSize += read;
            }
        }
        if (actualSize != expectedSize) {
            throw new IllegalStateException(
                "source file changed while calculating Fibra source revision: " + path);
        }
    }

    static String combine(String artifactRevision, String configRevision) {
        var digest = digest();
        text(digest, "fibra-engine-v1");
        text(digest, artifactRevision);
        text(digest, configRevision);
        return hex(digest);
    }

    private static void entry(MessageDigest digest, FibraConfigEntry entry) {
        text(digest, "entry");
        text(digest, entry.entryId());
        text(digest, entry.kind().name());
        nullableText(digest, entry.pluginId());
        bool(digest, entry.disabled());
        value(digest, entry.config());
        value(digest, entry.inject());
        value(digest, entry.intercept());
        value(digest, entry.isolate());
        integer(digest, entry.children().size());
        entry.children().forEach(child -> entry(digest, child));
    }

    private static void value(MessageDigest digest, Object value) {
        if (value == null) {
            text(digest, "null");
        } else if (value instanceof String string) {
            text(digest, "string");
            text(digest, string);
        } else if (value instanceof Boolean bool) {
            text(digest, "boolean");
            bool(digest, bool);
        } else if (value instanceof Number number) {
            text(digest, "number");
            text(digest, number.getClass().getName());
            text(digest, number.toString());
        } else if (value instanceof List<?> list) {
            text(digest, "list");
            integer(digest, list.size());
            list.forEach(item -> value(digest, item));
        } else if (value instanceof Map<?, ?> map) {
            text(digest, "map");
            integer(digest, map.size());
            map.entrySet().stream().sorted(java.util.Comparator.comparing(item ->
                (String) item.getKey())).forEach(item -> {
                    text(digest, (String) item.getKey());
                    value(digest, item.getValue());
                });
        } else {
            throw new IllegalArgumentException("unsupported config revision value type "
                + value.getClass().getName());
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void nullableText(MessageDigest digest, String value) {
        if (value == null) {
            integer(digest, -1);
        } else {
            text(digest, value);
        }
    }

    private static void text(MessageDigest digest, String value) {
        bytes(digest, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void bool(MessageDigest digest, boolean value) {
        digest.update(value ? (byte) 1 : (byte) 0);
    }

    private static void bytes(MessageDigest digest, byte[] value) {
        integer(digest, value.length);
        digest.update(value);
    }

    private static void integer(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static void longInteger(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static String hex(MessageDigest digest) {
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
}
