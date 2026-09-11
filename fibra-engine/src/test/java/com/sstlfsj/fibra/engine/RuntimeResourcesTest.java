package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactState;
import com.sstlfsj.fibra.artifact.RuntimeId;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeResourcesTest {
    @Test
    void configurationOnlyTargetDoesNotCreateAnotherRuntimeUpdate() {
        var probe = new Probe("java");
        var record = probe.artifact("a", "one");
        var resources = resources(probe);
        install(resources, Map.of(record.id(), record));

        var update = resources.createUpdate(Map.of(record.id(), record));
        update.prepareAsync().block();

        assertEquals(1, probe.createdOwners);
        assertEquals(1, probe.createdUpdates);
        assertEquals(Set.of(), update.affectedArtifacts());
        update.closeAsync().block();
    }

    @Test
    void changingOneRuntimeCreatesOnlyItsLocalUpdate() {
        var java = new Probe("java");
        var node = new Probe("node");
        var first = java.artifact("a", "one");
        var second = node.artifact("b", "one");
        var resources = new RuntimeResources(Map.of(java.id(), java, node.id(), node), PluginCatalog.empty());
        install(resources, Map.of(first.id(), first, second.id(), second));
        var changed = java.artifact("a", "two");

        var update = resources.createUpdate(Map.of(changed.id(), changed, second.id(), second));
        update.prepareAsync().block();

        assertEquals(2, java.createdUpdates);
        assertEquals(1, node.createdUpdates);
        assertEquals(Set.of(changed.id()), update.affectedArtifacts());
        update.closeAsync().block();
    }

    @Test
    void failedPreparationAndFailedCleanupKeepTheUpdateGate() {
        var probe = new Probe("java");
        probe.prepareFailure = new IllegalStateException("prepare");
        var record = probe.artifact("a", "one");
        var resources = resources(probe);
        var failed = resources.createUpdate(Map.of(record.id(), record));
        assertThrows(IllegalStateException.class, () -> failed.prepareAsync().block());
        assertThrows(IllegalStateException.class, () -> resources.createUpdate(Map.of()));
        failed.closeAsync().block();

        probe.prepareFailure = null;
        install(resources, Map.of(record.id(), record));
        var cleanup = resources.createUpdate(Map.of());
        cleanup.prepareAsync().block();
        probe.closeFailure = new IllegalStateException("close");
        var failure = assertThrows(IllegalStateException.class, () -> cleanup.closeAsync().block());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> cleanup.closeAsync().block()));
        assertThrows(IllegalStateException.class, () -> resources.createUpdate(Map.of()));
    }

    @Test
    void cleanupUsesTheSameHandleBeforeAndAfterAdoptionAndWaitsForPreparation() {
        var probe = new Probe("java");
        var record = probe.artifact("a", "one");
        var resources = resources(probe);
        var before = resources.createUpdate(Map.of(record.id(), record));
        before.prepareAsync().block();
        before.closeAsync().block();
        assertEquals(1, probe.closedUpdates);

        var preparing = resources.createUpdate(Map.of(record.id(), record));
        probe.prepareGate = Sinks.one();
        var preparingFuture = preparing.prepareAsync().toFuture();
        var closing = preparing.closeAsync().toFuture();
        assertEquals(1, probe.closedUpdates);
        probe.prepareGate.tryEmitEmpty();
        preparingFuture.join();
        closing.join();
        assertEquals(2, probe.closedUpdates);

        var adopted = resources.createUpdate(Map.of(record.id(), record));
        adopted.prepareAsync().block();
        adopted.adopt();
        adopted.closeAsync().block();
        assertEquals(3, probe.closedUpdates);
    }

    @Test
    void oneFailedUpdateDoesNotSkipAnIndependentRuntimeOwner() {
        var java = new Probe("java");
        var node = new Probe("node");
        var first = java.artifact("a", "one");
        var second = node.artifact("b", "one");
        var resources = new RuntimeResources(Map.of(java.id(), java, node.id(), node), PluginCatalog.empty());
        install(resources, Map.of(first.id(), first, second.id(), second));
        var changed = java.artifact("a", "two");
        var update = resources.createUpdate(Map.of(changed.id(), changed, second.id(), second));
        update.prepareAsync().block();
        java.closeFailure = new IllegalStateException("java resource remains open");

        assertThrows(IllegalStateException.class, () -> resources.closeAsync().block());
        assertEquals(1, node.closedOwners, "independent owner must still be reclaimed");
    }

    private static RuntimeResources resources(Probe probe) {
        return new RuntimeResources(Map.of(probe.id(), probe), PluginCatalog.empty());
    }

    private static void install(RuntimeResources resources, Map<ArtifactId, ArtifactRecord> target) {
        var update = resources.createUpdate(target);
        update.prepareAsync().block();
        update.adopt();
        update.closeAsync().block();
    }

    private static final class Probe implements PluginRuntimeAdapter {
        private final RuntimeId id;
        private int createdOwners;
        private int createdUpdates;
        private int closedUpdates;
        private int closedOwners;
        private Throwable prepareFailure;
        private Throwable closeFailure;
        private Sinks.One<Void> prepareGate;

        private Probe(String id) { this.id = new RuntimeId(id); }
        ArtifactRecord artifact(String id, String revision) {
            return ArtifactRecord.builder().id(new ArtifactId(id)).runtimeId(this.id).version("1")
                .checksum(revision).revision(revision).location(Path.of(id + '-' + revision))
                .state(ArtifactState.INSTALLED).updatedAt(Instant.EPOCH).build();
        }
        @Override public RuntimeId id() { return id; }
        @Override public Mono<RuntimeArtifactInspection> inspect(ArtifactRecord artifact) {
            return Mono.just(new RuntimeArtifactInspection(id, artifact.id(), Map.of()));
        }
        @Override public RuntimeResourceOwner create() {
            createdOwners++;
            return new RuntimeResourceOwner() {
                @Override public RuntimeResourceUpdate createUpdate(List<ArtifactRecord> target) {
                    createdUpdates++;
                    return new RuntimeResourceUpdate() {
                        @Override public Mono<Void> prepareAsync() {
                            if (prepareFailure != null) return Mono.error(prepareFailure);
                            return prepareGate == null ? Mono.empty() : prepareGate.asMono();
                        }
                        @Override public Set<ArtifactId> affectedArtifacts() {
                            return target.stream().map(ArtifactRecord::id).collect(java.util.stream.Collectors.toSet());
                        }
                        @Override public RuntimeCatalog catalog() { return RuntimeCatalog.empty(); }
                        @Override public RuntimeResourceSnapshot snapshot() {
                            return new RuntimeResourceSnapshot(id, List.of());
                        }
                        @Override public void adopt() { }
                        @Override public Mono<Void> closeAsync() {
                            closedUpdates++;
                            return closeFailure == null ? Mono.empty() : Mono.error(closeFailure);
                        }
                    };
                }
                @Override public RuntimeCatalog catalog() { return RuntimeCatalog.empty(); }
                @Override public RuntimeResourceSnapshot snapshot() { return new RuntimeResourceSnapshot(id, List.of()); }
                @Override public Mono<Void> closeAsync() {
                    closedOwners++;
                    return Mono.empty();
                }
            };
        }
    }
}
