package com.sstlfsj.fibra.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesiredStateRepositoryTest {
    @Test
    void memoryRepositoryUsesOptimisticTransactionalReplacement() {
        var repository = InMemoryDesiredStateRepository.empty();
        var initial = repository.load(name -> java.util.Optional.empty());
        var graph = new DesiredGraph(List.of(entry("sample")));

        try (var ignored = repository.prepareReplace(initial.snapshot().revision(), graph)) {
            assertEquals(0, repository.load(name -> java.util.Optional.empty())
                .graph().entries().size());
        }

        DesiredCompilation committed;
        try (var transaction = repository.prepareReplace(
            initial.snapshot().revision(), graph)) {
            committed = transaction.commit();
        }

        assertTrue(repository.writable());
        assertEquals(List.of("sample"), committed.graph().entries().stream()
            .map(DesiredEntry::instanceId).toList());
        assertThrows(ConfigException.class,
            () -> repository.prepareReplace(initial.snapshot().revision(), graph));
    }

    @Test
    void fileRepositoryIsAnExplicitReadOnlySource(@TempDir Path work) throws Exception {
        var root = work.resolve("fibra.yaml");
        Files.writeString(root, "- id: sample\n  plugin: sample\n");
        var repository = new FileDesiredStateRepository(root, ConfigLimits.defaults());
        var resolver = (PluginDefinitionResolver) name -> java.util.Optional.of(
            PluginContract.builder("sample").configType(Void.class)
                .binder(value -> null).build());

        assertFalse(repository.writable());
        assertEquals(1, repository.load(resolver).graph().entries().size());
        assertThrows(UnsupportedOperationException.class,
            () -> repository.prepareReplace("revision", new DesiredGraph(List.of())));
    }

    private static DesiredEntry entry(String id) {
        return DesiredEntry.builder(id, "sample").source(Path.of("memory")).build();
    }
}
