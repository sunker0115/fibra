package com.sstlfsj.fibra.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigDocumentStoreTest {
    @Test
    void commitsOrRollsBackValidatedContentWithExpectedRevision(@TempDir Path work)
        throws Exception {
        var root = work.resolve("fibra.yaml");
        var original = "- id: first\n  plugin: {id: sample-plugin, facet: main, definition: sample}\n";
        var replacement = "- id: second\n  plugin: {id: sample-plugin, facet: main, definition: sample}\n";
        Files.writeString(root, original);
        var store = new ConfigDocumentStore(root, ConfigLimits.defaults());
        var initial = store.read();

        assertEquals(original, initial.text());
        assertThrows(ConfigException.class,
            () -> store.prepareReplace("stale", replacement.getBytes(StandardCharsets.UTF_8)));

        try (var transaction = store.prepareReplace(initial.revision(),
            replacement.getBytes(StandardCharsets.UTF_8))) {
            assertNotEquals(initial.revision(), transaction.candidate().revision());
            assertEquals(original, Files.readString(root));
        }
        assertEquals(original, Files.readString(root));

        try (var transaction = store.prepareReplace(initial.revision(),
            replacement.getBytes(StandardCharsets.UTF_8))) {
            transaction.commit();
            transaction.commit();
        }
        assertEquals(replacement, Files.readString(root));
        assertFalse(Files.list(work)
            .anyMatch(path -> path.getFileName().toString().contains("fibra-txn")));
    }

    @Test
    void rejectsInvalidCandidateBeforeTouchingTheSource(@TempDir Path work) throws Exception {
        var root = work.resolve("fibra.yaml");
        var original = "- id: first\n  plugin: {id: sample-plugin, facet: main, definition: sample}\n";
        Files.writeString(root, original);
        var store = new ConfigDocumentStore(root, ConfigLimits.defaults());
        var initial = store.read();

        var failure = assertThrows(ConfigException.class,
            () -> store.prepareReplace(initial.revision(), "not: [valid".getBytes(
                StandardCharsets.UTF_8)));

        assertEquals(ConfigStage.PARSE, failure.diagnostic().stage());
        assertEquals(original, Files.readString(root));
    }
}
