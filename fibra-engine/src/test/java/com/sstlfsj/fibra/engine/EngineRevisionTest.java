package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
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

    @Test
    void sourceRevisionReadsAllChunksWithLongLengthFraming(@TempDir Path work)
        throws Exception {
        var source = work.resolve("large.zip");
        var content = new byte[128 * 1024 + 17];
        for (var index = 0; index < content.length; index++) {
            content[index] = (byte) (index * 31);
        }
        Files.write(source, content);

        assertEquals(expectedSourceRevision(source),
            EngineRevision.sourceFiles(List.of(source)));

        var revision = EngineRevision.sourceFiles(List.of(source));
        content[content.length / 2] ^= 1;
        Files.write(source, content);
        assertNotEquals(revision, EngineRevision.sourceFiles(List.of(source)));
    }

    private static String expectedSourceRevision(Path source) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        text(digest, "fibra-source-files-v2");
        text(digest, source.toAbsolutePath().normalize().toString());
        var content = Files.readAllBytes(source);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(content.length).array());
        digest.update(content);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void text(MessageDigest digest, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
