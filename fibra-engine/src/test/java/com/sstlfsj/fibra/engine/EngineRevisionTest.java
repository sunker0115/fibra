package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EngineRevisionTest {
    @Test
    void isStableForOrderingAndChangesWithAnyInputByte(@TempDir Path work)
        throws Exception {
        var first = work.resolve("first.yaml");
        var second = work.resolve("second.yaml");
        Files.writeString(first, "one");
        Files.writeString(second, "two");
        var artifacts = List.of(new RevisionArtifact("b", "1.0.0", "b".repeat(64)),
            new RevisionArtifact("a", "1.0.0", "a".repeat(64)));

        var revision = EngineRevision.compute(artifacts, List.of(first, second));
        assertEquals(revision,
            EngineRevision.compute(artifacts.reversed(), List.of(second, first)));
        Files.writeString(second, "changed");
        assertNotEquals(revision,
            EngineRevision.compute(artifacts, List.of(first, second)));
    }
}
