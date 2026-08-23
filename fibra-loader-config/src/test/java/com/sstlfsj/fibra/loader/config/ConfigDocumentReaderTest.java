package com.sstlfsj.fibra.loader.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigDocumentReaderTest {
    @Test
    void yamlAndJsonProduceTheSameLiteralValueGraph(@TempDir Path work) throws Exception {
        var yaml = work.resolve("plugins.yaml");
        var json = work.resolve("plugins.json");
        Files.writeString(yaml, """
            - id: first
              name: fixture
              disabled: false
              inject:
                settings: null
              config:
                retries: 2
                enabled: true
            """);
        Files.writeString(json, """
            [{"id":"first","name":"fixture","disabled":false,
              "inject":{"settings":null},
              "config":{"retries":2,"enabled":true}}]
            """);

        var reader = new ConfigDocumentReader(ConfigLimits.defaults());

        assertEquals(reader.read(yaml), reader.read(json));
        assertEquals(List.of(Map.of(
            "id", "first",
            "name", "fixture",
            "disabled", false,
            "inject", java.util.Collections.singletonMap("settings", null),
            "config", Map.of("retries", 2, "enabled", true)
        )), reader.read(yaml));
    }

    @Test
    void rejectsDuplicateKeysAsParseFailure(@TempDir Path work) throws Exception {
        var path = work.resolve("plugins.yaml");
        Files.writeString(path, """
            - id: first
              id: second
              name: fixture
            """);

        var error = assertThrows(FibraConfigException.class,
            () -> new ConfigDocumentReader(ConfigLimits.defaults()).read(path));

        assertEquals(FibraConfigErrorStage.PARSE, error.stage());
        assertEquals(path.toRealPath(), error.path());
    }

    @Test
    void rejectsUnsupportedExtensionAndNonArrayRoot(@TempDir Path work) throws Exception {
        var text = work.resolve("plugins.txt");
        Files.writeString(text, "[]");
        var object = work.resolve("plugins.json");
        Files.writeString(object, "{}");
        var reader = new ConfigDocumentReader(ConfigLimits.defaults());

        assertEquals(FibraConfigErrorStage.READ,
            assertThrows(FibraConfigException.class, () -> reader.read(text)).stage());
        assertEquals(FibraConfigErrorStage.VALIDATE,
            assertThrows(FibraConfigException.class, () -> reader.read(object)).stage());
    }

    @Test
    void rejectsFilesLargerThanTheConfiguredLimitBeforeParsing(@TempDir Path work)
        throws Exception {
        var path = work.resolve("plugins.json");
        Files.writeString(path, "[{}]");

        var error = assertThrows(FibraConfigException.class,
            () -> new ConfigDocumentReader(new ConfigLimits(3, 100, 1_048_576, 10_000))
                .read(path));

        assertEquals(FibraConfigErrorStage.READ, error.stage());
    }
}
