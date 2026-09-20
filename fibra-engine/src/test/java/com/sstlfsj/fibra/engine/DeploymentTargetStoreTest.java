package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentTargetStoreTest {
    @TempDir Path root;

    @Test
    void targetCodecPreservesRevisionContextAndRejectsOldOrNoncanonicalFormats() {
        var target = target(3, "中文");
        assertEquals(target, DeploymentTargetCodec.decode(DeploymentTargetCodec.encode(target)));
        assertThrows(IllegalArgumentException.class, () -> DeploymentTargetCodec.decode(
            "{\"manifest\":{},\"revision\":\"old\"}".getBytes(StandardCharsets.UTF_8)));
        var json = new String(DeploymentTargetCodec.encode(target), StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> DeploymentTargetCodec.decode(
            (" " + json).getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> DeploymentTargetCodec.decode(
            json.replace("中文", "changed").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void fileStoreRestoresOnlyCompleteTargetAndUsesRevisionCas() {
        var a = target(1, "a");
        try (var store = new FileDeploymentTargetStore(root)) {
            assertTrue(store.load().isEmpty());
            var token = store.save(0, a);
            assertEquals(a.targetDigest(), token.targetDigest());
            assertThrows(IllegalStateException.class, () -> store.save(0, target(2, "b")));
            assertEquals(a, store.load().orElseThrow().target());
        }
        try (var store = new FileDeploymentTargetStore(root)) {
            assertEquals(a, store.load().orElseThrow().target());
            store.save(1, target(2, "b"));
            var secondA = target(3, "a");
            store.save(2, secondA);
            assertEquals(a.targetDigest(), secondA.targetDigest());
            assertEquals(3, store.load().orElseThrow().token().targetRevision());
        }
    }

    @Test
    void replacementWithUnconfirmedDurabilityIsNotReportedAsExplicitFailure() {
        var io = new FileDeploymentTargetStore.StorageIo() {
            boolean replaced;
            public void force(Path path) throws java.io.IOException {
                if (replaced) throw new java.io.IOException("directory fsync failed");
            }
            public void replace(Path staged, Path target) throws java.io.IOException {
                java.nio.file.Files.move(staged, target);
                replaced = true;
            }
        };
        try (var store = new FileDeploymentTargetStore(root, io)) {
            assertThrows(DeploymentTargetStore.SaveUnconfirmedException.class,
                () -> store.save(0, target(1, "a")));
        }
        try (var recovered = new FileDeploymentTargetStore(root)) {
            assertEquals(target(1, "a"), recovered.load().orElseThrow().target());
        }
    }

    private static DeploymentTarget target(long revision, String value) {
        return DeploymentTarget.of(revision, List.of(), new DesiredInputGraph(List.of()),
            ConfigContextSnapshot.of(new com.sstlfsj.fibra.value.LiteralValue.ObjectValue(
                Map.of("value", com.sstlfsj.fibra.value.LiteralValue.of(value)))));
    }
}
