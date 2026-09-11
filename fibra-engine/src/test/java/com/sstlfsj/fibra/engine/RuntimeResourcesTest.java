package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactState;
import com.sstlfsj.fibra.artifact.RuntimeId;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeResourcesTest {
    @Test
    void failedSecondRuntimeClosesThePreviouslyPreparedHandle() {
        var closed = new ArrayList<String>();
        var first = new Probe("a", closed);
        var second = new Probe("b", closed);
        second.prepareFails = true;
        var resources = new RuntimeResources();
        assertThrows(IllegalStateException.class, () -> prepare(resources, first, second).block());
        resources.closeAsync().block();
        assertEquals(List.of("b", "a"), closed);
    }

    @Test
    void identityMismatchClosesEvenTheRejectedHandleInReverseOrder() {
        var closed = new ArrayList<String>();
        var first = new Probe("a", closed);
        var second = new Probe("b", closed);
        second.wrongIdentity = true;
        var resources = new RuntimeResources();
        assertThrows(IllegalArgumentException.class, () -> prepare(resources, first, second).block());
        resources.closeAsync().block();
        assertEquals(List.of("b", "a"), closed);
    }

    @Test
    void closingAttemptsEverySiblingAndReplaysTheSameFailure() {
        var closed = new ArrayList<String>();
        var first = new Probe("a", closed);
        var second = new Probe("b", closed);
        second.closeFails = true;
        var resources = new RuntimeResources();
        prepare(resources, first, second).block();

        var firstFailure = assertThrows(IllegalStateException.class, () -> resources.closeAsync().block());
        var repeated = assertThrows(IllegalStateException.class, () -> resources.closeAsync().block());
        assertSame(firstFailure, repeated);
        assertEquals(List.of("b", "a"), closed);
    }

    @Test
    void partialPreparationRetainsItsCloseFailureForTheOwner() {
        var closed = new ArrayList<String>();
        var first = new Probe("a", closed);
        var second = new Probe("b", closed);
        first.closeFails = true;
        second.prepareFails = true;

        var resources = new RuntimeResources();
        var failure = assertThrows(IllegalStateException.class,
            () -> prepare(resources, first, second).block());
        assertEquals("prepare:b", failure.getMessage());
        var closing = assertThrows(IllegalStateException.class, () -> resources.closeAsync().block());
        assertSame(closing, assertThrows(IllegalStateException.class, () -> resources.closeAsync().block()));
        assertEquals(List.of("b", "a"), closed);
    }

    private static Mono<Void> prepare(RuntimeResources resources, Probe first, Probe second) {
        return resources.prepare(Map.of(first.artifact.id(), first.artifact,
            second.artifact.id(), second.artifact), Map.of(first.id(), first, second.id(), second),
            PluginCatalog.empty());
    }

    private static final class Probe implements PluginRuntimeAdapter {
        private final RuntimeId id;
        private final ArtifactRecord artifact;
        private final List<String> closed;
        private boolean prepareFails;
        private boolean wrongIdentity;
        private boolean closeFails;

        private Probe(String id, List<String> closed) {
            this.id = new RuntimeId(id);
            this.closed = closed;
            artifact = ArtifactRecord.builder().id(new ArtifactId(id)).runtimeId(this.id)
                .version("1").checksum("digest").revision("revision").location(Path.of(id))
                .state(ArtifactState.INSTALLED).updatedAt(Instant.EPOCH).build();
        }

        @Override public RuntimeId id() { return id; }
        @Override public Mono<RuntimeArtifactInspection> inspect(ArtifactRecord artifact) {
            return Mono.just(new RuntimeArtifactInspection(id, artifact.id(), Map.of()));
        }
        @Override public RuntimeGeneration create(RuntimeGenerationRequest request) {
            var snapshot = new RuntimeGenerationSnapshot(wrongIdentity ? new RuntimeId("wrong") : id,
                "1", Map.of(artifact.id(), artifact), Set.of());
            return new RuntimeGeneration() {
                private final Mono<Void> preparation = Mono.<Void>defer(() -> prepareFails
                    ? Mono.error(new IllegalStateException("prepare:" + id.value())) : Mono.empty()).cache();
                @Override public Mono<Void> prepareAsync() { return preparation; }
                @Override public RuntimeGenerationSnapshot snapshot() { return snapshot; }
                @Override public PluginCatalog catalog() { return PluginCatalog.empty(); }
                @Override public Mono<Void> closeAsync() {
                    return Mono.defer(() -> {
                        closed.add(id.value());
                        return closeFails ? Mono.error(new IllegalStateException("close:" + id.value()))
                            : Mono.empty();
                    });
                }
            };
        }
    }
}
