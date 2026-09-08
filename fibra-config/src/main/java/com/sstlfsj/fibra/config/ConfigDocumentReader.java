package com.sstlfsj.fibra.config;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class ConfigDocumentReader {
    private final ConfigLimits limits;
    private final ObjectMapper json;
    private final ObjectMapper yaml;

    ConfigDocumentReader(ConfigLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        var constraints = StreamReadConstraints.builder()
            .maxNestingDepth(limits.maxDepth())
            .maxStringLength(limits.maxStringLength())
            .maxDocumentLength(limits.maxFileBytes())
            .build();
        json = JsonMapper.builder(JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build())
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
        yaml = YAMLMapper.builder(YAMLFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build())
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    }

    Document read(Path input, String entryId) {
        var normalized = Objects.requireNonNull(input, "path").toAbsolutePath().normalize();
        var extension = extension(normalized);
        if (!extension.equals("yaml") && !extension.equals("yml")
            && !extension.equals("json")) {
            throw error(ConfigStage.READ, "UNSUPPORTED_FORMAT",
                "config path must end with .yaml, .yml or .json", normalized, entryId, null);
        }

        Path path;
        byte[] bytes;
        try {
            path = normalized.toRealPath();
            if (Files.size(path) > limits.maxFileBytes()) {
                throw error(ConfigStage.READ, "FILE_TOO_LARGE",
                    "config file exceeds " + limits.maxFileBytes() + " bytes",
                    path, entryId, null);
            }
            bytes = Files.readAllBytes(path);
        } catch (ConfigException exception) {
            throw exception;
        } catch (IOException exception) {
            throw error(ConfigStage.READ, "READ_FAILED",
                "cannot read config file " + normalized, normalized, entryId, exception);
        }

        return read(path, bytes, entryId);
    }

    Document read(Path input, byte[] bytes, String entryId) {
        var path = Objects.requireNonNull(input, "path").toAbsolutePath().normalize();
        Objects.requireNonNull(bytes, "bytes");
        var extension = extension(path);
        if (!extension.equals("yaml") && !extension.equals("yml")
            && !extension.equals("json")) {
            throw error(ConfigStage.READ, "UNSUPPORTED_FORMAT",
                "config path must end with .yaml, .yml or .json", path, entryId, null);
        }
        if (bytes.length > limits.maxFileBytes()) {
            throw error(ConfigStage.READ, "FILE_TOO_LARGE",
                "config file exceeds " + limits.maxFileBytes() + " bytes",
                path, entryId, null);
        }

        Object value;
        try {
            value = (extension.equals("json") ? json : yaml).readValue(bytes, Object.class);
        } catch (RuntimeException exception) {
            throw error(ConfigStage.PARSE, "PARSE_FAILED",
                "cannot parse config file " + path, path, entryId, exception);
        }
        if (!(value instanceof List<?> list)) {
            throw error(ConfigStage.VALIDATE, "ROOT_NOT_ARRAY",
                "config root must be an array", path, entryId, null);
        }
        if (list.size() > limits.maxEntriesPerFile()) {
            throw error(ConfigStage.VALIDATE, "TOO_MANY_ENTRIES",
                "config file exceeds " + limits.maxEntriesPerFile() + " root entries",
                path, entryId, null);
        }
        try {
            for (var entry : list) {
                if (!(entry instanceof java.util.Map<?, ?>)) {
                    throw new IllegalArgumentException("every config entry must be an object");
                }
            }
            var entries = LiteralValues.freezeEntries(list);
            if (countEntries(entries) > limits.maxEntriesPerFile()) {
                throw new IllegalArgumentException("config file exceeds "
                    + limits.maxEntriesPerFile() + " entries");
            }
            return new Document(path, bytes, entries);
        } catch (IllegalArgumentException exception) {
            throw error(ConfigStage.VALIDATE, "INVALID_LITERAL",
                exception.getMessage(), path, entryId, exception);
        }
    }

    private static int countEntries(List<java.util.Map<String, Object>> entries) {
        var count = entries.size();
        for (var entry : entries) {
            if (Boolean.TRUE.equals(entry.get("group"))
                && entry.get("entries") instanceof List<?> children) {
                count += children.size();
                for (var child : children) {
                    if (child instanceof java.util.Map<?, ?> childMap
                        && Boolean.TRUE.equals(childMap.get("group"))
                        && childMap.get("entries") instanceof List<?> grandchildren) {
                        count += countNested(grandchildren);
                    }
                }
            }
        }
        return count;
    }

    private static int countNested(List<?> entries) {
        var count = entries.size();
        for (var entry : entries) {
            if (entry instanceof java.util.Map<?, ?> map
                && Boolean.TRUE.equals(map.get("group"))
                && map.get("entries") instanceof List<?> children) {
                count += countNested(children);
            }
        }
        return count;
    }

    private static String extension(Path path) {
        var name = path.getFileName().toString();
        var separator = name.lastIndexOf('.');
        return separator < 0 ? "" : name.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    private static ConfigException error(ConfigStage stage, String code, String message,
                                         Path source, String entryId, Throwable cause) {
        return new ConfigException(
            new ConfigDiagnostic(stage, code, message, source, entryId), cause);
    }

    record Document(Path path, byte[] bytes, List<java.util.Map<String, Object>> entries) {
        Document {
            bytes = bytes.clone();
            entries = List.copyOf(entries);
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
