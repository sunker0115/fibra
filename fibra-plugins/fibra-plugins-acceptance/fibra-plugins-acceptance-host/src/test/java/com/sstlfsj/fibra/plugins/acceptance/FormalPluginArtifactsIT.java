package com.sstlfsj.fibra.plugins.acceptance;

import com.sstlfsj.fibra.artifact.PluginPackage;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormalPluginArtifactsIT {
    private static final Set<String> CONTRACT_PACKAGES = Set.of(
        "fibra-fs", "fibra-subprocess", "fibra-shell", "fibra-storage");

    @Test
    void packagesUseExactReleaseVersionsAndOnlyDeclaredFacetDependencyEdges(@TempDir Path packageSources) {
        var expected = new LinkedHashMap<String, List<String>>();
        expected.put("fibra-fs", List.of());
        expected.put("fibra-fs-local", List.of("fibra-fs"));
        expected.put("fibra-tool-fs", List.of("fibra-fs"));
        expected.put("fibra-subprocess", List.of());
        expected.put("fibra-subprocess-local", List.of("fibra-subprocess"));
        expected.put("fibra-tool-fs-search", List.of("fibra-subprocess"));
        expected.put("fibra-shell", List.of());
        expected.put("fibra-shell-local", List.of("fibra-shell", "fibra-subprocess"));
        expected.put("fibra-tool-shell", List.of("fibra-shell"));
        expected.put("fibra-storage", List.of());
        expected.put("fibra-storage-json", List.of("fibra-storage"));
        expected.put("fibra-tool-storage", List.of("fibra-storage"));
        var releaseVersion = version("fibra-fs");

        expected.forEach((pluginId, dependencies) -> {
            var jar = PluginAcceptanceHarness.stagedJar(pluginId);
            var request = PluginAcceptanceHarness.installRequest(packageSources, jar);
            var pluginPackage = PluginPackage.read(request.source());
            assertEquals(pluginId, pluginPackage.pluginId().value());
            assertEquals(releaseVersion, pluginPackage.version());
            assertEquals(pluginId + '-' + releaseVersion + ".jar", jar.getFileName().toString());
            assertEquals(1, pluginPackage.facets().size());
            var facet = pluginPackage.facets().getFirst();
            assertEquals("main", facet.facetId().value());
            assertEquals("host", facet.role().value());
            assertEquals("java", facet.runtimeId().value());
            assertEquals("host", facet.executionTarget().value());
            assertTrue(facet.requiredCapabilities().isEmpty());
            assertEquals(dependencies, facet.dependencies().stream()
                .map(value -> value.pluginId().value()).toList());
            assertTrue(facet.dependencies().stream()
                .allMatch(value -> value.facetId().value().equals("main")));
            var descriptor = PluginAcceptanceHarness.manifest(jar);
            assertFalse(descriptor.contains("id:"));
            assertFalse(descriptor.contains("version:"));
            assertFalse(descriptor.contains("requires:"));
            if (CONTRACT_PACKAGES.contains(pluginId)) {
                assertEquals("{}\n", descriptor,
                    pluginId + " contract facet descriptor must be empty");
            }
        });
    }

    @Test
    void implementationJarsDoNotDuplicateHostOrDynamicContractClasses() throws Exception {
        var contractClasses = new LinkedHashSet<String>();
        for (var contract : List.of("fibra-fs", "fibra-subprocess", "fibra-shell", "fibra-storage")) {
            contractClasses.addAll(classEntries(PluginAcceptanceHarness.stagedJar(contract)));
        }
        var hostContractClasses = classpathPackageClasses(ToolRequest.class,
            "com/sstlfsj/fibra/plugins/tool");
        assertFalse(contractClasses.isEmpty());
        assertFalse(hostContractClasses.isEmpty());

        for (var artifact : List.of("fibra-fs-local", "fibra-tool-fs",
            "fibra-subprocess-local", "fibra-tool-fs-search", "fibra-shell-local",
            "fibra-tool-shell", "fibra-storage-json", "fibra-tool-storage")) {
            var entries = classEntries(PluginAcceptanceHarness.stagedJar(artifact));
            assertTrue(disjoint(entries, contractClasses), artifact + " bundles a dynamic contract class");
            assertTrue(disjoint(entries, hostContractClasses), artifact + " bundles fibra-tool-api");
            assertFalse(entries.contains("com/sstlfsj/fibra/Context.class"));
            assertFalse(entries.contains("com/sstlfsj/fibra/bridge/ContributionKind.class"));
        }
    }

    @Test
    void searchJarRelocatesItsPrivateJacksonImplementationAndKeepsServicesResolvable()
        throws Exception {
        var search = PluginAcceptanceHarness.stagedJar("fibra-tool-fs-search");
        try (var archive = new JarFile(search.toFile(), true)) {
            var entries = archive.stream().map(entry -> entry.getName()).toList();
            var unrelocated = entries.stream()
                .filter(name -> name.startsWith("tools/jackson/")
                    || name.startsWith("com/fasterxml/jackson/"))
                .toList();
            assertTrue(unrelocated.isEmpty(), () -> "unrelocated Jackson entries: " + unrelocated);
            var unrelocatedMultiRelease = entries.stream()
                .filter(name -> name.startsWith("META-INF/versions/")
                    && (name.contains("/tools/jackson/")
                        || name.contains("/com/fasterxml/jackson/")))
                .toList();
            assertTrue(unrelocatedMultiRelease.isEmpty(),
                () -> "unrelocated multi-release Jackson entries: " + unrelocatedMultiRelease);
            var unrelocatedServices = entries.stream()
                .filter(name -> name.startsWith("META-INF/services/tools.jackson."))
                .toList();
            assertTrue(unrelocatedServices.isEmpty(),
                () -> "unrelocated Jackson services: " + unrelocatedServices);
            assertTrue(entries.stream().anyMatch(name -> name.startsWith(
                "com/sstlfsj/fibra/plugins/fs/search/internal/jackson/")));
            assertTrue(entries.contains(
                "com/sstlfsj/fibra/plugins/fs/search/internal/jackson/annotation/JsonSerializeAs.class"));
            var notice = archive.getJarEntry("META-INF/NOTICE");
            assertNotNull(notice);
            try (var input = archive.getInputStream(notice)) {
                var content = new String(input.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(content.contains("Jackson JSON processor"));
                assertTrue(content.contains("FastDoubleParser"));
                assertTrue(content.contains("Schubfach"));
            }
            for (var service : entries.stream().filter(name -> name.startsWith("META-INF/services/"))
                .toList()) {
                var entry = archive.getJarEntry(service);
                assertNotNull(entry);
                try (var input = archive.getInputStream(entry)) {
                    for (var provider : new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .lines().map(String::trim).filter(line -> !line.isEmpty())
                        .filter(line -> !line.startsWith("#")).toList()) {
                        assertTrue(entries.contains(provider.replace('.', '/') + ".class"),
                            () -> service + " points to missing provider " + provider);
                    }
                }
            }
        }
    }

    @Test
    void localImplementationJarsKeepJniCompatiblePrivateJnaAndCarryItsNotice()
        throws Exception {
        for (var artifact : List.of("fibra-fs-local", "fibra-subprocess-local")) {
            try (var archive = new JarFile(
                PluginAcceptanceHarness.stagedJar(artifact).toFile(), true)) {
                var entries = archive.stream().map(entry -> entry.getName()).toList();
                assertTrue(entries.contains("com/sun/jna/Native.class"));
                assertTrue(entries.contains("com/sun/jna/win32-x86/jnidispatch.dll"));
                assertTrue(entries.contains("com/sun/jna/win32-x86-64/jnidispatch.dll"));
                assertTrue(entries.contains("com/sun/jna/win32-aarch64/jnidispatch.dll"));
                var notice = archive.getJarEntry("META-INF/THIRD_PARTY_NOTICES.md");
                assertNotNull(notice);
                try (var input = archive.getInputStream(notice)) {
                    var content = new String(input.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                    assertTrue(content.contains("JNA"));
                    assertTrue(content.contains("Apache License 2.0"));
                }
            }
        }
        for (var artifact : List.of("fibra-fs", "fibra-tool-fs", "fibra-subprocess",
            "fibra-tool-fs-search")) {
            assertTrue(classEntries(PluginAcceptanceHarness.stagedJar(artifact)).stream()
                .noneMatch(name -> name.startsWith("com/sun/jna/")), artifact + " bundles JNA");
        }
    }

    private static String version(String pluginId) {
        var fileName = PluginAcceptanceHarness.stagedJar(pluginId).getFileName().toString();
        return fileName.substring(pluginId.length() + 1, fileName.length() - 4);
    }

    private static Set<String> classEntries(Path jar) {
        try (var archive = new JarFile(jar.toFile(), true)) {
            var result = new LinkedHashSet<String>();
            archive.stream().map(entry -> entry.getName()).filter(name -> name.endsWith(".class"))
                .forEach(result::add);
            return Set.copyOf(result);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot inspect " + jar, failure);
        }
    }

    private static Set<String> classpathPackageClasses(Class<?> anchor, String packagePath)
        throws URISyntaxException, IOException {
        var location = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(location)) return classEntries(location).stream()
            .filter(name -> name.startsWith(packagePath + "/")).collect(
                java.util.stream.Collectors.toUnmodifiableSet());
        var packageRoot = location.resolve(packagePath);
        assertTrue(Files.isDirectory(packageRoot), () -> "missing classpath package " + packageRoot);
        try (var paths = Files.walk(packageRoot)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".class"))
                .map(path -> location.relativize(path).toString().replace('\\', '/'))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private static boolean disjoint(Set<String> left, Set<String> right) {
        return left.stream().noneMatch(right::contains);
    }
}
