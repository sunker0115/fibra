package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactException;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactPhase;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EngineArtifactRecoveryTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ArtifactId ARTIFACT = new ArtifactId("sample");
    private static final RuntimeId RUNTIME = new RuntimeId("fake");

    @Test
    void missingSavedArtifactRevisionFailsBootstrapWithoutReplacingTheTarget(@TempDir Path work)
        throws Exception {
        var artifactRoot = work.resolve("artifacts");
        var stateRoot = work.resolve("state");
        var saved = saveArtifact(work, artifactRoot);
        var missingRevision = "0".repeat(64);
        assertNotEquals(saved.revision(), missingRevision);
        var target = new DeploymentManifest(Map.of(ARTIFACT, missingRevision), emptyGraph());
        var targetBytes = saveTarget(stateRoot, target);

        try (var engine = recoveryEngine(artifactRoot, stateRoot)) {
            var failure = assertThrows(IllegalStateException.class,
                () -> engine.start().block(TIMEOUT));

            assertEquals("saved artifact is missing: sample@" + missingRevision, failure.getMessage());
            assertFailedWithMutationGateClosed(engine);
            assertArrayEquals(targetBytes, Files.readAllBytes(stateRoot.resolve("target.json")));
        }
    }

    @Test
    void corruptSavedArtifactContentFailsBootstrapWithoutReplacingTheTargetOrEvidence(
        @TempDir Path work) throws Exception {
        var artifactRoot = work.resolve("artifacts");
        var stateRoot = work.resolve("state");
        var saved = saveArtifact(work, artifactRoot);
        var target = new DeploymentManifest(Map.of(ARTIFACT, saved.revision()), emptyGraph());
        var targetBytes = saveTarget(stateRoot, target);
        Files.writeString(saved.location(), "corrupted artifact content");
        var corruptContent = Files.readAllBytes(saved.location());

        try (var engine = recoveryEngine(artifactRoot, stateRoot)) {
            var failure = assertThrows(ArtifactException.class,
                () -> engine.start().block(TIMEOUT));

            assertEquals(ArtifactPhase.RECOVER, failure.phase());
            assertFailedWithMutationGateClosed(engine);
            assertArrayEquals(targetBytes, Files.readAllBytes(stateRoot.resolve("target.json")));
            assertArrayEquals(corruptContent, Files.readAllBytes(saved.location()));
        }
    }

    private static FibraEngine recoveryEngine(Path artifactRoot, Path stateRoot) {
        return FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(new ArtifactStore(artifactRoot))
            .stateStore(new FileEngineStateStore(stateRoot)).build();
    }

    private static ArtifactRecord saveArtifact(Path work, Path artifactRoot) throws Exception {
        var source = work.resolve("sample.bin");
        Files.writeString(source, "original artifact content");
        try (var store = new ArtifactStore(artifactRoot);
             var transaction = store.prepareInstall(ARTIFACT, RUNTIME, "1.0.0", source)) {
            return transaction.save();
        }
    }

    private static byte[] saveTarget(Path stateRoot, DeploymentManifest target) throws Exception {
        try (var store = new FileEngineStateStore(stateRoot)) {
            store.save(target);
        }
        return Files.readAllBytes(stateRoot.resolve("target.json"));
    }

    private static DesiredInputGraph emptyGraph() {
        return new DesiredInputGraph(List.of());
    }

    private static void assertFailedWithMutationGateClosed(FibraEngine engine) {
        assertEquals(EngineState.FAILED, engine.published().current().engine().state());
        assertFalse(engine.published().current().engineDiagnostics().mutationGateOpen());
        assertThrows(MutationGateClosedException.class,
            () -> engine.submit(new RefreshDesired(null)).block(TIMEOUT));
    }
}
