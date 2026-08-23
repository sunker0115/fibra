package com.sstlfsj.fibra.loader.config;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        var jsonFactory = JsonFactory.builder()
            .streamReadConstraints(constraints)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
        var yamlFactory = YAMLFactory.builder()
            .streamReadConstraints(constraints)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
        this.json = JsonMapper.builder(jsonFactory)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
        this.yaml = YAMLMapper.builder(yamlFactory)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    }

    List<Map<String, Object>> read(Path input) {
        Objects.requireNonNull(input, "path");
        var path = input.toAbsolutePath().normalize();
        var extension = extension(path);
        if (!extension.equals("yaml") && !extension.equals("yml")
            && !extension.equals("json")) {
            throw FibraConfigException.at(FibraConfigErrorStage.READ, path,
                "config path must end with .yaml, .yml or .json", null);
        }

        byte[] bytes;
        try {
            path = path.toRealPath();
            var size = Files.size(path);
            if (size > limits.maxFileBytes()) {
                throw FibraConfigException.at(FibraConfigErrorStage.READ, path,
                    "config file exceeds " + limits.maxFileBytes() + " bytes", null);
            }
            bytes = Files.readAllBytes(path);
        } catch (FibraConfigException exception) {
            throw exception;
        } catch (IOException exception) {
            throw FibraConfigException.at(FibraConfigErrorStage.READ, path,
                "cannot read config file " + path, exception);
        }

        return read(path, bytes);
    }

    List<Map<String, Object>> read(Path input, byte[] bytes) {
        Objects.requireNonNull(input, "path");
        Objects.requireNonNull(bytes, "bytes");
        var path = input.toAbsolutePath().normalize();
        var extension = extension(path);
        if (!extension.equals("yaml") && !extension.equals("yml")
            && !extension.equals("json")) {
            throw FibraConfigException.at(FibraConfigErrorStage.READ, path,
                "config path must end with .yaml, .yml or .json", null);
        }
        if (bytes.length > limits.maxFileBytes()) {
            throw FibraConfigException.at(FibraConfigErrorStage.READ, path,
                "config file exceeds " + limits.maxFileBytes() + " bytes", null);
        }

        Object value;
        try {
            value = mapper(extension).readValue(bytes, Object.class);
        } catch (RuntimeException exception) {
            throw FibraConfigException.at(FibraConfigErrorStage.PARSE, path,
                "cannot parse config file " + path, exception);
        }
        if (!(value instanceof List<?> list)) {
            throw FibraConfigException.at(FibraConfigErrorStage.VALIDATE, path,
                "config root must be an array", null);
        }
        if (list.size() > limits.maxEntriesPerFile()) {
            throw FibraConfigException.at(FibraConfigErrorStage.VALIDATE, path,
                "config file exceeds " + limits.maxEntriesPerFile() + " root entries", null);
        }

        var result = new ArrayList<Map<String, Object>>(list.size());
        for (var element : list) {
            if (!(element instanceof Map<?, ?> map)) {
                throw FibraConfigException.at(FibraConfigErrorStage.VALIDATE, path,
                    "every config entry must be an object", null);
            }
            result.add(freezeStringMap(map, path));
        }
        return Collections.unmodifiableList(result);
    }

    private ObjectMapper mapper(String extension) {
        return extension.equals("json") ? json : yaml;
    }

    private static String extension(Path path) {
        var name = path.getFileName().toString();
        var separator = name.lastIndexOf('.');
        return separator < 0 ? "" : name.substring(separator + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static Map<String, Object> freezeStringMap(Map<?, ?> source, Path path) {
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (!(key instanceof String name)) {
                throw FibraConfigException.at(FibraConfigErrorStage.VALIDATE, path,
                    "config object keys must be strings", null);
            }
            result.put(name, freeze(value, path));
        });
        return Collections.unmodifiableMap(result);
    }

    private static Object freeze(Object value, Path path) {
        if (value instanceof Map<?, ?> map) {
            return freezeStringMap(map, path);
        }
        if (value instanceof List<?> list) {
            var result = new ArrayList<>(list.size());
            list.forEach(element -> result.add(freeze(element, path)));
            return Collections.unmodifiableList(result);
        }
        return value;
    }
}
