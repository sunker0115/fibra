package com.sstlfsj.fibra.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class PluginConfigurationResourceTest {
    private static final String FILE = "fibra-config.json";
    private static final byte[] BYTES = "{\"format\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void verifiedRecordReadsOriginalBytesAndReturnsDefensiveCopies(@TempDir Path work)
        throws Exception {
        var record = install(work, BYTES);
        try (var resource = record.configurationResource()) {
            var first = resource.read();
            assertArrayEquals(BYTES, first);
            first[0] = 0;
            Files.writeString(record.location().resolve(FILE), "changed after successful read");
            assertArrayEquals(BYTES, resource.read());
            resource.close();
            assertArrayEquals(BYTES, resource.read());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "truncate", "same-length", "replacement", "directory", "symlink"})
    void findRecordNeverReturnsChangedBytesAsTheOriginalRevision(String change,
                                                                 @TempDir Path work)
        throws Exception {
        var record = install(work, BYTES);
        var file = record.location().resolve(FILE);
        switch (change) {
            case "missing" -> Files.delete(file);
            case "truncate" -> Files.write(file, new byte[0]);
            case "same-length" -> Files.write(file, new byte[BYTES.length]);
            case "replacement" -> {
                var replacement = Files.writeString(work.resolve("replacement"), "new config");
                Files.move(replacement, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            case "directory" -> {
                Files.delete(file);
                Files.createDirectory(file);
            }
            case "symlink" -> {
                Files.delete(file);
                Files.createSymbolicLink(file, Files.write(work.resolve("secret-target"), BYTES));
            }
            default -> throw new AssertionError(change);
        }
        try (var resource = record.configurationResource()) {
            var failure = assertThrows(ArtifactException.class, resource::read);
            assertSafe(failure);
            resource.close();
            assertSafe(assertThrows(ArtifactException.class, resource::read));
        }
    }

    @Test
    void originallyAbsentResourceStaysMissingWithoutConsultingTheFileSystem(@TempDir Path work)
        throws Exception {
        var record = install(work, null);
        Files.write(record.location().resolve(FILE), BYTES);
        try (var resource = record.configurationResource()) {
            var failure = assertThrows(NoSuchElementException.class, resource::read);
            assertEquals("package configuration resource is absent", failure.getMessage());
            assertNull(failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
        }
    }

    @Test
    void originallyNonregularResourceCannotBecomeValidByReplacement(@TempDir Path work)
        throws Exception {
        var source = source(work);
        Files.createDirectory(source.resolve(FILE));
        var record = installSource(work, source);
        Files.delete(record.location().resolve(FILE));
        Files.write(record.location().resolve(FILE), BYTES);
        try (var resource = record.configurationResource()) {
            assertSafe(assertThrows(ArtifactException.class, resource::read));
        }
    }

    @Test
    void sameBytesWithChangedMtimeAreStillTheSameResource(@TempDir Path work) throws Exception {
        var record = install(work, BYTES);
        Files.setLastModifiedTime(record.location().resolve(FILE), FileTime.fromMillis(123_000));
        try (var resource = record.configurationResource()) {
            assertArrayEquals(BYTES, resource.read());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1_048_576, 1_048_577})
    void enforcesOneMiBReadBoundary(int length, @TempDir Path work) throws Exception {
        var bytes = new byte[length];
        var record = install(work, bytes);
        try (var resource = record.configurationResource()) {
            if (length == 1_048_576) {
                assertArrayEquals(bytes, resource.read());
            } else {
                assertSafe(assertThrows(ArtifactException.class, resource::read));
            }
        }
    }

    @Test
    void metadataBuilderHasNoAuthorityEvenWhenAllMetadataMatches(@TempDir Path work)
        throws Exception {
        var record = install(work, BYTES);
        var manual = PluginPackageRecord.builder().pluginId(record.pluginId())
            .version(record.version()).packageRevision(record.packageRevision())
            .location(record.location()).managedPackage(record.managedPackage())
            .state(record.state()).updatedAt(record.updatedAt()).build();
        assertEquals(record, manual);
        assertEquals(record.hashCode(), manual.hashCode());
        assertThrows(IllegalStateException.class, manual::configurationResource);
        Files.delete(record.location().resolve(FILE));
        assertThrows(IllegalStateException.class, manual::configurationResource);
        assertThrows(IllegalStateException.class, () -> manual.toBuilder()
            .location(work.resolve("does-not-exist")).build().configurationResource());
        // Creation is lazy even after a verified file has disappeared.
        try (var resource = record.configurationResource()) {
            assertSafe(assertThrows(ArtifactException.class, resource::read));
        }
    }

    @Test
    void toBuilderChecksFinalNormalizedContentIdentityAndPreservesMetadataChanges(
        @TempDir Path work) throws Exception {
        var record = install(work, BYTES);
        var restored = record.toBuilder().location(work.resolve("unused"))
            .location(record.location().resolve("unvisited/.."))
            .state(ArtifactState.STAGED).updatedAt(Instant.EPOCH).build();
        try (var resource = restored.configurationResource()) {
            assertArrayEquals(BYTES, resource.read());
        }
        assertThrows(IllegalStateException.class, () -> record.toBuilder()
            .location(work.resolve("another-root")).build().configurationResource());
        var changed = changedManaged(record);
        assertThrows(IllegalStateException.class, () -> record.toBuilder()
            .managedPackage(changed).build().configurationResource());
        try (var resource = record.toBuilder().managedPackage(changed)
            .managedPackage(record.managedPackage()).build().configurationResource()) {
            assertArrayEquals(BYTES, resource.read());
        }
        var revision = "0".repeat(64);
        var altered = ManagedPluginPackage.builder().pluginId(record.pluginId())
            .version(record.version()).packageRevision(revision)
            .facets(record.managedPackage().facets().stream().map(facet -> new ManagedFacet(
                facet.artifactId(), record.pluginId(), revision, facet.facet())).toList()).build();
        assertThrows(IllegalStateException.class, () -> record.toBuilder()
            .packageRevision(revision).managedPackage(altered).build().configurationResource());
    }

    @Test
    void reopenedStoreReconstructsEvidenceFromVerifiedPackage(@TempDir Path work) throws Exception {
        var record = install(work, BYTES);
        try (var reopened = new PluginPackageStore(work.resolve("store"));
             var resource = reopened.find(record.pluginId(), record.packageRevision())
                 .orElseThrow().configurationResource()) {
            assertArrayEquals(BYTES, resource.read());
        }
    }

    @Test
    void closeBeforeReadDoesNotOpenAndRejectsLaterRead(@TempDir Path work) throws Exception {
        var record = install(work, BYTES);
        var resource = record.configurationResource();
        resource.close();
        Files.delete(record.location().resolve(FILE));
        assertThrows(IllegalStateException.class, resource::read);
        resource.close();
    }

    private static ManagedPluginPackage changedManaged(PluginPackageRecord record) {
        var current = record.managedPackage().facets().getFirst();
        var facet = current.facet();
        var changedFacet = new PluginFacet(facet.facetId(), facet.role(), facet.runtimeId(),
            facet.executionTarget(), facet.payload(), facet.payloadDigest(), facet.dependencies(),
            List.of("changed-capability"));
        return ManagedPluginPackage.builder().pluginId(record.pluginId()).version(record.version())
            .packageRevision(record.packageRevision()).facets(List.of(new ManagedFacet(
                current.artifactId(), record.pluginId(), record.packageRevision(), changedFacet)))
            .build();
    }

    static void assertSafe(ArtifactException failure) {
        assertNull(failure.path());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertTrue(List.of("cannot read package configuration resource",
            "package configuration resource content identity mismatch",
            "cannot close package configuration resource").contains(failure.getMessage()));
    }

    private static PluginPackageRecord install(Path work, byte[] bytes) throws Exception {
        var source = source(work);
        if (bytes != null) {
            Files.write(source.resolve(FILE), bytes);
        }
        return installSource(work, source);
    }

    private static PluginPackageRecord installSource(Path work, Path source) {
        try (var store = new PluginPackageStore(work.resolve("store"));
             var transaction = store.prepareInstall(source)) {
            var saved = transaction.save();
            return store.find(saved.pluginId(), saved.packageRevision()).orElseThrow();
        }
    }

    private static Path source(Path work) throws Exception {
        var source = Files.createDirectory(work.resolve("source"));
        Files.writeString(source.resolve("payload.bin"), "payload");
        Files.writeString(source.resolve(PluginPackage.MANIFEST), """
            format: 1
            id: example.config
            version: 1.0.0
            facets:
              - id: main
                role: host
                runtime: java
                target: host
                payload: payload.bin
                dependencies: []
                capabilities: []
            """);
        return source;
    }
}
