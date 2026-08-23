package com.sstlfsj.fibra.loader.config;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ConfigDocumentWriter {
    private final JsonMapper json = JsonMapper.builder().build();
    private final YAMLMapper yaml = YAMLMapper.builder().build();

    byte[] write(Path path, List<Map<String, Object>> entries) {
        try {
            var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".json")) {
                return json.writerWithDefaultPrettyPrinter().writeValueAsBytes(entries);
            }
            if (name.endsWith(".yaml") || name.endsWith(".yml")) {
                return yaml.writeValueAsBytes(entries);
            }
            throw new IllegalArgumentException("unsupported config extension " + path);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("cannot serialize config file " + path, exception);
        }
    }
}
