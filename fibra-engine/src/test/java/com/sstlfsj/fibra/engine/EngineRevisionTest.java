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
    void artifactAndSourceRevisionsAreStableAndContentAddressed(@TempDir Path work)
        throws Exception {
        var first = work.resolve("first.yaml");
        var second = work.resolve("second.yaml");
        Files.writeString(first, "one");
        Files.writeString(second, "two");
        var artifacts = List.of(new RevisionArtifact("b", "1.0.0", "b".repeat(64)),
            new RevisionArtifact("a", "1.0.0", "a".repeat(64)));

        var artifactRevision = EngineRevision.artifacts(artifacts);
        assertEquals(artifactRevision, EngineRevision.artifacts(artifacts.reversed()));

        var revision = EngineRevision.sourceFiles(List.of(first, second));
        assertEquals(revision,
            EngineRevision.sourceFiles(List.of(second, first)));
        Files.writeString(second, "changed");
        assertNotEquals(revision,
            EngineRevision.sourceFiles(List.of(first, second)));

        assertEquals(EngineRevision.combine(artifactRevision, revision),
            EngineRevision.combine(artifactRevision, revision));
    }
}
