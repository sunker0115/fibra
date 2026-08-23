package com.sstlfsj.fibra.loader.pf4j;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginPackageInspectorTest {

    @Test
    void exposesStableArtifactFailureTypes(@TempDir Path packagesRoot) {
        var source = packagesRoot.resolve("candidate.zip");
        var failure = new FibraArtifactException(
            FibraArtifactErrorStage.VALIDATE,
            List.of(source),
            List.of("example.plugin"),
            "invalid candidate",
            null
        );
        var busy = new FibraPluginLoaderBusyException("loader transaction is active");

        assertEquals(FibraArtifactErrorStage.VALIDATE, failure.stage());
        assertEquals(List.of(source), failure.packages());
        assertEquals(List.of("example.plugin"), failure.artifactIds());
        assertThrows(UnsupportedOperationException.class,
            () -> failure.packages().add(packagesRoot));
        assertInstanceOf(IllegalStateException.class, busy);
    }

    @Test
    void inspectsStandardInstalledDirectory(@TempDir Path packagesRoot) throws Exception {
        var packageRoot = PluginPackageFixtures.standardDirectory(
            packagesRoot, "example.plugin", "1.2.0");

        var inspected = new PluginPackageInspector().inspectDirectory(packageRoot);

        assertEquals(packageRoot.toRealPath(), inspected.packageRoot());
        assertEquals("example.plugin", inspected.descriptor().getPluginId());
        assertEquals("1.2.0", inspected.descriptor().getVersion());
        assertEquals(packageRoot.resolve("lib/example.plugin-1.2.0.jar").toRealPath(),
            inspected.mainJar());
        assertEquals(List.of(inspected.mainJar()), inspected.classpath());
        assertEquals(List.of(), inspected.entrypointClassNames());
        assertTrue(inspected.digest().matches("[0-9a-f]{64}"));
    }

    @Test
    void rejectsNonemptyPluginClass(@TempDir Path packagesRoot) throws Exception {
        var packageRoot = PluginPackageFixtures.standardDirectory(
            packagesRoot, "example.plugin", "1.2.0");
        PluginPackageFixtures.writeProperties(packageRoot, Map.of(
            "plugin.id", "example.plugin",
            "plugin.version", "1.2.0",
            "plugin.class", "example.Plugin"
        ));

        assertValidation(packageRoot,
            () -> new PluginPackageInspector().inspectDirectory(packageRoot));
    }

    @Test
    void rejectsPluginRequires(@TempDir Path packagesRoot) throws Exception {
        var packageRoot = PluginPackageFixtures.standardDirectory(
            packagesRoot, "example.plugin", "1.2.0");
        PluginPackageFixtures.writeProperties(packageRoot, Map.of(
            "plugin.id", "example.plugin",
            "plugin.version", "1.2.0",
            "plugin.requires", ">=1.0.0"
        ));

        assertValidation(packageRoot,
            () -> new PluginPackageInspector().inspectDirectory(packageRoot));
    }

    @Test
    void rejectsUnknownDescriptorProperty(@TempDir Path packagesRoot) throws Exception {
        var packageRoot = PluginPackageFixtures.standardDirectory(
            packagesRoot, "example.plugin", "1.2.0");
        PluginPackageFixtures.writeProperties(packageRoot, Map.of(
            "plugin.id", "example.plugin",
            "plugin.version", "1.2.0",
            "custom.lifecycle", "other-container"
        ));

        assertValidation(packageRoot,
            () -> new PluginPackageInspector().inspectDirectory(packageRoot));
    }

    @Test
    void rejectsInvalidIdentityAndDirectoryName(@TempDir Path packagesRoot) throws Exception {
        var invalidId = PluginPackageFixtures.standardDirectory(
            packagesRoot, "example.plugin", "1.2.0");
        PluginPackageFixtures.writeProperties(invalidId, Map.of(
            "plugin.id", "-invalid",
            "plugin.version", "1.2.0"
        ));
        assertValidation(invalidId,
            () -> new PluginPackageInspector().inspectDirectory(invalidId));

        var mismatchedRoot = PluginPackageFixtures.standardDirectory(
            packagesRoot, "other.plugin", "1.2.0");
        PluginPackageFixtures.writeProperties(mismatchedRoot, Map.of(
            "plugin.id", "declared.plugin",
            "plugin.version", "1.2.0"
        ));
        assertValidation(mismatchedRoot,
            () -> new PluginPackageInspector().inspectDirectory(mismatchedRoot));
    }

    @Test
    void rejectsInvalidSemverAndMissingMainJar(@TempDir Path packagesRoot) throws Exception {
        var invalidVersion = PluginPackageFixtures.standardDirectory(
            packagesRoot, "invalid.version", "1.2.0");
        PluginPackageFixtures.writeProperties(invalidVersion, Map.of(
            "plugin.id", "invalid.version",
            "plugin.version", "not-semver"
        ));
        assertValidation(invalidVersion,
            () -> new PluginPackageInspector().inspectDirectory(invalidVersion));

        var missingMain = PluginPackageFixtures.standardDirectory(
            packagesRoot, "missing.main", "1.2.0");
        java.nio.file.Files.move(
            missingMain.resolve("lib/missing.main-1.2.0.jar"),
            missingMain.resolve("lib/private.jar")
        );
        assertValidation(missingMain,
            () -> new PluginPackageInspector().inspectDirectory(missingMain));
    }

    @Test
    void rejectsNonJarLibChildAndBundledSharedRuntime(@TempDir Path packagesRoot)
        throws Exception {
        var nonJar = PluginPackageFixtures.standardDirectory(
            packagesRoot, "non.jar", "1.2.0");
        java.nio.file.Files.writeString(nonJar.resolve("lib/readme.txt"), "invalid");
        assertValidation(nonJar,
            () -> new PluginPackageInspector().inspectDirectory(nonJar));

        var sharedRuntime = PluginPackageFixtures.standardDirectory(
            packagesRoot, "shared.runtime", "1.2.0");
        PluginPackageFixtures.writeJar(
            sharedRuntime.resolve("lib/private.jar"),
            PluginPackageFixtures.entries("org/slf4j/Bundled.class", "invalid")
        );
        assertValidation(sharedRuntime,
            () -> new PluginPackageInspector().inspectDirectory(sharedRuntime));
    }

    @Test
    void readsOnlyMainJarEntrypointIndex(@TempDir Path packagesRoot) throws Exception {
        var packageRoot = PluginPackageFixtures.standardDirectory(
            packagesRoot, "indexed.plugin", "1.2.0");
        PluginPackageFixtures.writeJar(
            packageRoot.resolve("lib/indexed.plugin-1.2.0.jar"),
            Map.of("META-INF/extensions.idx",
                PluginPackageFixtures.extensionIndex(List.of("example.MainEntrypoint")))
        );
        PluginPackageFixtures.writeJar(
            packageRoot.resolve("lib/private.jar"),
            Map.of("META-INF/extensions.idx",
                PluginPackageFixtures.extensionIndex(List.of("example.PrivateEntrypoint")))
        );

        var inspected = new PluginPackageInspector().inspectDirectory(packageRoot);

        assertEquals(List.of("example.MainEntrypoint"), inspected.entrypointClassNames());
        assertEquals(List.of(
            packageRoot.resolve("lib/indexed.plugin-1.2.0.jar").toRealPath(),
            packageRoot.resolve("lib/private.jar").toRealPath()
        ), inspected.classpath());
    }

    @Test
    void propertiesRemainTheOnlyDescriptorSourceAndIndexDeclarationsArePreserved(
        @TempDir Path packagesRoot) throws Exception {
        var packageRoot = PluginPackageFixtures.standardDirectory(
            packagesRoot, "descriptor.plugin", "1.2.0");
        PluginPackageFixtures.writeProperties(packageRoot, Map.of(
            "plugin.id", "descriptor.plugin",
            "plugin.version", "1.2.0",
            "plugin.dependencies", "contract?@>=1.0.0 & <2.0.0",
            "plugin.description", "fixture",
            "plugin.provider", "fibra",
            "plugin.license", "Apache-2.0"
        ));
        var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Plugin-Id", "manifest.plugin");
        manifest.getMainAttributes().putValue("Plugin-Version", "9.9.9");
        PluginPackageFixtures.writeJar(
            packageRoot.resolve("lib/descriptor.plugin-1.2.0.jar"),
            manifest,
            Map.of("META-INF/extensions.idx", PluginPackageFixtures.extensionIndex(
                List.of("example.First", "example.Second")))
        );

        var inspected = new PluginPackageInspector().inspectDirectory(packageRoot);

        assertEquals("descriptor.plugin", inspected.descriptor().getPluginId());
        assertEquals("1.2.0", inspected.descriptor().getVersion());
        assertEquals(1, inspected.descriptor().getDependencies().size());
        assertTrue(inspected.descriptor().getDependencies().getFirst().isOptional());
        assertEquals(List.of("example.First", "example.Second"),
            inspected.entrypointClassNames());
    }

    @Test
    void canonicalDigestIgnoresAbsoluteRootAndChangesWithProtocolBytes(@TempDir Path work)
        throws Exception {
        var first = PluginPackageFixtures.standardDirectory(
            java.nio.file.Files.createDirectory(work.resolve("first")),
            "same.plugin", "1.2.0");
        var second = PluginPackageFixtures.standardDirectory(
            java.nio.file.Files.createDirectory(work.resolve("second")),
            "same.plugin", "1.2.0");
        var inspector = new PluginPackageInspector();

        var firstDigest = inspector.inspectDirectory(first).digest();
        var secondDigest = inspector.inspectDirectory(second).digest();

        assertEquals(firstDigest, secondDigest);
        PluginPackageFixtures.writeJar(second.resolve("lib/private.jar"),
            PluginPackageFixtures.entries("private/value.txt", "changed"));
        assertNotEquals(firstDigest, inspector.inspectDirectory(second).digest());
    }

    @Test
    void copiesAndInspectsStandardSingleRootZipWithoutChangingSource(@TempDir Path work)
        throws Exception {
        var sourceRoot = PluginPackageFixtures.standardDirectory(
            java.nio.file.Files.createDirectory(work.resolve("source")),
            "zip.plugin", "1.2.0");
        var candidate = PluginPackageFixtures.zipDirectory(
            sourceRoot, work.resolve("candidate.zip"));
        var originalBytes = java.nio.file.Files.readAllBytes(candidate);
        var preflight = java.nio.file.Files.createDirectory(work.resolve("preflight"));

        var inspected = new PluginPackageInspector().inspectCandidate(candidate, preflight);

        assertEquals("zip.plugin", inspected.descriptor().getPluginId());
        assertEquals(preflight.resolve("next/zip.plugin").toRealPath(),
            inspected.packageRoot());
        assertTrue(java.nio.file.Files.isDirectory(preflight.resolve("input")));
        try (var inputs = java.nio.file.Files.list(preflight.resolve("input"))) {
            assertEquals(1, inputs.count());
        }
        assertTrue(java.util.Arrays.equals(originalBytes,
            java.nio.file.Files.readAllBytes(candidate)));
    }

    @Test
    void rejectsZipSlipAbsolutePathAndSymlink(@TempDir Path work) throws Exception {
        var inspector = new PluginPackageInspector();
        var zipSlip = PluginPackageFixtures.writeZip(
            work.resolve("slip.zip"),
            PluginPackageFixtures.entries("../escaped.txt", "invalid")
        );
        assertCandidateValidation(zipSlip, work.resolve("slip-work"),
            () -> inspector.inspectCandidate(zipSlip, work.resolve("slip-work")));

        var absolute = PluginPackageFixtures.writeZip(
            work.resolve("absolute.zip"),
            PluginPackageFixtures.entries("/absolute.txt", "invalid")
        );
        assertCandidateValidation(absolute, work.resolve("absolute-work"),
            () -> inspector.inspectCandidate(absolute, work.resolve("absolute-work")));

        var symlink = PluginPackageFixtures.writeSymlinkZip(
            work.resolve("symlink.zip"), "zip.plugin/lib/link.jar", "target.jar");
        assertCandidateValidation(symlink, work.resolve("symlink-work"),
            () -> inspector.inspectCandidate(symlink, work.resolve("symlink-work")));
    }

    @Test
    void rejectsMultipleRootsAndNonstandardZipLayer(@TempDir Path work) throws Exception {
        var inspector = new PluginPackageInspector();
        var multiple = PluginPackageFixtures.writeZip(
            work.resolve("multiple.zip"),
            Map.of(
                "first/plugin.properties", "plugin.id=first\n".getBytes(),
                "second/plugin.properties", "plugin.id=second\n".getBytes()
            )
        );
        assertCandidateValidation(multiple, work.resolve("multiple-work"),
            () -> inspector.inspectCandidate(multiple, work.resolve("multiple-work")));

        var extraLayer = PluginPackageFixtures.writeZip(
            work.resolve("extra.zip"),
            PluginPackageFixtures.entries("zip.plugin/nested/plugin.properties", "invalid")
        );
        assertCandidateValidation(extraLayer, work.resolve("extra-work"),
            () -> inspector.inspectCandidate(extraLayer, work.resolve("extra-work")));
    }

    private static FibraArtifactException assertValidation(
        Path packageRoot, org.junit.jupiter.api.function.Executable action) throws Exception {
        var error = assertThrows(FibraArtifactException.class, action);
        assertEquals(FibraArtifactErrorStage.VALIDATE, error.stage());
        assertEquals(List.of(packageRoot.toRealPath()), error.packages());
        return error;
    }

    private static void assertCandidateValidation(
        Path candidate, Path preflight,
        org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(FibraArtifactException.class, action);
        assertEquals(FibraArtifactErrorStage.VALIDATE, error.stage());
        assertEquals(List.of(candidate.toAbsolutePath().normalize()), error.packages());
        assertTrue(java.nio.file.Files.notExists(preflight.resolve("next/escaped.txt")));
    }
}
