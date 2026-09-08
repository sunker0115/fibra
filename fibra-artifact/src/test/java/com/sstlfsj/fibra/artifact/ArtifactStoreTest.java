package com.sstlfsj.fibra.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactStoreTest {
    private static final ArtifactId SAMPLE = new ArtifactId("sample");
    private static final RuntimeId JAVA = new RuntimeId("java");

    @Test
    void stagesCommitsAndRetiresOpaqueArtifacts(@TempDir Path work) throws Exception {
        var source = work.resolve("sample.jar");
        Files.writeString(source, "jar-content");
        var store = new ArtifactStore(work.resolve("store"));

        try (var transaction = store.prepareInstall(SAMPLE, JAVA, "1.0.0", source)) {
            assertEquals(ArtifactState.STAGED, transaction.candidate().state());
            assertTrue(Files.exists(transaction.candidate().location()));
            assertFalse(store.find(SAMPLE).isPresent());
        }
        assertFalse(store.find(SAMPLE).isPresent());
        try (var paths = Files.list(work.resolve("store").resolve("objects"))) {
            assertEquals(0, paths.count());
        }

        ArtifactRecord installed;
        try (var transaction = store.prepareInstall(SAMPLE, JAVA, "1.0.0", source)) {
            installed = transaction.commit();
            assertEquals(installed, transaction.commit());
        }
        assertEquals(ArtifactState.INSTALLED, installed.state());
        assertEquals(JAVA, installed.runtimeId());
        assertEquals(installed, store.find(SAMPLE).orElseThrow());
        assertEquals("jar-content", Files.readString(installed.location()));

        var retired = store.retire(installed);

        assertEquals(ArtifactState.RETIRED, retired.state());
        assertFalse(store.find(SAMPLE).isPresent());
        assertEquals(1, store.history(SAMPLE).size());
    }

    @Test
    void recoversAbandonedStagingAndRejectsSymbolicLinks(@TempDir Path work)
        throws Exception {
        var source = Files.createDirectory(work.resolve("node-package"));
        Files.writeString(source.resolve("index.mjs"), "export default {};");
        var storeRoot = work.resolve("store");
        var store = new ArtifactStore(storeRoot);
        store.prepareInstall(SAMPLE, new RuntimeId("node"), "1.0.0", source);
        store.close();

        var recovered = new ArtifactStore(storeRoot);

        try (var paths = Files.list(storeRoot.resolve("transactions"))) {
            assertEquals(0, paths.count());
        }

        var external = work.resolve("external.txt");
        Files.writeString(external, "external");
        Files.createSymbolicLink(source.resolve("escape"), external);

        var failure = assertThrows(ArtifactException.class,
            () -> recovered.prepareInstall(SAMPLE, new RuntimeId("node"), "1.0.1", source));
        assertEquals(ArtifactPhase.VALIDATE, failure.phase());
        assertTrue(Files.exists(external));
        recovered.close();
    }

    @Test
    void compensatesACommittedInstallUntilTheOuterChangeSetFinishes(@TempDir Path work)
        throws Exception {
        var first = work.resolve("first.jar");
        var second = work.resolve("second.jar");
        Files.writeString(first, "first");
        Files.writeString(second, "second");
        var store = new ArtifactStore(work.resolve("store"));
        ArtifactRecord original;
        try (var transaction = store.prepareInstall(SAMPLE, JAVA, "1.0.0", first)) {
            original = transaction.commit();
        }

        var replacement = store.prepareInstall(SAMPLE, JAVA, "2.0.0", second);
        replacement.commit();
        replacement.rollback();

        assertEquals(original, store.find(SAMPLE).orElseThrow());
        assertEquals("first", Files.readString(original.location()));
        store.close();
    }
}
