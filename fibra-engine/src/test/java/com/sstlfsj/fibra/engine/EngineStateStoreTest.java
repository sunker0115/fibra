package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.config.DesiredInputGraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EngineStateStoreTest {
    @Test
    void memoryStoreStartsEmptyAndKeepsTheCompleteTarget() {
        try (var store = EngineStateStore.inMemory()) {
            assertTrue(store.load().isEmpty());
            var target = new DeploymentManifest(java.util.Map.of(), new DesiredInputGraph(List.of()));
            store.save(target);
            assertEquals(target, store.load().orElseThrow());
            assertThrows(NullPointerException.class, () -> store.save(null));
            assertEquals(target, store.load().orElseThrow());
        }
    }
}
