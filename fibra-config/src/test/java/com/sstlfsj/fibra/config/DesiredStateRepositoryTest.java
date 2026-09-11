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
        var initial = repository.load();
        var graph = new DesiredInputGraph(List.of(entry("sample")));

        try (var ignored = repository.prepareReplace(initial.snapshot().revision(), graph)) {
            assertEquals(0, repository.load()
                .graph().plugins().size());
        }

        DesiredCompilation committed;
        try (var transaction = repository.prepareReplace(
            initial.snapshot().revision(), graph)) {
            committed = transaction.commit();
        }

        assertTrue(repository.writable());
        assertEquals(List.of("sample"), committed.graph().plugins().keySet().stream().toList());
        assertThrows(ConfigException.class,
            () -> repository.prepareReplace(initial.snapshot().revision(), graph));
    }

    @Test
    void memoryRepositoryCompensatesACommittedParticipantBeforeGlobalCommit() {
        var repository = InMemoryDesiredStateRepository.empty();
        var initial = repository.load();
        var graph = new DesiredInputGraph(List.of(entry("sample")));
        var transaction = repository.prepareReplace(initial.snapshot().revision(), graph);

        transaction.commit();
        transaction.rollback();
        transaction.rollback();

        var restored = repository.load();
        assertEquals(initial.snapshot().revision(), restored.snapshot().revision());
        assertEquals(List.of(), restored.graph().roots());
    }

    @Test
    void compensationRejectsALaterWriteAndCloseKeepsCommittedState() {
        var repository = InMemoryDesiredStateRepository.empty();
        var initial = repository.load();
        var first = repository.prepareReplace(initial.snapshot().revision(),
            new DesiredInputGraph(List.of(entry("first"))));
        var firstResult = first.commit();
        first.close();
        assertEquals(firstResult, repository.load());

        var second = repository.prepareReplace(firstResult.snapshot().revision(),
            new DesiredInputGraph(List.of(entry("second"))));
        var secondResult = second.commit();
        assertThrows(ConfigException.class, first::rollback);
        assertEquals(secondResult, repository.load());
        second.close();
        assertEquals(secondResult, repository.load());
    }

    @Test
    void fileRepositoryIsAnExplicitReadOnlySource(@TempDir Path work) throws Exception {
        var root = work.resolve("fibra.yaml");
        Files.writeString(root, "- id: sample\n  plugin: sample\n");
        var repository = new FileDesiredStateRepository(root, ConfigLimits.defaults());
        assertFalse(repository.writable());
        assertEquals(1, repository.load().graph().plugins().size());
        assertThrows(UnsupportedOperationException.class,
            () -> repository.prepareReplace("revision", new DesiredInputGraph(List.of())));
    }

    private static DesiredInputEntry entry(String id) {
        return DesiredInputEntry.builder(id, "sample").build();
    }
}
